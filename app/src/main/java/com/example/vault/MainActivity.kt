package com.example.vault

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Тёмная тема с белым акцентом вместо стандартного сиреневого
private val whiteDarkScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color.White,
    inversePrimary = Color(0xFF424242),
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFE0E0E0),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color.White,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceTint = Color.White,
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF444444),
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF3A3A3A),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF333333)
)

class MainActivity : ComponentActivity() {

    private var vaultOpen by mutableStateOf(false)
    private var storageGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FtpState.load(this)
        VaultCrypto.init(this)

        setContent {
            MaterialTheme(colorScheme = whiteDarkScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ExplorerScreen(
                        inVault = vaultOpen,
                        setInVault = { vaultOpen = it },
                        storageGranted = storageGranted,
                        onRequestStorage = { openStorageSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        storageGranted = Environment.isExternalStorageManager()
    }

    // Свернули приложение — скрытый раздел закрывается
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            vaultOpen = false
            VaultCrypto.lock()
        }
    }

    private fun openStorageSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
