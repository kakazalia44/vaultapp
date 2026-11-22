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

class MainActivity : ComponentActivity() {

    private var vaultOpen by mutableStateOf(false)
    private var storageGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FtpState.load(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
        if (!isChangingConfigurations) vaultOpen = false
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
