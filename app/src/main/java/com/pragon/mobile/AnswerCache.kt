package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Remembers the AI's answers to general-knowledge questions so asking again is instant and free (no AI call, works offline).
 * Only stand-alone questions are cached: never anything about "me", "now", "today", the weather, a follow-up, or a phone action.
 * Stored on the phone only; switch it off or clear it in Settings.
 */
object AnswerCache {
    private const val MAX = 120
    private const val TTL = 14L * 24 * 3600 * 1000

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("pragon_answers", Context.MODE_PRIVATE)

    fun key(ctx: Context, q: String): String = Prefs.replyLang(ctx) + "|" + VoiceParser.norm(q).replace(Regex("^(?:hey |ok |okay )?(?:pragon |paragon )?(?:please )?"), "").trim()

    fun eligible(ctx: Context, raw: String, hasFiles: Boolean): Boolean {
        if (hasFiles || !Guard.cacheOn(ctx)) return false
        return TextRules.cacheable(VoiceParser.norm(raw))
    }

    @Synchronized fun get(ctx: Context, raw: String): String? {
        if (!eligible(ctx, raw, false)) return null
        return try {
            val o = JSONObject(sp(ctx).getString("all", "{}") ?: "{}")
            val e = o.optJSONObject(key(ctx, raw)) ?: return null
            if (System.currentTimeMillis() - e.optLong("t") > TTL) null else e.optString("a").ifBlank { null }
        } catch (e: Exception) { null }
    }

    @Synchronized fun put(ctx: Context, raw: String, answer: String) {
        if (!eligible(ctx, raw, false) || answer.length !in 20..700) return
        try {
            val o = JSONObject(sp(ctx).getString("all", "{}") ?: "{}")
            o.put(key(ctx, raw), JSONObject().put("a", answer).put("t", System.currentTimeMillis()))
            while (o.length() > MAX) {
                var oldest: String? = null; var ot = Long.MAX_VALUE
                for (k in o.keys()) { val t = o.optJSONObject(k)?.optLong("t") ?: 0L; if (t < ot) { ot = t; oldest = k } }
                oldest?.let { o.remove(it) } ?: break
            }
            sp(ctx).edit().putString("all", o.toString()).apply()
        } catch (e: Exception) { }
    }

    @Synchronized fun clear(ctx: Context) { sp(ctx).edit().clear().apply() }
    @Synchronized fun count(ctx: Context): Int = try { JSONObject(sp(ctx).getString("all", "{}") ?: "{}").length() } catch (e: Exception) { 0 }
}
