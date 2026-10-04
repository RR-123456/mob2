package com.pragon.mobile

import android.content.Context
import android.util.Base64
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Meaning-based memory lookup. With many remembered facts, only the ones related to what you just said go into the prompt
 * (so a small model isn't swamped and answers faster). Uses an Ollama embedding model (pull "nomic-embed-text"); if it is
 * missing or Ollama is unreachable, Pragon quietly falls back to sending every fact as before.
 */
object Semantic {
    const val MODEL = "nomic-embed-text"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_embed", Context.MODE_PRIVATE)

    private fun pack(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 4); v.forEach { bb.putFloat(it) }
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }
    private fun unpack(s: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)); return FloatArray(bb.remaining() / 4) { bb.getFloat() }
    }

    /** Embeds [texts] in one call. Null if the model/server isn't available. */
    private fun embed(ctx: Context, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        return try {
            val body = JSONObject().put("model", MODEL).put("input", JSONArray(texts)).put("keep_alive", Prefs.ollamaKeepAlive(ctx))
            val req = Request.Builder().url(LocalLlmClient.ollamaBase(Prefs.ollamaHost(ctx)) + "/api/embed")
                .post(body.toString().toRequestBody(JSON)).build()
            LocalLlmClient.http.newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val arr = JSONObject(r.body?.string() ?: return null).optJSONArray("embeddings") ?: return null
                (0 until arr.length()).map { i -> val a = arr.getJSONArray(i); FloatArray(a.length()) { j -> a.getDouble(j).toFloat() } }
            }
        } catch (e: Exception) { null }
    }

    private fun cos(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var d = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else d / (sqrt(na) * sqrt(nb))
    }

    /** Indexes (into [facts]) of the [k] facts closest in meaning to [query], or null to use them all. */
    fun relevant(ctx: Context, facts: List<String>, query: String, k: Int = 8): List<Int>? {
        if (!Guard.semanticOn(ctx) || query.isBlank() || facts.size <= k) return null
        val cache = sp(ctx)
        val vecs = arrayOfNulls<FloatArray>(facts.size)
        val missing = ArrayList<Int>()
        for ((i, f) in facts.withIndex()) {
            val c = cache.getString("v" + f.hashCode(), null)
            if (c != null) vecs[i] = unpack(c) else missing.add(i)
        }
        val toEmbed = missing.map { facts[it] } + query
        val got = embed(ctx, toEmbed) ?: return null
        if (got.size != toEmbed.size) return null
        val ed = cache.edit()
        for ((n, i) in missing.withIndex()) { vecs[i] = got[n]; ed.putString("v" + facts[i].hashCode(), pack(got[n])) }
        ed.apply()
        val q = got.last()
        return facts.indices.sortedByDescending { cos(vecs[it]!!, q) }.take(k)
    }
}
