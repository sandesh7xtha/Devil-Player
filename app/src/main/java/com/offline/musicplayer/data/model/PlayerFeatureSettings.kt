package com.offline.musicplayer.data.model

data class PlayerFeatureSettings(
    val crossfadeSeconds: Int = 0,
    val playbackSpeed: Float = 1f,
    val pitchLocked: Boolean = true,
    val lyricsFontSizeSp: Int = 22,
    val lyricsOffsetMs: Int = 0
)

data class TrackAudioInfo(
    val format: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val isLossless: Boolean = false
)
