package com.pragon.mobile

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * "Read my notifications" / "summarize WhatsApp". Needs YOU to switch on Notification access for Pragon in Android settings AND
 * "Let Pragon read my notifications" in Pragon's Security settings. Notifications are kept only in memory (gone when the app
 * is closed), never written to disk, and go to an AI engine ONLY for "summarize", and only if you also allowed that.
 * Secret/lock-screen-hidden notifications, ongoing ones (music, downloads) and Pragon's own are ignored; one-time codes are hidden.
 */
class PragonNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { Notifs.service = this; seed() }
    override fun onListenerDisconnected() { if (Notifs.service === this) Notifs.service = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { Notifs.add(this, it) } }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let { Notifs.remove(it.key) } }
    private fun seed() { try { activeNotifications?.forEach { Notifs.add(this, it) } } catch (e: Exception) { } }
    /** Called when the user turns "read my notifications" on while Android already has the listener connected. */
    fun reseed() = seed()
}

object Notifs {
    class Note(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long)

    @Volatile var service: PragonNotificationService? = null
    private val list = ArrayList<Note>()
    private const val MAX = 60

    private fun hideCodes(s: String) = TextRules.hideCodes(s)

    fun listenerOn(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, PragonNotificationService::class.java).flattenToString()
        val short = ComponentName(ctx, PragonNotificationService::class.java).flattenToShortString()
        return flat.split(":").any { it == me || it == short }
    }

    @Synchronized fun add(ctx: Context, sbn: StatusBarNotification) {
        if (!Guard.notifOn(ctx)) return
        val n = sbn.notification ?: return
        if (sbn.packageName == ctx.packageName) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.visibility == Notification.VISIBILITY_SECRET) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (x: Exception) { sbn.packageName }
        list.removeAll { it.key == sbn.key }
        list.add(Note(sbn.key, sbn.packageName, app, title.take(80), text.take(240), sbn.postTime))
        while (list.size > MAX) list.removeAt(0)
    }

    @Synchronized fun remove(key: String) { list.removeAll { it.key == key } }
    @Synchronized fun clear() { list.clear() }

    @Synchronized private fun pick(app: String): List<Note> {
        val q = app.lowercase().replace(Regex("[^a-z0-9]"), "")
        val all = list.sortedByDescending { it.time }
        if (q.isEmpty()) return all
        return all.filter { val a = it.app.lowercase().replace(Regex("[^a-z0-9]"), ""); a.contains(q) || q.contains(a) || Fuzzy.close(a, q) }
    }

    private fun gate(ctx: Context): CommandExecutor.Res? {
        if (!Guard.notifOn(ctx))
            return CommandExecutor.Res(false, "I'm not allowed to read your notifications. Turn on \"Let Pragon read my notifications\" in Settings > Security first.", label = "Notifications")
        if (!listenerOn(ctx))
            return CommandExecutor.Res(false, "Android hasn't given Pragon notification access yet. Open Settings > Security > NOTIFICATION ACCESS and switch Pragon on.", label = "Notifications")
        return null
    }

    /** "read my notifications": spoken by the phone's own voice, nothing leaves the phone. */
    fun read(ctx: Context, app: String): CommandExecutor.Res {
        gate(ctx)?.let { return it }
        val items = pick(app)
        if (items.isEmpty()) return CommandExecutor.Res(true, if (app.isBlank()) "You have no new notifications, ${Prefs.callMe(ctx)}." else "No notifications from $app.", label = "Notifications")
        val top = items.take(6)
        val sb = StringBuilder("You have ${items.size} notification${if (items.size == 1) "" else "s"}")
        if (items.size > top.size) sb.append(", here are the latest ${top.size}")
        sb.append(". ")
        for (n in top) {
            sb.append(n.app).append(": ")
            if (n.title.isNotEmpty()) sb.append(n.title).append(". ")
            if (n.text.isNotEmpty()) sb.append(hideCodes(n.text).take(140)).append(". ")
        }
        return CommandExecutor.Res(true, sb.toString().trim(), label = "Notifications")
    }

    private const val SYSTEM =
        "You summarise a phone user's notifications. Reply in plain spoken language, at most four short sentences, no lists or markdown. " +
            "Group by app or person, say what needs attention first (messages from people, calls, deadlines), and skip promotions unless nothing else is there."

    /** "summarize my notifications": the AI engine sees the (code-hidden) text, but only if you allowed that. */
    fun summarize(ctx: Context, app: String): CommandExecutor.Res {
        gate(ctx)?.let { return it }
        if (!Guard.notifAiOn(ctx))
            return CommandExecutor.Res(false, "To summarise I would send your notification text to your AI engine. Turn on \"Allow summaries by my AI\" in Settings > Security, or say read my notifications.", label = "Notifications")
        Guard.engineProblem(ctx)?.let { return CommandExecutor.Res(false, it, label = "Notifications") }
        val items = pick(app).take(25)
        if (items.isEmpty()) return CommandExecutor.Res(true, if (app.isBlank()) "You have no new notifications." else "No notifications from $app.", label = "Notifications")
        val body = items.joinToString("\n") { "- ${it.app}: ${it.title}. ${hideCodes(it.text)}" }
        val (ans, problem) = ScreenAI.complete(ctx, SYSTEM, "NOTIFICATIONS:\n$body\n\nSummarise them.", null, maxTokens = 260)
        return if (ans != null) CommandExecutor.Res(true, ans, label = "Notifications") else CommandExecutor.Res(false, problem ?: "The AI didn't answer.", label = "Notifications")
    }
}
