package com.example.comic.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.comic.data.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer is not provided")
}
