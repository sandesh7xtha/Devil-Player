package com.offline.musicplayer.data.model

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMillis: Long = 0L,
    val totalDurationMillis: Long = 0L,
    val isEndOfTrackMode: Boolean = false
) {
    val formattedRemainingTime: String
        get() {
            if (!isActive) return ""
            if (isEndOfTrackMode) return "End of track"
            val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%02d:%02d", minutes, seconds)
        }

    val progress: Float
        get() {
            if (!isActive || totalDurationMillis <= 0L) return 0f
            return (remainingMillis.toFloat() / totalDurationMillis.toFloat()).coerceIn(0f, 1f)
        }
}
