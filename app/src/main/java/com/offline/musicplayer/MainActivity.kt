package com.offline.musicplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.offline.musicplayer.ui.screens.LibraryScreen
import com.offline.musicplayer.ui.screens.checkHasPermissions
import com.offline.musicplayer.ui.theme.OfflineMusicPlayerTheme
import com.offline.musicplayer.ui.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.offline.musicplayer.OPEN_NOW_PLAYING"
    }

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check if permissions are already granted on startup
        if (checkHasPermissions(this)) {
            viewModel.onPermissionGranted()
        }
        handleNowPlayingIntent(intent)

        setContent {
            OfflineMusicPlayerTheme {
                LibraryScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkHasPermissions(this) && !viewModel.hasStoragePermission.value) {
            viewModel.onPermissionGranted()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNowPlayingIntent(intent)
    }

    private fun handleNowPlayingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
            viewModel.requestOpenNowPlaying()
        }
    }
}
