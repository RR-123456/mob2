import com.pragon.mobile.*

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") { if (!ok) { fails++; println("FAIL  $name $extra") } else println("ok    $name") }

fun main() {
    // ---- 1. reading messy AI replies
    val replies = mapOf(
        """{"action":"tap","index":3}""" to Pair("tap", 3),
        "```json\n{\"action\": \"tap\", \"index\": \"7\"}\n```" to Pair("tap", 7),
        "Sure! Here you go: {\"action\":\"scroll\",\"dir\":\"Down\"} hope that helps" to Pair("scroll", -1),
        """{"action":"Open App","text":"Instagram"}""" to Pair("open_app", -1),
        """{"action":"type","index":2,"text":"hello"}""" to Pair("type", 2),
    )
    for ((r, e) in replies) { val p = AgentRules.parse(r); check("parse ${r.take(34).replace('\n',' ')}", p != null && p.action == e.first && p.index == e.second, p?.let { "${it.action}/${it.index}" } ?: "null") }
    check("parse garbage -> null", AgentRules.parse("I cannot do that") == null)
    check("parse empty json -> null", AgentRules.parse("{}") == null)
    check("parse truncated -> null", AgentRules.parse("{\"action\":\"tap\",\"ind") == null)

    // ---- 2. what needs a yes
    for (l in listOf("Send", "Post", "Place order", "Pay now", "Delete chat", "Log out", "Subscribe", "Share", "Call", "Confirm", "Submit")) check("confirm: $l", AgentRules.needsConfirm(l))
    for (l in listOf("Messages", "Reels", "Search", "Bookmarks", "Home", "Compose", "Message Ravi", "Profile", "Sender name", "Postcard", "Ordering info")) check("no confirm: $l", !AgentRules.needsConfirm(l))
    check("money app: com.phonepe.app", AgentRules.isMoneyApp("com.phonepe.app")); check("money app: bank", AgentRules.isMoneyApp("com.sbi.bank.mobile"))
    check("not money: instagram", !AgentRules.isMoneyApp("com.instagram.android")); check("not money: whatsapp", !AgentRules.isMoneyApp("com.whatsapp"))

    // ---- 3. yes / no
    for (y in listOf("yes", "yeah", "go ahead", "do it", "ok", "yes please")) check("yes: $y", AgentRules.yesNo(y) == true)
    for (n in listOf("no", "nope", "stop", "cancel", "never mind", "don't")) check("no: $n", AgentRules.yesNo(n) == false)
    for (o in listOf("maybe", "open youtube", "what is that", "yesterday")) check("neither: $o", AgentRules.yesNo(o) == null)

    // ---- 4. answer cache rules
    val cache = listOf("how does photosynthesis work", "explain how a transistor works", "what is the capital of france", "why is the sky blue")
    for (q in cache) check("cache ok: $q", TextRules.cacheable(q))
    val nocache = listOf("what is the weather", "what time is it", "what is my name", "and why is that", "hi", "tell me the news today", "what did you say again", "why", "why is that", "is it going to rain tomorrow")
    for (q in nocache) check("no cache: $q", !TextRules.cacheable(q))

    // ---- 5. codes hidden
    check("otp hidden", TextRules.hideCodes("Your OTP is 482913. Do not share") == "Your OTP is [code hidden]. Do not share")
    check("pin hidden", TextRules.hideCodes("Use PIN 4821 to log in").contains("[code hidden]"))
    check("year kept", TextRules.hideCodes("Meeting moved to 2025 budget review") == "Meeting moved to 2025 budget review")
    check("shipping kept", TextRules.hideCodes("Your shipping update 123456 arrived") == "Your shipping update 123456 arrived")
    check("order no. kept", TextRules.hideCodes("Order 556677 shipped") == "Order 556677 shipped")

    // ---- 6. fuzzy
    val apps = listOf("Instagram", "WhatsApp", "Spotify", "YouTube", "Chrome", "Google Maps", "Calculator", "Calendar", "Camera", "Clock", "Gmail", "Files", "Photos", "Phone", "Play Store")
    val expect = mapOf("instagran" to "Instagram", "whats up" to "WhatsApp", "spotifi" to "Spotify", "you tube" to "YouTube", "chrom" to "Chrome", "gogle maps" to "Google Maps",
        "calender" to "Calendar", "calculater" to "Calculator", "playstore" to "Play Store")
    for ((q, e) in expect) { val got = Fuzzy.best(q, apps) { it }; check("fuzzy $q -> $e", got == e, "got $got") }
    for (q in listOf("weather", "recording", "tell me a story", "volume", "settings", "music", "sam", "cam", "pho")) {
        val got = Fuzzy.best(q, apps) { it }; check("fuzzy no false hit: $q", got == null, "got $got")
    }
    check("fuzzy close: subscibe~subscribe", Fuzzy.close("subscibe", "subscribe")); check("fuzzy close: like!~share", !Fuzzy.close("like", "share"))

    // ---- 6b. commands accepted while a video plays (no wake word)
    for (q in listOf("volume up", "volume down", "louder", "quieter", "pause", "resume", "mute", "next", "skip", "stop", "pause the video", "pragon volume up", "turn it up", "next song"))
        check("media-safe: $q", TextRules.mediaSafeCommand(q))
    for (q in listOf("and then he said stop right there", "i need to turn the volume of the speech", "play the next level now", "open instagram", "call mom", "he said pause for a second", "the volume of sales is up", "next time we meet"))
        check("not media-safe: $q", !TextRules.mediaSafeCommand(q))

    // ---- 7. simulated agent run: "open instagram, go to messages, message ravi hello"
    //      a fake phone: screens + an AI that answers like a good model; checks the control flow & the safety stops
    class Screen(val pkg: String, val items: List<Pair<String, String>>)  // kind,label
    val screens = mapOf(
        "home" to Screen("launcher", listOf("button" to "Instagram", "button" to "Chrome")),
        "feed" to Screen("com.instagram.android", listOf("button" to "Home", "button" to "Reels", "button" to "Messages", "button" to "Profile")),
        "inbox" to Screen("com.instagram.android", listOf("textbox" to "Search", "button" to "Ravi", "button" to "Meena")),
        "chat" to Screen("com.instagram.android", listOf("textbox" to "Message...", "button" to "Send")),
    )
    var cur = "home"; var typed = ""; var sent = false; var pendingStep: AgentRules.Parsed? = null
    val script = ArrayDeque(listOf(
        """{"action":"open_app","text":"Instagram"}""", """{"action":"tap","index":3}""", """{"action":"tap","index":2}""",
        """{"action":"type","index":1,"text":"hello"}""", """{"action":"tap","index":2}""", """{"action":"done","text":"Sent hello to Ravi."}"""))
    val log = ArrayList<String>(); var steps = 0; var result = ""
    fun labelAt(i: Int) = screens[cur]!!.items.getOrNull(i - 1)?.second ?: ""
    loop@ while (steps < 14) {
        val reply = script.removeFirstOrNull() ?: break
        val st = AgentRules.parse(reply)!!
        if (AgentRules.isMoneyApp(screens[cur]!!.pkg)) { result = "refused money app"; break }
        if (st.action == "tap" && AgentRules.needsConfirm(labelAt(st.index))) {
            pendingStep = st; log.add("ASK: press ${labelAt(st.index)}?")
            // user says yes
            if (AgentRules.yesNo("yes") == true) { sent = true; log.add("pressed ${labelAt(st.index)}") }
            steps++; continue
        }
        when (st.action) {
            "open_app" -> cur = "feed"
            "tap" -> { val l = labelAt(st.index); log.add("tapped $l"); if (l == "Messages") cur = "inbox"; if (l == "Ravi") cur = "chat" }
            "type" -> { typed = st.text; log.add("typed $typed") }
            "done" -> { result = st.text; break@loop }
        }
        steps++
    }
    check("sim: finished with done", result == "Sent hello to Ravi.", result)
    check("sim: message typed", typed == "hello"); check("sim: asked before Send and then pressed it", log.any { it.startsWith("ASK: press Send") } && sent)
    check("sim: stayed under step limit", steps <= 14, "steps=$steps"); println("      log: $log")

    println(if (fails == 0) "\nALL MOCK CHECKS PASSED" else "\n$fails FAILED")
}
