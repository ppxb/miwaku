package com.ppxb.miwaku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ppxb.miwaku.data.settings.SettingsStore
import com.ppxb.miwaku.data.settings.ThemeMode
import com.ppxb.miwaku.ui.MiwakuApp
import com.ppxb.miwaku.ui.theme.MiwakuTheme

class MainActivity : ComponentActivity() {
    private val settings by lazy { SettingsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by settings.themeMode.collectAsState()
            val liquidGlass by settings.liquidGlass.collectAsState()
            val darkTheme =
                when (themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }

            MiwakuTheme(darkTheme = darkTheme) {
                MiwakuApp(
                    themeMode = themeMode,
                    onThemeModeChange = settings::setThemeMode,
                    liquidGlass = liquidGlass,
                    onLiquidGlassChange = settings::setLiquidGlass,
                )
            }
        }
    }
}
