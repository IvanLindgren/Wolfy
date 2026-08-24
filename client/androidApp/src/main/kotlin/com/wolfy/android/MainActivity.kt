package com.wolfy.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wolfy.ui.WolfyApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Под системные панели рисуем сами: газетная страница должна доходить
        // до края экрана, иначе поля страницы спорят с полями системы.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WolfyApplication(
                onPhone = true,
                serverUrl = BuildConfig.WOLFY_SERVER_URL,
                currentVersion = BuildConfig.VERSION_NAME,
            )
        }
    }
}
