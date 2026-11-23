package com.offline.musicplayer.audio

import androidx.media3.exoplayer.ExoPlayer
import com.offline.musicplayer.data.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SleepTimerManager(
    private val scope: CoroutineScope
) {
    private var timerJob: Job? = null
    private var activePlayerProvider: (() -> ExoPlayer?)? = null
    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    fun setActivePlayerProvider(provider: () -> ExoPlayer?) {
        activePlayerProvider = provider
    }

    /**
     * Starts sleep timer for specified minutes with smooth 5-second fadeout before pause.
     */
    fun startDurationTimer(minutes: Int, exoPlayer: ExoPlayer) {
        cancelTimer(exoPlayer)

        val totalDurationMillis = minutes * 60 * 1000L
        _sleepTimerState.value = SleepTimerState(
            isActive = true,
            remainingMillis = totalDurationMillis,
            totalDurationMillis = totalDurationMillis,
            isEndOfTrackMode = false
        )

        timerJob = scope.launch {
            val endTime = System.currentTimeMillis() + totalDurationMillis

            while (isActive) {
                val now = System.currentTimeMillis()
                val remaining = endTime - now

                if (remaining <= 0) {
                    // Timer expired: pause player, restore volume, reset state
                    val activePlayer = activePlayerProvider?.invoke() ?: exoPlayer
                    activePlayer.volume = 0f
                    activePlayer.pause()
                    activePlayer.volume = 1.0f
                    _sleepTimerState.value = SleepTimerState(isActive = false)
                    break
                }

                _sleepTimerState.update { it.copy(remainingMillis = remaining) }

                // Smooth fadeout during the final 5 seconds (5000ms)
                if (remaining <= 5000L) {
                    val targetVolume = (remaining.toFloat() / 5000f).coerceIn(0f, 1f)
                    (activePlayerProvider?.invoke() ?: exoPlayer).volume = targetVolume
                    delay(100L)
                } else {
                    // Tick more frequently when getting close, otherwise every 500ms
                    delay(500L)
                }
            }
        }
    }

    /**
     * Sets sleep timer to automatically pause when the current playing track ends.
     */
    fun startEndOfTrackTimer(exoPlayer: ExoPlayer) {
        cancelTimer(exoPlayer)
        _sleepTimerState.value = SleepTimerState(
            isActive = true,
            remainingMillis = 0L,
            totalDurationMillis = 0L,
            isEndOfTrackMode = true
        )
    }

    /**
     * Called on track transition from ExoPlayer.
     * If "End of Current Track" mode is enabled, immediately pauses playback and resets timer.
     */
    fun onMediaItemTransition(exoPlayer: ExoPlayer) {
        if (_sleepTimerState.value.isActive && _sleepTimerState.value.isEndOfTrackMode) {
            exoPlayer.pause()
            cancelTimer(exoPlayer)
        }
    }

    /**
     * Cancels active sleep timer, restoring player volume to 1.0f.
     */
    fun cancelTimer(exoPlayer: ExoPlayer?) {
        timerJob?.cancel()
        timerJob = null
        exoPlayer?.volume = 1.0f
        _sleepTimerState.value = SleepTimerState(
            isActive = false,
            remainingMillis = 0L,
            totalDurationMillis = 0L,
            isEndOfTrackMode = false
        )
    }
}
