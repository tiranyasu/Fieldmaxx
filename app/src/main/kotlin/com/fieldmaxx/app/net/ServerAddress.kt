package com.fieldmaxx.app.net

import android.content.Context
import com.fieldmaxx.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server's base URL: the address last used on the login screen, else [default]. Kept in plain preferences (it
 * isn't secret) so the app keeps talking to the server it signed in to.
 */
class ServerAddress(context: Context, private val default: String = BuildConfig.SERVER_URL) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY, null) ?: default

    fun set(url: String) {
        prefs.edit().putString(KEY, url).apply()
    }

    private companion object {
        const val KEY = "url"
    }
}

/** `fieldmaxx.example.com/` → `https://fieldmaxx.example.com`; null when the text can't be an http(s) address. */
fun normalizeServerUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val url = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
    return url.toString().trimEnd('/')
}
