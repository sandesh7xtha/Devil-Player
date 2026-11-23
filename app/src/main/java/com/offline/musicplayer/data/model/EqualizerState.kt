package com.offline.musicplayer.data.model

data class EqualizerBand(
    val index: Int,
    val centerFreqHz: Int,
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val currentLevelMb: Int = 0
) {
    val displayFrequency: String
        get() = if (centerFreqHz >= 1000) {
            val kHz = centerFreqHz / 1000
            val fraction = (centerFreqHz % 1000) / 100
            if (fraction > 0) "$kHz.${fraction}k" else "${kHz}k"
        } else {
            "$centerFreqHz"
        }

    val displayDb: String
        get() {
            val dB = currentLevelMb / 100
            return if (dB > 0) "+$dB dB" else "$dB dB"
        }

    val bandTitle: String
        get() = when {
            centerFreqHz <= 100 -> "Sub-Bass"
            centerFreqHz <= 350 -> "Bass"
            centerFreqHz <= 1200 -> "Midrange"
            centerFreqHz <= 5000 -> "Presence"
            else -> "Treble"
        }
}

val DEFAULT_EQUALIZER_BANDS = listOf(
    EqualizerBand(index = 0, centerFreqHz = 60, minLevelMb = -1500, maxLevelMb = 1500, currentLevelMb = 0),
    EqualizerBand(index = 1, centerFreqHz = 230, minLevelMb = -1500, maxLevelMb = 1500, currentLevelMb = 0),
    EqualizerBand(index = 2, centerFreqHz = 910, minLevelMb = -1500, maxLevelMb = 1500, currentLevelMb = 0),
    EqualizerBand(index = 3, centerFreqHz = 3600, minLevelMb = -1500, maxLevelMb = 1500, currentLevelMb = 0),
    EqualizerBand(index = 4, centerFreqHz = 14000, minLevelMb = -1500, maxLevelMb = 1500, currentLevelMb = 0)
)

val STANDARD_PRESETS = listOf(
    "Flat",
    "Bass Boost",
    "Bass Reducer",
    "Vocal",
    "Rock",
    "Pop",
    "Jazz",
    "Classical",
    "Electronic",
    "Hip Hop",
    "Treble Boost",
    "Custom"
)

data class EqualizerState(
    val isEnabled: Boolean = true,
    val activePreset: String = "Flat",
    val bands: List<EqualizerBand> = DEFAULT_EQUALIZER_BANDS,
    val availablePresets: List<String> = STANDARD_PRESETS
)
