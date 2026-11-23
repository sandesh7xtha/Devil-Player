package com.offline.musicplayer.data.model

data class SmartModeState(
    val isEnabled: Boolean = false,
    val isSupported: Boolean = false,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null
)
