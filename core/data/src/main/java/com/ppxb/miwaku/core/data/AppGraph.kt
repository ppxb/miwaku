package com.ppxb.miwaku.core.data

import com.ppxb.miwaku.core.network.ApiClient

object AppGraph {
    private val apiClient by lazy { ApiClient() }
    val comics: ComicRepository by lazy { ComicRepository(apiClient) }
}
