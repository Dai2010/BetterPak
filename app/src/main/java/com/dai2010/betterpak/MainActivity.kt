package com.dai2010.betterpak

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dai2010.betterpak.data.ArchiveEngineProvider
import com.dai2010.betterpak.ui.BetterPakApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchiveEngineProvider.engine.initializeAppStorage(this)
        setContent { BetterPakApp(initialArchiveUri = sharedUri(intent)) }
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
        return intent.data
    }
}
