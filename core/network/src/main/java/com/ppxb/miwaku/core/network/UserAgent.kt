package com.ppxb.miwaku.core.network

import java.util.UUID

/**
 * Derives a stable, install-specific mobile user agent from a persisted seed.
 *
 * A single hard-coded UA would give every install the same fingerprint; a UA that changes per
 * request would be an even stronger bot signal. The seed is generated once on first launch and
 * stored by [UserAgentStore], so one install always claims the same plausible device.
 */
object UserAgent {
    private val ANDROID_VERSIONS = listOf("10", "11", "12", "13", "14", "15")
    private val CHROME_VERSIONS =
        listOf(
            "114.0.5735.196",
            "116.0.5845.172",
            "118.0.5993.111",
            "119.0.6045.194",
            "120.0.6099.230",
            "121.0.6167.178",
            "122.0.6261.119",
            "123.0.6312.118",
            "124.0.6367.179",
            "125.0.6422.165",
        )
    private val BUILD_CODES =
        listOf("TQ1A.230305.002", "UP1A.231005.007", "UQ1A.240205.002", "AP1A.240405.002")

    /** New seed for a fresh install. */
    fun randomSeed(): String = UUID.randomUUID().toString().replace("-", "")

    /** Empty seeds keep the known-good constant instead of inventing a new identity per call. */
    fun fromSeed(seed: String): String {
        if (seed.isBlank()) return ApiConfig.USER_AGENT
        val device = deviceId(seed)
        val android = pick(ANDROID_VERSIONS, seed, salt = 1)
        val build = pick(BUILD_CODES, seed, salt = 2)
        val chrome = pick(CHROME_VERSIONS, seed, salt = 3)
        return "Mozilla/5.0 (Linux; Android $android; $device Build/$build; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$chrome Mobile Safari/537.36"
    }

    private fun pick(options: List<String>, seed: String, salt: Int): String =
        options[(hash(seed, salt) and Int.MAX_VALUE) % options.size]

    /** Nine lowercase alphanumerics, the shape the real clients claim. */
    private fun deviceId(seed: String): String {
        var state = hash(seed, salt = 7).toLong() shl 32 or (hash(seed, salt = 11).toLong() and 0xFFFF_FFFFL)
        return buildString(9) {
            repeat(9) {
                state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
                append(ALPHABET[((state ushr 33) % ALPHABET.length).toInt()])
            }
        }
    }

    private fun hash(seed: String, salt: Int): Int {
        var value = 0x811C9DC5.toInt() xor salt
        for (char in seed) {
            value = (value xor char.code) * 16_777_619
        }
        return value
    }

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
}
