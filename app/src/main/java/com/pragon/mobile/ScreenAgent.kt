package com.pragon.mobile

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen agent: "agent: open Instagram, go to messages, message Ravi hello".
 * Pragon looks at the screen (the accessibility tree, plus a screenshot when a cloud model is allowed), asks your AI engine for ONE next
 * action, does it, looks again, and repeats until the job is done, it needs you, or it hits the step limit.
 *
 * Safety (all enforced here, not left to the model):
 *   - needs "Let Pragon read my screen" AND "Allow the screen agent" (Settings > Security); only YOU can start it, never the AI or the PC link;
 *   - it asks "yes or no?" before pressing anything that sends, pays, buys, deletes, posts, submits or signs out;
 *   - it stops when a password box is on screen and never types into one; it refuses payment / banking apps;
 *   - at most [MAX_STEPS] actions; say "stop" or tap the bubble at any time.
 */
object ScreenAgent {

    private const val MAX_STEPS = 14

    private class Item(val idx: Int, val kind: String, val label: String, val r: Rect) {
        fun line() = "[$idx] $kind \"${label.take(60)}\""
    }
    private class Step(val action: String, val index: Int, val text: String, val dir: String)

    @Volatile var running = false
        private set
    private val cancel = AtomicBoolean(false)

    private var task = ""
    private val log = ArrayList<String>()
    private var steps = 0
    private var pending: Step? = null
    private var pendingLabel = ""
    private var items: List<Item> = emptyList()

    fun waiting(): Boolean = pending != null
    fun cancelNow() { cancel.set(true) }

    private const val SYSTEM =
        "You operate an Android phone for the user, one action at a time. You are shown the TASK, what you already did, and the " +
            "CURRENT SCREEN as a numbered list of elements (and sometimes a screenshot). Reply with ONE JSON object and nothing else:\n" +
            "{\"action\":\"tap\",\"index\":N}  press element N\n" +
            "{\"action\":\"type\",\"index\":N,\"text\":\"...\"}  put the cursor in text box N (index optional if one is already focused) and type\n" +
            "{\"action\":\"scroll\",\"dir\":\"down\"|\"up\"}\n" +
            "{\"action\":\"back\"}  {\"action\":\"home\"}  {\"action\":\"wait\"}  (wait = the screen is still loading)\n" +
            "{\"action\":\"open_app\",\"text\":\"Instagram\"}\n" +
            "{\"action\":\"done\",\"text\":\"one short spoken sentence saying what was done\"}\n" +
            "{\"action\":\"ask_user\",\"text\":\"one short question\"}\n" +
            "Rules: use only indexes from the list; never type passwords, OTPs or card numbers; do not repeat an action that did not change the screen " +
            "(try another way or ask_user); when the task is finished answer done; if the task is unclear or impossible answer ask_user."

    // ── entry points ─────────────────────────────────────────────────────

    /** Starts a new job. Blocking (can take a minute): call from a background thread. */
    fun start(ctx: Context, taskText: String): CommandExecutor.Res {
        if (running) return CommandExecutor.Res(false, "I'm already working on a task. Say stop first.", label = "Agent")
        if (!Guard.screenReadingOn(ctx))
            return CommandExecutor.Res(false, "The screen agent needs \"Let Pragon read my screen\" (Settings > Security) first.", label = "Agent")
        if (!Guard.agentOn(ctx))
            return CommandExecutor.Res(false, "Turn on \"Allow the screen agent\" in Settings > Security first.", label = "Agent")
        if (PragonAccessibilityService.instance == null)
            return CommandExecutor.Res(false, "The screen agent needs the Pragon accessibility service turned on.", label = "Agent")
        Guard.engineProblem(ctx)?.let { return CommandExecutor.Res(false, it, label = "Agent") }
        task = taskText.trim(); log.clear(); steps = 0; pending = null; cancel.set(false)
        return drive(ctx)
    }

