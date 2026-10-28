package com.example.comic

import android.app.Application
import com.example.comic.data.AppContainer

class ComicApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
