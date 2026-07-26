package com.dai2010.betterpak

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dai2010.betterpak.data.ArchiveEngineProvider
import com.dai2010.betterpak.ui.BetterPakApp

class MainActivity : ComponentActivity() {
    private var cloudCallbackUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchiveEngineProvider.engine.initializeAppStorage(this)
        cloudCallbackUri = cloudCallback(intent)
        setContent {
            BetterPakApp(
                initialArchiveUri = sharedUri(intent),
                cloudCallbackUri = cloudCallbackUri,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cloudCallbackUri = cloudCallback(intent)
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return intent.data?.takeUnless {
            it.scheme == "com.dai2010.betterpak" && it.host == "oauth"
        }
    }

    private fun cloudCallback(intent: Intent?): Uri? = intent?.data?.takeIf {
        it.scheme == "com.dai2010.betterpak" && it.host == "oauth"
    }
}
