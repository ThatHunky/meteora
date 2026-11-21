package dev.thathunky.meteora

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.thathunky.meteora.ui.shell.MeteoraShell
import dev.thathunky.meteora.ui.theme.MeteoraTheme

// AppCompatActivity (not ComponentActivity) so per-app locales work on Android 12.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MeteoraTheme {
                MeteoraShell()
            }
        }
    }
}
