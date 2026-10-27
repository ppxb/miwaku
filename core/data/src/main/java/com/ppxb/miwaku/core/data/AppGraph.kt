package com.ppxb.miwaku.core.data

import android.content.Context
import com.ppxb.miwaku.core.data.image.ChapterManifestCache
import com.ppxb.miwaku.core.data.image.PageImageStore
import com.ppxb.miwaku.core.network.ApiClient
import com.ppxb.miwaku.core.network.SharedSettingSnapshotStore
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object AppGraph {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val settingStore by lazy { SharedSettingSnapshotStore(appContext) }
    private val apiClient by lazy { ApiClient(httpClient, settingStore) }
    private val manifests by lazy { ChapterManifestCache(appContext) }
    val pageStore by lazy { PageImageStore(appContext, apiClient.userAgent(), httpClient) }
    val comics: ComicRepository by lazy { ComicRepository(apiClient, manifests) }
    val history: HistoryStore by lazy { HistoryStore(appContext) }
    val progress: ProgressStore by lazy { ProgressStore(appContext) }
}
