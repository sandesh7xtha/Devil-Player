package com.offline.musicplayer.data.model

data class PlayerFeatureSettings(
    val crossfadeSeconds: Int = 0,
    val playbackSpeed: Float = 1f,
    val pitchLocked: Boolean = true,
    val lyricsFontSizeSp: Int = 22,
    val lyricsOffsetMs: Int = 0
)

/**
 * App overlays always sit below the system status bar, so a capsule placed around the
 * camera shares space with the clock and icons. BELOW_STATUS_BAR keeps it fully visible.
 */
enum class CapsulePosition(val label: String) {
    AROUND_CAMERA("Around camera"),
    BELOW_STATUS_BAR("Below status bar")
}

enum class CapsuleSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.2f)
}

data class CapsuleSettings(
    val enabled: Boolean = true,
    val size: CapsuleSize = CapsuleSize.MEDIUM,
    val position: CapsulePosition = CapsulePosition.AROUND_CAMERA,
    val swipeGestures: Boolean = true
)

/** One-time setup prompts that must not reappear on every launch. */
data class SetupPromptState(
    val overlaySetupPrompted: Boolean = false,
    val batteryGuidanceShown: Boolean = false
)

data class TrackAudioInfo(
    val format: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
    val isLossless: Boolean = false
)
