package com.pragon.mobile

import org.json.JSONObject

/** Plain-JVM rules (no Android classes) so they can be unit-tested. */

/** Screen-agent decisions. */
object AgentRules {
    class Parsed(val action: String, val index: Int, val text: String, val dir: String)

    val ACTIONS = setOf("tap", "type", "scroll", "back", "home", "wait", "open_app", "done", "ask_user")

    /** Reads the model's reply: tolerates ```json fences, text around the JSON, numbers sent as strings. Null if unusable. */
    fun parse(reply: String): Parsed? {
        val m = Regex("\\{[\\s\\S]*\\}").find(reply.replace("```json", "").replace("```", "")) ?: return null
        return try {
            val o = JSONObject(m.value)
            val a = o.optString("action").lowercase().trim().replace(' ', '_')
            if (a.isEmpty()) null else Parsed(a, o.optInt("index", -1), o.optString("text"), o.optString("dir").lowercase().trim())
        } catch (e: Exception) { null }
    }

    private val DANGER = Regex(
        "\\b(send|post|publish|pay|payment|buy|purchase|order|place order|checkout|check out|confirm|delete|remove|erase|clear all|reset|" +
            "submit|transfer|donate|subscribe|book|reserve|sign out|log out|logout|uninstall|install|unfollow|block|report|share|forward|call|dial)\\b",
        RegexOption.IGNORE_CASE
    )
    /** True when pressing something with this label must be confirmed by the user first. */
    fun needsConfirm(label: String): Boolean = DANGER.containsMatchIn(label)

    private val MONEY_APP = Regex("bank|wallet|upi|paytm|phonepe|gpay|googlepay|payments|finance|paypal|razorpay|walletapp", RegexOption.IGNORE_CASE)
    fun isMoneyApp(pkg: String): Boolean = MONEY_APP.containsMatchIn(pkg)

    /** "yes" / "no" answers to the agent's question. Null if it is neither. */
    fun yesNo(c: String): Boolean? {
        if (Regex("^(?:yes|yeah|yep|yup|sure|ok|okay|confirm|go ahead|do it|proceed|continue)(?: please)?$").matches(c)) return true
        if (Regex("^(?:no|nope|nah|stop|cancel|don't|do not|abort|wait|never mind|nevermind)(?: please)?$").matches(c)) return false
        return null
    }
}

/** What may be cached, and what must be hidden before text goes to an AI. */
object TextRules {
    private val VOLATILE = Regex(
        "\\b(today|tonight|tomorrow|yesterday|now|current|currently|latest|recent|news|weather|time|date|day|price|score|stock|rate|battery|" +
            "my|mine|me|i|we|our|you|your|this|that|these|those|it|them|here|there|again|more|another|next|previous|continue|also|then)\\b"
    )
    private val FOLLOWUP = Regex("^(and|but|so|then|also|ok|okay|yes|no|yeah|thanks|thank you)\\b")
    private val SHORT_FOLLOWUP = Regex("^(why|how about|what about|how so|really)\\b")   // a follow-up only when it is this short ("why?"), not "why is the sky blue"

    /** [n] = the question already lower-cased, letters/digits/spaces only. */
    fun cacheable(n: String): Boolean {
        val w = n.split(" ").filter { it.isNotEmpty() }.size
        if (w < 3 || w > 25) return false
        if (SHORT_FOLLOWUP.containsMatchIn(n) && w < 5) return false
        return !(FOLLOWUP.containsMatchIn(n) || VOLATILE.containsMatchIn(n))
    }

    private val MEDIA_SAFE = Regex(
        "^(?:pragon |paragon )?(?:please )?(?:volume (?:up|down|max|maximum|min|minimum|louder|lower)|turn (?:it|the volume) (?:up|down)|make it (?:louder|quieter|lower)|" +
            "(?:much )?(?:louder|quieter|lower|higher)|mute|unmute|pause|resume|play|next|previous|skip|(?:pause|stop|resume|play|skip)(?: the)? (?:video|music|song|track)|" +
            "(?:next|previous) (?:video|song|track)|stop)$"
    )
    /** While a video / song plays, only a WHOLE sentence like "volume up" or "pause" is accepted without the wake word (dialogue rarely is exactly that). */
    fun mediaSafeCommand(n: String): Boolean = MEDIA_SAFE.matches(n.trim())

    private val OTP = Regex("\\b\\d{4,8}\\b")
    private val CODE_WORDS = Regex("\\b(otp|code|passcode|verification|verify|pin|password|cvv)\\b", RegexOption.IGNORE_CASE)
    /** Hides 4-8 digit numbers in messages that talk about a code / OTP / PIN. */
    fun hideCodes(s: String): String = if (CODE_WORDS.containsMatchIn(s)) s.replace(OTP, "[code hidden]") else s
}
