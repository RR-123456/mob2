package com.pragon.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * AUTO engine: Gemini (cloud) and the phone / Ollama model working together.
 *
 *  - Chat: Gemini first when it may be used (a key is set, Privacy Shield is OFF and there is internet), the local model as the
 *    fallback. With "AUTO tries the phone model first" it is the other way round. If one fails, the other answers.
 *  - Screen questions (photos, text, numbers): Gemini first when allowed (it sees pictures best), else the local vision model.
 *  - Privacy Shield ON (or no key, or no internet) means local only. Plain phone commands never touch either AI.
 */
object AutoEngine {

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }   // can't tell: let the request itself find out

    fun cloudOk(ctx: Context): Boolean =
        Prefs.effectiveAiKey(ctx).isNotBlank() && !Guard.privacyOn(ctx) && online(ctx)

    /** "cloud" / "local", in the order to try them for a chat message. */
    fun chatOrder(ctx: Context): List<String> = when {
        !cloudOk(ctx) -> listOf("local")
        Prefs.autoLocalFirst(ctx) -> listOf("local", "cloud")
        else -> listOf("cloud", "local")
    }

    /** "cloud" / "local", in the order to try them for a question about the screen. */
    fun visionOrder(ctx: Context): List<String> = if (cloudOk(ctx)) listOf("cloud", "local") else listOf("local")
}
