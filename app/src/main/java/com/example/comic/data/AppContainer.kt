package com.example.comic.data

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val sourceStore: SourceStore = SourceStore(this.context)
    val progressStore: ProgressStore = ProgressStore(this.context)
    val readingSettings: ReadingSettingsStore = ReadingSettingsStore(this.context)
    val downloadStore: DownloadStore = DownloadStore(this.context)
    val repository: SourceRepository = SourceRepository(
        context = this.context,
        client = httpClient,
        sourceStore = sourceStore,
        progressStore = progressStore,
        downloadStore = downloadStore,
    )
    val imageLoader: ImageLoader = ImageLoader(repository)
    val downloadManager: DownloadManager = DownloadManager(repository, downloadStore)
}
