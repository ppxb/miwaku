package com.ppxb.miwaku.core.network

/**
 * Everything the transport needs to survive a restart: both pools, their health and the current
 * selection. Keeping them in one document prevents "API switched, image host stale" on a cold start.
 */
data class EndpointState(
    val version: Int = SCHEMA_VERSION,
    val probedAt: Long = 0L,
    val apiVersion: String = "",
    val api: List<EndpointHealth> = emptyList(),
    val image: List<EndpointHealth> = emptyList(),
    val chosenApi: String? = null,
    val chosenImage: String? = null,
) {
    /** Null while any part of the selection is missing, which forces a fresh `/setting` probe. */
    fun toSnapshot(): SettingSnapshot? {
        val api = chosenApi.normalizedOrNull() ?: return null
        val image = chosenImage.normalizedOrNull() ?: return null
        val version = apiVersion.trim().takeIf { it.isNotEmpty() } ?: return null
        return SettingSnapshot(api, version, image)
    }

    /** Healthy entries first, then the least-failed, then the fastest. Cooling entries stay last. */
    fun rankedApi(now: Long): List<EndpointHealth> = api.ranked(now)

    fun rankedImage(now: Long): List<EndpointHealth> = image.ranked(now)

    private fun List<EndpointHealth>.ranked(now: Long): List<EndpointHealth> =
        sortedWith(compareBy({ it.isCoolingDown(now) }, { it.failures }, { it.latencyMs }))

    private fun String?.normalizedOrNull(): String? = this?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_API_POOL = 12
        const val MAX_IMAGE_POOL = 6
    }
}
