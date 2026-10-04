package com.pragon.mobile

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "What's on my screen?" / "answer the question on the screen" / "read the screen".
 * Works only when YOU turned on "Let Pragon read my screen" (Settings > Security). Pragon reads the screen's text through the
 * accessibility service and, on Android 11+, also takes a screenshot so it can describe photos, pictures and anything that
 * is not text. Both go ONLY to the AI engine you chose in Settings. With Privacy Shield on, anything outside your own
 * network is refused (so Gemini is blocked until you switch the Shield off). Nothing is sent while a password box is
 * visible. The question is asked on its own: it is not added to your chat history.
 */
object ScreenAI {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val SYSTEM =
        "You are Pragon, a phone assistant. The user shows you their phone screen as a picture (when available) plus the text " +
            "currently visible on it (in reading order, one item per line), and asks about it. You can describe photos and other " +
            "images (what they show: objects, animals, places, food, products, scenes, clothing, charts, memes, logos), read and " +
            "explain text in any language, and read out numbers, prices, dates and totals exactly. Use plain spoken language in at " +
            "most five short sentences, with no markdown or lists. Do not identify real people from their faces: describe them " +
            "(age range, clothing, what they are doing) and only name someone if a caption or text on the screen names them. " +
            "If the answer isn't visible on the screen, say so. If the screen shows a question with options, give the answer and " +
            "the option's letter or text."

    private fun gate(ctx: Context): CommandExecutor.Res? {
        if (!Guard.screenReadingOn(ctx))
            return CommandExecutor.Res(false, "I'm not allowed to read your screen. Turn on \"Let Pragon read my screen\" in Settings > Security first.", label = "Screen")
        if (PragonAccessibilityService.instance == null)
            return CommandExecutor.Res(false, "Reading the screen needs the Pragon accessibility service turned on.", label = "Screen")
        return null
    }

    /** The visible screen text, or a failure message. */
    private fun grab(ctx: Context): Pair<String?, CommandExecutor.Res?> {
        gate(ctx)?.let { return null to it }
        val txt = AccessFeatures.Screen.dump(PragonAccessibilityService.instance!!, ctx.packageName)
            ?: return null to CommandExecutor.Res(false, "I couldn't read the screen right now.", label = "Screen")
        if (txt.isBlank()) return null to CommandExecutor.Res(false, "I can't see any text on this screen. It may be a picture, a video or a protected screen.", label = "Screen")
        return txt to null
    }

    /** "read the screen": the phone's own text to speech reads what's visible. No AI, nothing leaves the phone. */
    fun read(ctx: Context): CommandExecutor.Res {
        val (txt, fail) = grab(ctx)
        if (fail != null) return fail
        val t = txt!!.lines().filter { it.isNotBlank() }.joinToString(". ").take(2500)
        try { Speaker.get(ctx).speak(t) } catch (e: Exception) { return CommandExecutor.Res(false, "I couldn't start reading: ${e.message}") }
        return CommandExecutor.Res(true, "Reading the screen aloud.", label = "Screen")
    }

