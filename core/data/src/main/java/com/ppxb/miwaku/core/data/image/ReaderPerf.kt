package com.ppxb.miwaku.core.data.image

import android.os.SystemClock
import android.util.Log

/**
 * Debug-only timing logs. Enable with:
 * adb shell setprop log.tag.ReaderPerf DEBUG
 */
object ReaderPerf {
    const val TAG = "ReaderPerf"

    val enabled: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    inline fun log(message: () -> String) {
        if (enabled) {
            Log.d(TAG, message())
        }
    }

    fun mark(): Long = SystemClock.elapsedRealtime()
}
