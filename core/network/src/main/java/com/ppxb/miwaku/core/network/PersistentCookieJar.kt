package com.ppxb.miwaku.core.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Minimal persistent cookie store. No WebView/CookieManager: cookies are kept per host in
 * SharedPreferences and are dropped once expired. Session cookies are kept across restarts,
 * which is what the JM login (AVS) needs.
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val host = url.host
            val now = System.currentTimeMillis()
            val stored =
                read(host)
                    .mapNotNull { line -> Cookie.parse(url, line)?.takeIf { it.expiresAt > now }?.toString() }
                    .toMutableList()
            for (cookie in cookies) {
                stored.removeAll { line ->
                    val existing = Cookie.parse(url, line)
                    existing != null && existing.name == cookie.name && existing.path == cookie.path
                }
                if (cookie.expiresAt > now) {
                    stored += cookie.toString()
                }
            }
            prefs.edit().putString(host, stored.joinToString(SEPARATOR)).apply()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val host = url.host
            val lines = read(host)
            if (lines.isEmpty()) return emptyList()
            val now = System.currentTimeMillis()
            val valid = ArrayList<String>(lines.size)
            val cookies = ArrayList<Cookie>(lines.size)
            for (line in lines) {
                val cookie = Cookie.parse(url, line) ?: continue
                if (cookie.expiresAt <= now) continue
                if (!cookie.matches(url)) continue
                valid += line
                cookies += cookie
            }
            if (valid.size != lines.size) {
                prefs.edit().putString(host, valid.joinToString(SEPARATOR)).apply()
            }
            return cookies
        }
    }

    fun clear() {
        synchronized(lock) { prefs.edit().clear().apply() }
    }

    private fun read(host: String): List<String> =
        prefs
            .getString(host, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private companion object {
        const val PREFS_NAME = "api_cookies"

        /** Newlines never appear inside a serialized cookie, so the separator is unambiguous. */
        const val SEPARATOR = "\n"
    }
}
