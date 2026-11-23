package com.offline.musicplayer.data.model

data class PlayerFeatureSettings(
    val crossfadeSeconds: Int = 0,
    val playbackSpeed: Float = 1f,
    val pitchLocked: Boolean = true,
    val lyricsFontSizeSp: Int = 22,
    val lyricsOffsetMs: Int = 0
)

enum class CapsuleAnimationMode(val label: String) {
    FULL("Full"),
    REDUCED("Reduced"),
    OFF("Off")
}

/**
 * App overlays always sit below the system status bar, so a capsule placed around the
 * camera shares space with the clock and icons. BELOW_STATUS_BAR keeps it fully visible.
 */
enum class CapsulePosition(val label: String) {
    AROUND_CAMERA("Around camera"),
    BELOW_STATUS_BAR("Below status bar")
}

data class CapsuleSettings(
    val enabled: Boolean = true,
    val position: CapsulePosition = CapsulePosition.AROUND_CAMERA,
    val animationMode: CapsuleAnimationMode = CapsuleAnimationMode.FULL,
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
