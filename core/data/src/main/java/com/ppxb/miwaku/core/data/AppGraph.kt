package com.ppxb.miwaku.core.data

import android.content.Context
import com.ppxb.miwaku.core.network.ApiClient

object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val apiClient by lazy { ApiClient() }
    val comics: ComicRepository by lazy { ComicRepository(apiClient) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }
    val progress: ProgressStore by lazy { ProgressStore(appContext) }
}
