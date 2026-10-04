package com.pragon.mobile

/** Forgiving name matching for things speech recognizers mishear ("instagran", "whats up", "spotifi", "you tube"). */
object Fuzzy {

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Letters that sound alike are folded together before comparing (ph/f, c/k/q, z/s, double letters, silent h). */
    fun phonetic(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        .replace("ph", "f").replace("ck", "k").replace("q", "k").replace("c", "k").replace("z", "s").replace("x", "ks")
        .replace("w", "v").replace(Regex("(.)\\1+"), "$1").replace("h", "")

    /** How many typos we forgive: none for very short names, one for medium, two for long ones. */
    private fun allowed(len: Int) = when { len < 4 -> 0; len < 8 -> 1; else -> 2 }

    /** The candidate closest to [query] (both already normalised), or null when nothing is close enough. */
    fun <T> best(query: String, items: List<T>, name: (T) -> String): T? {
        val q = query.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (q.length < 4) return null
        val pq = phonetic(q)
        var bestItem: T? = null
        var bestScore = Int.MAX_VALUE
        for (it in items) {
            val n = name(it).lowercase().replace(Regex("[^a-z0-9]"), "")
            if (n.length < 3) continue
            val d = minOf(distance(q, n), distance(pq, phonetic(n)))
            if (d <= allowed(maxOf(q.length, n.length)) && d < bestScore) { bestScore = d; bestItem = it }
        }
        return bestItem
    }

    /** True when [a] and [b] (already squashed) are close enough to be the same word. */
    fun close(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return false
        return distance(a, b) <= allowed(maxOf(a.length, b.length)) || distance(phonetic(a), phonetic(b)) <= allowed(maxOf(a.length, b.length))
    }
}
