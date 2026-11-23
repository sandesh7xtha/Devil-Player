package com.offline.musicplayer.data.model

import android.os.Build

data class SmartModeState(
    val isEnabled: Boolean = false,
    // Matches SmartModeManager, so the UI is right before the playback service starts.
    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null
)
