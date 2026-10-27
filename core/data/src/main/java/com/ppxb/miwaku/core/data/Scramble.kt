package com.ppxb.miwaku.core.data

import java.security.MessageDigest

internal object Scramble {
    private const val JM_SCRAMBLE_ID = 220_980
    private const val SCRAMBLE_268850 = 268_850L
    private const val SCRAMBLE_421926 = 421_926L

    fun splitCount(photoId: Long, filename: String): Int {
        if (photoId < JM_SCRAMBLE_ID) return 0
        if (photoId < SCRAMBLE_268850) return 10

        val lastChar = md5Hex("$photoId$filename").last().code
        return if (photoId > SCRAMBLE_421926) {
            lastChar % 8 * 2 + 2
        } else {
            lastChar % 10 * 2 + 2
        }
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
