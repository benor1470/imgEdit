package com.benor.imgedit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val st = EditorState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Fonts.init(this)
        Projects.init(this)
        val initial = intentUri(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    EditorScreen(st, initial)
                }
            }
        }
    }

    /** Leaving the app: keep the work as a new version of the project. */
    override fun onStop() {
        super.onStop()
        st.saveVersion()
    }

    /** Picture handed to us by another app ("Share" / "Open with" / "Edit"). */
    @Suppress("DEPRECATION")
    private fun intentUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
        else -> null
    }
}
