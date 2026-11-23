package com.offline.musicplayer.service

import androidx.media3.exoplayer.ExoPlayer
import com.offline.musicplayer.audio.EqualizerManager
import com.offline.musicplayer.audio.ShuffleManager
import com.offline.musicplayer.audio.SleepTimerManager
import com.offline.musicplayer.audio.SmartModeManager
import com.offline.musicplayer.data.model.EqualizerState
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.SleepTimerState
import com.offline.musicplayer.data.model.SmartModeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton bridge facilitating direct reactive flow binding between PlaybackService
 * and UI/ViewModels within the application process.
 */
object ServiceBridge {

    const val COMMAND_SET_SLEEP_TIMER = "com.offline.musicplayer.SET_SLEEP_TIMER"
    const val COMMAND_CANCEL_SLEEP_TIMER = "com.offline.musicplayer.CANCEL_SLEEP_TIMER"
    const val COMMAND_SET_EQUALIZER_ENABLED = "com.offline.musicplayer.SET_EQUALIZER_ENABLED"
    const val COMMAND_SET_EQUALIZER_BAND = "com.offline.musicplayer.SET_EQUALIZER_BAND"
    const val COMMAND_SET_EQUALIZER_PRESET = "com.offline.musicplayer.SET_EQUALIZER_PRESET"
    const val COMMAND_TOGGLE_SHUFFLE = "com.offline.musicplayer.TOGGLE_SHUFFLE"
    const val COMMAND_SET_SMART_MODE_ENABLED = "com.offline.musicplayer.SET_SMART_MODE_ENABLED"
    const val COMMAND_SHOW_FLOATING_PLAYER = "com.offline.musicplayer.SHOW_FLOATING_PLAYER"

    const val ARG_MINUTES = "arg_minutes"
    const val ARG_END_OF_TRACK = "arg_end_of_track"
    const val ARG_ENABLED = "arg_enabled"
    const val ARG_BAND_INDEX = "arg_band_index"
    const val ARG_BAND_LEVEL = "arg_band_level"
    const val ARG_PRESET_NAME = "arg_preset_name"

    var equalizerManager: EqualizerManager? = null
        private set

    var sleepTimerManager: SleepTimerManager? = null
        private set

    var shuffleManager: ShuffleManager? = null
        private set

    var smartModeManager: SmartModeManager? = null
        private set

    var exoPlayer: ExoPlayer? = null
        private set

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _equalizerState = MutableStateFlow(EqualizerState())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _smartModeState = MutableStateFlow(SmartModeState())
    val smartModeState: StateFlow<SmartModeState> = _smartModeState.asStateFlow()

    private val _radioNowPlaying = MutableStateFlow<RadioNowPlaying?>(null)
    val radioNowPlaying: StateFlow<RadioNowPlaying?> = _radioNowPlaying.asStateFlow()

    fun bindService(
        eqManager: EqualizerManager,
        stManager: SleepTimerManager,
        shManager: ShuffleManager,
        smartManager: SmartModeManager,
        player: ExoPlayer
    ) {
        equalizerManager = eqManager
        sleepTimerManager = stManager
        shuffleManager = shManager
        smartModeManager = smartManager
        exoPlayer = player
        _isServiceBound.value = true
    }

    fun updateActivePlayer(player: ExoPlayer) {
        exoPlayer = player
    }

    fun unbindService() {
        equalizerManager = null
        sleepTimerManager = null
        shuffleManager = null
        smartModeManager = null
        exoPlayer = null
        _radioNowPlaying.value = null
        _isServiceBound.value = false
    }

    fun updateEqualizerState(state: EqualizerState) {
        _equalizerState.value = state
    }

    fun updateSleepTimerState(state: SleepTimerState) {
        _sleepTimerState.value = state
    }

    fun updateSmartModeState(state: SmartModeState) {
        _smartModeState.value = state
    }

    fun updateRadioNowPlaying(state: RadioNowPlaying?) {
        _radioNowPlaying.value = state
    }
}
