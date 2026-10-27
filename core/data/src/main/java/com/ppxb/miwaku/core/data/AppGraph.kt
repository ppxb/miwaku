package com.ppxb.miwaku.core.data

import android.content.Context
import com.ppxb.miwaku.core.data.image.ChapterManifestCache
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.SharedSettingSnapshotStore

object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val settingStore by lazy { SharedSettingSnapshotStore(appContext) }
    private val apiClient by lazy { ApiClient(settingStore) }
    private val manifests by lazy { ChapterManifestCache(appContext) }
    val pageStore by lazy { PageImageStore(appContext, apiClient.userAgent()) }
    val comics: ComicRepository by lazy { ComicRepository(apiClient, manifests) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }
    val progress: ProgressStore by lazy { ProgressStore(appContext) }
}
