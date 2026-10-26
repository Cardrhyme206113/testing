package com.cardrhyme.sharplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cardrhyme.sharplayer.ui.SharpLayerApp
import com.cardrhyme.sharplayer.ui.theme.SharpLayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SharpLayerTheme {
                SharpLayerApp()
            }
        }
    }
}