    /** The screen as a small JPEG (base64), or null when Android won't give a screenshot. Blocking. */
    private fun shot(svc: PragonAccessibilityService): String? {
        val bmp = svc.screenshot() ?: return null
        return try {
            val maxSide = 1280
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val bos = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bos)
            android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) { null }
    }

    private fun clean(s: String) = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").replace(Regex("[*_`#]+"), "").trim()

    /**
     * Sends [user] (and optionally a screenshot) to the AI engine(s) you chose and returns (answer, null) or (null, problem).
     * AUTO: Gemini first for pictures when it is allowed, otherwise (or if it fails) the local vision model.
     * [json] asks the model to answer with a JSON object only (used by the screen agent).
     */
    fun complete(ctx: Context, sys: String, user: String, img: String?, maxTokens: Int = 400, json: Boolean = false): Pair<String?, String?> {
        Guard.engineProblem(ctx)?.let { return null to it }
        val engines: List<String> = when (Prefs.engine(ctx)) {
            "gemini" -> listOf("gemini")
            "ollama" -> listOf("ollama")
            "openai" -> listOf("openai")
            else -> AutoEngine.visionOrder(ctx).map { if (it == "cloud") "gemini" else Prefs.localEngine(ctx) }
        }
        var failure = "No AI engine is available."
        for (engine in engines) {
            try {
                val ans = try {
                    when (engine) {
                        "ollama" -> askOllama(ctx, sys, user, img, maxTokens, json)
                        "openai" -> askOpenAi(ctx, sys, user, img, maxTokens)
                        else -> askGemini(ctx, sys, user, img, maxTokens, json)
                    }
                } catch (e: AiFail) {
                    // a model without eyes may refuse the picture: still answer from the text
                    if (img != null && engine != "gemini") {
                        val t = if (engine == "ollama") askOllama(ctx, sys, user, null, maxTokens, json) else askOpenAi(ctx, sys, user, null, maxTokens)
                        if (json) t else "(This model can't look at pictures, so this is from the text only.) $t"
                    } else throw e
                }
                val c = clean(ans)
                if (c.isNotBlank()) return c to null
                failure = "The AI gave an empty answer."
            } catch (e: AiFail) {
                failure = e.message ?: "The AI didn't answer."
            } catch (e: Exception) {
                failure = "I couldn't reach your AI (${e.message ?: "no connection"})."
            }
        }
        return null to failure
    }

    /** A screenshot as base64 JPEG for the agent / questions (null when not possible). */
    fun screenshotB64(ctx: Context): String? {
        val svc = PragonAccessibilityService.instance ?: return null
        return shot(svc)
    }

    /**
     * Asks the AI about the screen: what is in a photo, what a text says, what a number is...
     * Blocking: call from a background thread.
     */
    fun ask(ctx: Context, question: String): CommandExecutor.Res {
        gate(ctx)?.let { return it }
        Guard.engineProblem(ctx)?.let { return CommandExecutor.Res(false, it, label = "Screen") }
        val svc = PragonAccessibilityService.instance!!
        if (svc.passwordVisible())
            return CommandExecutor.Res(false, "A password box is on the screen, so I won't send this screen anywhere. Move away from it and ask again.", label = "Screen")
        val txt = try { AccessFeatures.Screen.dump(svc, ctx.packageName, 3500) } catch (e: Exception) { null }.orEmpty()
        val img = shot(svc)
        if (img == null && txt.isBlank())
            return CommandExecutor.Res(false, "I can't see anything on this screen. It may be protected (banking, video apps), or Android didn't allow a screenshot. If this keeps happening, switch the Pragon accessibility service off and on once.", label = "Screen")
        val q = question.ifBlank { "Briefly, what is on this screen?" }
        val user = (if (txt.isNotBlank()) "SCREEN TEXT:\n$txt\n\n" else "") +
            (if (img != null) "The screenshot of the phone is attached.\n\n" else "") + "QUESTION: $q"
        val (ans, problem) = complete(ctx, SYSTEM, user, img)
        return if (ans != null) CommandExecutor.Res(true, ans, label = "Screen") else CommandExecutor.Res(false, problem ?: "The AI didn't answer.", label = "Screen")
    }

    private class AiFail(msg: String) : Exception(msg)

    private const val NO_EYES = " If your model can't look at pictures, pick a vision model (for example qwen3.5, gemma3 or llava on Ollama, or Gemini)."

    private fun send(req: Request): String {
        LocalLlmClient.http.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) throw AiFail("The AI answered HTTP ${r.code}: " + raw.take(140) + NO_EYES)
            return raw
        }
    }

    private fun askOllama(ctx: Context, sys: String, user: String, img: String?, maxTokens: Int, json: Boolean): String {
        val u = JSONObject().put("role", "user").put("content", user)
        if (img != null) u.put("images", JSONArray().put(img))
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(u)
        val body = JSONObject().put("model", Prefs.ollamaModel(ctx)).put("messages", msgs).put("stream", false)
            .put("think", false).put("keep_alive", Prefs.ollamaKeepAlive(ctx))
            .put("options", JSONObject().put("num_predict", maxTokens).put("num_ctx", 4096).put("temperature", 0.2))
        if (json) body.put("format", "json")
        val raw = send(Request.Builder().url(LocalLlmClient.ollamaBase(Prefs.ollamaHost(ctx)) + "/api/chat").post(body.toString().toRequestBody(JSON)).build())
        return JSONObject(raw).optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun askOpenAi(ctx: Context, sys: String, user: String, img: String?, maxTokens: Int): String {
        val content: Any = if (img == null) user else JSONArray()
            .put(JSONObject().put("type", "text").put("text", user))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$img")))
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", Prefs.openaiModel(ctx)).put("messages", msgs).put("stream", false).put("max_tokens", maxTokens).put("temperature", 0.2)
        val rb = Request.Builder().url(LocalLlmClient.openaiBase(Prefs.openaiBase(ctx)) + "/chat/completions").post(body.toString().toRequestBody(JSON))
        val k = Prefs.openaiKey(ctx)
        if (k.isNotBlank()) rb.header("Authorization", "Bearer $k")
        val raw = send(rb.build())
        return JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun askGemini(ctx: Context, sys: String, user: String, img: String?, maxTokens: Int, json: Boolean): String {
        val key = Prefs.effectiveAiKey(ctx)
        if (key.isBlank()) throw AiFail("I need a Gemini API key first. Add yours in Settings.")
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (img != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        val models = listOf(Prefs.aiModelWorking(ctx).ifBlank { Prefs.aiModel(ctx) }, Prefs.aiModel(ctx), Prefs.DEFAULT_GEMINI, "gemini-3.5-flash", "gemini-2.5-flash").distinct()
        var lastErr = ""
        for (m in models) {
            for (attempt in 0..1) {
                val gen = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxOf(maxTokens, 900))
                if (json) gen.put("responseMimeType", "application/json")
                if (attempt == 0) {
                    if (m.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                    else if (m.startsWith("gemini-2.5-flash")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", gen)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(JSON)).build()
                var retry = false
                var next = false
                LocalLlmClient.http.newCall(req).execute().use { r ->
                    val raw = r.body?.string() ?: ""
                    if (r.isSuccessful) {
                        val ps = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (ps != null) for (i in 0 until ps.length()) sb.append(ps.getJSONObject(i).optString("text"))
                        if (m != Prefs.aiModelWorking(ctx)) Prefs.saveAiModelWorking(ctx, m)
                        return sb.toString()
                    }
                    lastErr = try { JSONObject(raw).getJSONObject("error").optString("message") } catch (e: Exception) { raw.take(160) }
                    if (lastErr.isBlank()) lastErr = "HTTP ${r.code}"
                    val gone = r.code == 404 || lastErr.contains("no longer available", true) || lastErr.contains("not found", true)
                    if (attempt == 0 && r.code == 400 && lastErr.contains("think", true)) retry = true
                    else if (gone) next = true
                    else throw AiFail("Gemini said no: $lastErr")
                }
                if (retry) continue
                if (next) break
            }
        }
        throw AiFail("Gemini said no: $lastErr")
    }
}
