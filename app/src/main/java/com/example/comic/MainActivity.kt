package com.example.comic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.comic.ui.ComicApp
import com.example.comic.ui.theme.ComicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ComicTheme {
                ComicApp((application as ComicApplication).container)
            }
        }
    }
}
