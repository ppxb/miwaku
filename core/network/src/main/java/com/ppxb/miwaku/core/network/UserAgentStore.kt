package com.ppxb.miwaku.core.network

import android.content.Context

/** Keeps the UA seed for the lifetime of the install: generated once, never rotated. */
class UserAgentStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val userAgent: String by lazy { UserAgent.fromSeed(seed()) }

    private fun seed(): String =
        prefs.getString(KEY_SEED, null)?.takeIf { it.isNotBlank() }
            ?: UserAgent.randomSeed().also { prefs.edit().putString(KEY_SEED, it).apply() }

    private companion object {
        const val PREFS_NAME = "user_agent"
        const val KEY_SEED = "seed"
    }
}
