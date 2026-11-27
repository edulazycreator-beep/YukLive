package com.yuklive.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.yuklive.app.ui.navigation.YukLiveRoot
import com.yuklive.app.ui.theme.YukLiveTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activity tunggal. Pakai AppCompatActivity supaya ganti bahasa aplikasi
 * (Indonesia/English) lewat AppCompatDelegate bekerja di semua versi Android.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            YukLiveTheme {
                YukLiveRoot()
            }
        }
    }
}