    /** The user said yes / no to "I'm about to press Send. Continue?". */
    fun answer(ctx: Context, yes: Boolean): CommandExecutor.Res {
        val p = pending ?: return CommandExecutor.Res(false, "I wasn't waiting for an answer.", label = "Agent")
        pending = null
        if (!yes) { log.add("user refused: ${pendingLabel}"); return CommandExecutor.Res(true, "Okay, I stopped before pressing $pendingLabel.", label = "Agent") }
        cancel.set(false)
        val svc = PragonAccessibilityService.instance ?: return CommandExecutor.Res(false, "The accessibility service turned off.", label = "Agent")
        items = observe(svc, ctx.packageName)
        val fresh = items.firstOrNull { it.label.take(40) == pendingLabel }
            ?: return CommandExecutor.Res(false, "The screen changed and I can't see \"$pendingLabel\" any more, so I didn't press it. Tell me the task again.", label = "Agent")
        val err = perform(ctx, Step(p.action, fresh.idx, p.text, p.dir), confirmed = true)
        if (err != null) return CommandExecutor.Res(false, err, label = "Agent")
        steps++
        return drive(ctx)
    }

    fun drop() { pending = null }

    fun stop(): CommandExecutor.Res {
        cancel.set(true); pending = null
        return CommandExecutor.Res(true, if (running) "Stopping the task." else "No task was running.", label = "Agent")
    }

    // ── the loop ─────────────────────────────────────────────────────────

    private fun say(t: String) { FloatService.caption(t) }

    private fun drive(ctx: Context): CommandExecutor.Res {
        running = true
        try {
            while (steps < MAX_STEPS) {
                if (cancel.get()) return CommandExecutor.Res(true, "Stopped.", label = "Agent")
                val svc = PragonAccessibilityService.instance
                    ?: return CommandExecutor.Res(false, "The accessibility service turned off, so I stopped.", label = "Agent")
                if (svc.passwordVisible())
                    return CommandExecutor.Res(true, "A password box is on the screen. I'll leave that part to you; tell me when to continue.", label = "Agent")
                val pkg = svc.foregroundPackage().orEmpty()
                if (pkg.isNotEmpty() && AgentRules.isMoneyApp(pkg))
                    return CommandExecutor.Res(false, "That looks like a payment or banking app, so I won't operate it.", label = "Agent")

                items = observe(svc, ctx.packageName)
                val user = buildString {
                    append("TASK: ").append(task).append("\n\n")
                    append("DONE SO FAR:\n").append(if (log.isEmpty()) "(nothing yet)" else log.takeLast(8).joinToString("\n")).append("\n\n")
                    append("CURRENT SCREEN (app: ").append(pkg.ifEmpty { "unknown" }).append("):\n")
                    append(if (items.isEmpty()) "(no readable elements: it may be loading, a video or a protected screen)" else items.joinToString("\n") { it.line() })
                }
                // pictures only go to the cloud model, and only when it is allowed
                val img = if (AutoEngine.cloudOk(ctx) || Prefs.engine(ctx) == "gemini") ScreenAI.screenshotB64(ctx) else null
                say("Thinking... (step ${steps + 1})")
                val (reply, problem) = ScreenAI.complete(ctx, SYSTEM, user, img, maxTokens = 250, json = true)
                if (cancel.get()) return CommandExecutor.Res(true, "Stopped.", label = "Agent")
                if (reply == null) return CommandExecutor.Res(false, problem ?: "The AI didn't answer.", label = "Agent")
                val step = parse(reply)
                if (step == null) { log.add("(the AI answered something I couldn't read)"); steps++; continue }

                when (step.action) {
                    "done" -> return CommandExecutor.Res(true, step.text.ifBlank { "Done." }, label = "Agent")
                    "ask_user" -> return CommandExecutor.Res(true, step.text.ifBlank { "I need your help with this step." }, label = "Agent")
                }
                val danger = dangerous(step)
                if (danger != null) {
                    pending = step; pendingLabel = danger
                    return CommandExecutor.Res(true, "I'm about to press \"$danger\". Say yes to go ahead or no to stop.", label = "Agent")
                }
                val err = perform(ctx, step, confirmed = false)
                if (err != null) return CommandExecutor.Res(false, err, label = "Agent")
                steps++
                try { Thread.sleep(900) } catch (e: InterruptedException) { }
            }
            return CommandExecutor.Res(true, "I did $MAX_STEPS steps and stopped. Ask me to continue if there is more to do.", label = "Agent")
        } finally {
            running = false
        }
    }

    // ── reading the screen ───────────────────────────────────────────────

