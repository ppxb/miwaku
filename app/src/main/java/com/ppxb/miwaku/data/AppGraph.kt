package com.ppxb.miwaku.data

import com.ppxb.miwaku.data.network.ApiClient

internal object AppGraph {
    val apiClient: ApiClient by lazy { ApiClient() }
    val comics: ComicRepository by lazy { ComicRepository(apiClient) }
}
