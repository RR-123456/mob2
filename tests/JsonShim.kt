package org.json
class JSONException(m: String) : Exception(m)
/** Test-only: flat objects with string / number / bool values. */
class JSONObject(src: String) {
    private val m = HashMap<String, String>()
    init {
        val s = src.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) throw JSONException("not an object")
        var i = 1
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun str(): String { if (s[i] != '"') throw JSONException("expected string at $i"); i++; val sb = StringBuilder()
            while (i < s.length && s[i] != '"') { if (s[i] == '\\') { i++; sb.append(when (s[i]) { 'n' -> '\n'; else -> s[i] }) } else sb.append(s[i]); i++ }
            if (i >= s.length) throw JSONException("unterminated"); i++; return sb.toString() }
        ws()
        while (i < s.length - 1) {
            ws(); if (s[i] == '}') break
            val k = str(); ws(); if (s[i] != ':') throw JSONException("expected :"); i++; ws()
            val v = if (s[i] == '"') str() else { val st = i; while (i < s.length && s[i] != ',' && s[i] != '}') i++; s.substring(st, i).trim() }
            m[k] = v; ws(); if (i < s.length && s[i] == ',') i++
        }
    }
    fun optString(k: String): String = m[k] ?: ""
    fun optInt(k: String, d: Int): Int = m[k]?.toIntOrNull() ?: d
}
