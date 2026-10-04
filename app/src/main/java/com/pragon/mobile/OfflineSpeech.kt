package com.pragon.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Offline speech recognition (Vosk). Unlike Google's recognizer it does NOT ask Android for audio focus and never beeps,
 * so a video or song keeps playing smoothly while the float bubble listens. Everything stays on the phone.
 * The language pack (about 40 MB) is downloaded once from Settings; after that no internet is needed.
 */
object OfflineSpeech {

    class Pack(val id: String, val label: String, val mb: Int, val url: String)

    private const val BASE = "https://alphacephei.com/vosk/models/"
    val PACKS = listOf(
        Pack("en-us", "English (US)", 40, BASE + "vosk-model-small-en-us-0.15.zip"),
        Pack("en-in", "English (India)", 36, BASE + "vosk-model-small-en-in-0.4.zip"),
        Pack("hi", "Hindi", 42, BASE + "vosk-model-small-hi-0.22.zip"),
    )

    fun pack(id: String): Pack = PACKS.firstOrNull { it.id == id } ?: PACKS[0]

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    fun dirOf(ctx: Context, id: String) = File(root(ctx), id)
    fun installed(ctx: Context, id: String): Boolean = File(dirOf(ctx, id), "am").isDirectory || File(dirOf(ctx, id), "conf").isDirectory
    fun ready(ctx: Context): Boolean = Prefs.speechOffline(ctx) && installed(ctx, Prefs.offlinePack(ctx)) && !broken

    /** Set when the native library or model failed to load, so float mode falls back to Google instead of going deaf. */
    @Volatile var broken = false
    @Volatile var lastProblem = ""

    @Volatile private var model: Model? = null
    @Volatile private var modelId = ""

    @Synchronized fun model(ctx: Context): Model? {
        val id = Prefs.offlinePack(ctx)
        if (model != null && modelId == id) return model
        try { model?.close() } catch (e: Throwable) { }
        model = null
        return try {
            Model(dirOf(ctx, id).absolutePath).also { model = it; modelId = id }
        } catch (e: Throwable) {
            broken = true; lastProblem = "The speech model didn't load: ${e.message}"; null
        }
    }

    @Synchronized fun release() { try { model?.close() } catch (e: Throwable) { }; model = null; modelId = "" }

    // ── download ─────────────────────────────────────────────────────────

    /** Downloads and unpacks a language pack. Blocking: call from a background thread. [progress] gets (percent, message). */
    fun download(ctx: Context, id: String, progress: (Int, String) -> Unit): String? {
        val p = pack(id)
        val tmp = File(ctx.cacheDir, "vosk-${p.id}.zip")
        try {
            progress(0, "Connecting...")
            // The Privacy Shield client: if the Shield is ON this is refused, with a message saying how to allow it.
            val req = Request.Builder().url(p.url).get().build()
            LocalLlmClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return "The download server answered HTTP ${r.code}."
                val body = r.body ?: return "The download was empty."
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress((done * 90 / total).toInt(), "Downloading ${done / 1_000_000} MB")
                        }
                    }
                }
            }
            progress(92, "Unpacking...")
            val dest = dirOf(ctx, p.id)
            dest.deleteRecursively(); dest.mkdirs()
            val canon = dest.canonicalPath
            ZipInputStream(tmp.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    // the zip has one top folder: drop it
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val f = File(dest, rel)
                    if (!f.canonicalPath.startsWith(canon + File.separator)) continue   // never write outside the model folder
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                }
            }
            tmp.delete()
            if (!installed(ctx, p.id)) return "The pack unpacked but doesn't look like a Vosk model."
            broken = false; release()
            progress(100, "Ready: ${p.label}")
            return null
        } catch (e: Exception) {
            tmp.delete()
            return (e.message ?: "download failed") +
                (if (Guard.privacyOn(ctx)) " (Privacy Shield is ON: turn it off for the download, or add alphacephei.com to Trusted hosts.)" else "")
        }
    }

    // ── listening ────────────────────────────────────────────────────────

    /**
     * Keeps the microphone open and calls [onFinal] with each finished sentence. [onHearing] fires once when speech starts.
     * Uses AudioRecord directly: no audio focus is requested, so other apps keep playing.
     */
    class Listener(
        private val ctx: Context,
        private val onHearing: () -> Unit,
        private val onFinal: (String) -> Unit,
        private val onProblem: (String) -> Unit,
    ) {
        @Volatile private var going = false
        @Volatile private var cancelled = false
        private var thread: Thread? = null

        /** Blocking (loads the model the first time, 1-3 s): call from a background thread. */
        fun start(): Boolean {
            if (going) return true
            val m = model(ctx) ?: run { onProblem(lastProblem.ifBlank { "No speech model." }); return false }
            if (cancelled) return false
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) { onProblem("This phone can't record 16 kHz audio."); return false }
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
            } catch (e: Throwable) { onProblem("Microphone: ${e.message}"); return false }
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onProblem("The microphone is busy or not allowed."); return false }
            val recognizer = try { Recognizer(m, rate.toFloat()) } catch (e: Throwable) {
                rec.release(); broken = true; lastProblem = "Speech engine failed: ${e.message}"; onProblem(lastProblem); return false
            }
            if (cancelled) { try { rec.release() } catch (e: Throwable) { }; try { recognizer.close() } catch (e: Throwable) { }; return false }
            going = true
            thread = Thread {
                var hearing = false
                try {
                    rec.startRecording()
                    val buf = ByteArray(4096)
                    while (going) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { if (n < 0) break else continue }
                        if (recognizer.acceptWaveForm(buf, n)) {
                            hearing = false
                            val text = JSONObject(recognizer.result).optString("text").trim()
                            if (text.isNotEmpty()) onFinal(text)
                        } else if (!hearing) {
                            val part = JSONObject(recognizer.partialResult).optString("partial")
                            if (part.isNotBlank()) { hearing = true; onHearing() }
                        }
                    }
                } catch (e: Throwable) {
                    if (going) onProblem("Offline listening stopped: ${e.message}")
                } finally {
                    try { rec.stop() } catch (e: Throwable) { }
                    try { rec.release() } catch (e: Throwable) { }
                    try { recognizer.close() } catch (e: Throwable) { }
                }
            }.also { it.isDaemon = true; it.start() }
            return true
        }

        fun stop() {
            cancelled = true
            going = false
            try { thread?.join(300) } catch (e: Exception) { }
            thread = null
        }
    }
}