    private fun observe(svc: PragonAccessibilityService, ownPkg: String): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (n in svc.visibleNodes()) {
            if (n.packageName?.toString() == ownPkg || n.isPassword) continue
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.isEmpty || r.width() < 8 || r.height() < 8) continue
            val text = (n.text?.toString() ?: "").trim()
            val desc = (n.contentDescription?.toString() ?: "").trim()
            val hint = (n.hintText?.toString() ?: "").trim()
            val id = (n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' ') ?: "").trim()
            val label = text.ifEmpty { desc }.ifEmpty { hint }.ifEmpty { if (n.isClickable || n.isEditable) id else "" }
            val kind = when {
                n.isEditable -> "textbox"
                n.isClickable && n.isCheckable -> if (n.isChecked) "switch(on)" else "switch(off)"
                n.isClickable -> "button"
                n.isScrollable -> "list"
                label.isNotEmpty() -> "text"
                else -> continue
            }
            if (label.isEmpty() && kind != "list" && kind != "textbox") continue
            if (!seen.add("$kind|$label|${r.top / 20}")) continue
            out.add(Item(0, kind, label.ifEmpty { kind }, r))
            if (out.size >= 70) break
        }
        return out.sortedWith(compareBy({ it.r.top / 30 }, { it.r.left })).mapIndexed { i, it -> Item(i + 1, it.kind, it.label, it.r) }
    }

    // ── doing one step ───────────────────────────────────────────────────

    private fun parse(reply: String): Step? = AgentRules.parse(reply)?.let { Step(it.action, it.index, it.text, it.dir) }

    /** The label of what is about to be pressed, if pressing it needs a yes from the user. */
    private fun dangerous(s: Step): String? {
        if (s.action == "tap") {
            val it = items.firstOrNull { x -> x.idx == s.index } ?: return null
            return if (AgentRules.needsConfirm(it.label)) it.label.take(40) else null
        }
        if (s.action == "type" && s.text.isNotBlank()) {
            // typing is fine; sending is a separate (checked) tap
            return null
        }
        return null
    }

    /** Runs [s]. Returns null on success or a spoken problem. */
    private fun perform(ctx: Context, s: Step, confirmed: Boolean): String? {
        val svc = PragonAccessibilityService.instance ?: return "The accessibility service turned off."
        when (s.action) {
            "tap" -> {
                val it = items.firstOrNull { x -> x.idx == s.index } ?: return "I lost track of the screen; please say the task again."
                log.add("tapped ${it.label.take(40)}"); say("Tap: ${it.label.take(40)}")
                if (!svc.tapAt(it.r.centerX(), it.r.centerY())) return "The phone refused the tap on ${it.label.take(40)}."
            }
            "type" -> {
                if (s.text.isBlank()) return null
                if (svc.passwordVisible()) return "A password box is on screen, so I won't type."
                val it = items.firstOrNull { x -> x.idx == s.index }
                if (it != null) { svc.tapAt(it.r.centerX(), it.r.centerY()); try { Thread.sleep(450) } catch (e: InterruptedException) { } }
                log.add("typed \"${s.text.take(30)}\""); say("Typing...")
                if (!svc.typeText(s.text) && svc.focusTextBox() == null) return "I couldn't find a text box to type into."
                if (!svc.hasFocusedTextBox()) svc.typeText(s.text)
            }
            "scroll" -> { log.add("scrolled ${s.dir}"); say("Scrolling ${s.dir}"); svc.swipeDir(if (s.dir == "up") "down" else "up") }
            "back" -> { log.add("pressed back"); svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            "home" -> { log.add("went home"); svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
            "wait" -> { log.add("waited"); try { Thread.sleep(1500) } catch (e: InterruptedException) { } }
            "open_app" -> {
                val name = s.text.trim()
                if (name.isEmpty()) return "No app name was given."
                log.add("opened $name"); say("Opening $name")
                val r = CommandExecutor.run(ctx, JSONObject().put("action", "open_app").put("value", name), fromStandalone = false, user = true)
                if (!r.ok) return r.msg
                try { Thread.sleep(1500) } catch (e: InterruptedException) { }
            }
            else -> { log.add("(unknown action ${s.action})") }
        }
        return null
    }
}
