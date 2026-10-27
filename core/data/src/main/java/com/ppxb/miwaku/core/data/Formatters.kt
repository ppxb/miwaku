package com.ppxb.miwaku.core.data

import org.json.JSONArray

internal const val UNKNOWN_AUTHOR = "N/A"
internal const val SINGLE_VOLUME = "单行本"

/**
 * The API returns `author` either as a plain name or as a JSON array string. Normalize both
 * shapes so every grid/list shows one readable line, and use N/A when nothing is available.
 */
internal fun String.displayAuthor(): String {
    val raw = trim()
    if (raw.isEmpty() || raw == "null" || raw == "[]") return UNKNOWN_AUTHOR

    val names =
        runCatching { JSONArray(raw) }.getOrNull()?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index)
                    .unescapeHtml()
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "null" }
            }
        }
    val text = if (names != null) names.joinToString(", ") else unescapeHtml().trim()
    return text.ifBlank { UNKNOWN_AUTHOR }
}

/** Series entries occasionally have an empty name; fall back to a readable chapter label. */
internal fun String.chapterTitle(sort: Int): String =
    unescapeHtml().trim().ifBlank { "第${sort}话" }

internal fun String.unescapeHtml(): String =
    replace("&amp;", "&")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
