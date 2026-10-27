package com.ppxb.miwaku.core.data

import java.security.MessageDigest

internal object Scramble {
    const val DEFAULT_SCRAMBLE_ID = 220_980
    private const val SCRAMBLE_268850 = 268_850L
    private const val SCRAMBLE_421926 = 421_926L

    fun scrapeScrambleId(html: String): Int? =
        Regex("""var\s+scramble_id\s*=\s*(\d+)""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    fun splitCount(scrambleId: Int, photoId: Long, filename: String): Int {
        if (photoId < scrambleId) return 0
        if (photoId < SCRAMBLE_268850) return 10

        val modulus = if (photoId < SCRAMBLE_421926) 10 else 8
        val lastDigit = md5Hex("$photoId$filename").last().digitToInt(16)
        return lastDigit % modulus * 2 + 2
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
