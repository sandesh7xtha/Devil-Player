package com.offline.musicplayer.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.offline.musicplayer.data.local.TrackLoudnessDatabase
import com.offline.musicplayer.data.model.SmartModeState
import com.offline.musicplayer.data.model.TrackLoudnessEntity
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.data.preferences.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmartModeManager(
    context: Context,
    private val preferences: PlayerPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SmartLoudnessManager"
        private const val CHANNEL_COUNT = 2
        private const val TARGET_DB = -1.0f
    }

    private val dao = TrackLoudnessDatabase.getInstance(context).trackLoudnessDao()
    private val peakScanner = TrackPeakScanner(context.applicationContext)

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var currentTrackId: Long? = null
    private var currentTrackUri: Uri? = null
    private var scanJob: Job? = null

    private val _state = MutableStateFlow(
        SmartModeState(isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    )
    val state: StateFlow<SmartModeState> = _state.asStateFlow()

    init {
        scope.launch {
            val isEnabled = preferences.smartModeEnabled.first()
            _state.update { it.copy(isEnabled = isEnabled && it.isSupported) }
            if (isEnabled && _state.value.isSupported) {
                normalizeCurrentTrack()
            }
        }
    }

    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (!_state.value.isSupported ||
            audioSessionId == C.AUDIO_SESSION_ID_UNSET ||
            audioSessionId <= 0 ||
            currentAudioSessionId == audioSessionId
        ) {
            return
        }

        releaseEffect()
        currentAudioSessionId = audioSessionId
        if (_state.value.isEnabled) {
            normalizeCurrentTrack()
        }
    }

    fun onMediaItemTransition(mediaItem: MediaItem?) {
        scanJob?.cancel()
        val sourceType = mediaItem?.mediaMetadata?.extras
            ?.getString(Song.EXTRA_SOURCE_TYPE)
            ?.let { runCatching { TrackSourceType.valueOf(it) }.getOrNull() }
            ?: when {
                mediaItem?.mediaId?.startsWith("radio:") == true -> TrackSourceType.RADIO_STREAM
                mediaItem?.mediaId?.startsWith("jamendo:") == true -> TrackSourceType.JAMENDO_STREAM
                else -> TrackSourceType.LOCAL
            }
        if (sourceType != TrackSourceType.LOCAL) {
            currentTrackId = null
            currentTrackUri = null
            applyInputGain(0f)
            _state.update { it.copy(isAnalyzing = false, errorMessage = null) }
            return
        }
        currentTrackId = mediaItem?.mediaId?.toLongOrNull()
        currentTrackUri = mediaItem?.localConfiguration?.uri
        applyInputGain(0f)
        _state.update { it.copy(isAnalyzing = false, errorMessage = null) }
        if (_state.value.isEnabled) {
            normalizeCurrentTrack()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!_state.value.isSupported) return

        _state.update { it.copy(isEnabled = enabled, errorMessage = null) }
        scope.launch {
            try {
                preferences.setSmartModeEnabled(enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Could not save Smart Loudness preference", e)
                _state.update {
                    it.copy(errorMessage = "Could not save the Smart Loudness setting.")
                }
            }
        }

        if (enabled) {
            normalizeCurrentTrack()
        } else {
            scanJob?.cancel()
            _state.update { it.copy(isAnalyzing = false) }
            applyInputGain(0f)
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun normalizeCurrentTrack() {
        val trackId = currentTrackId ?: return
        val uri = currentTrackUri ?: return
        if (!_state.value.isEnabled || !_state.value.isSupported) return

        scanJob?.cancel()
        scanJob = scope.launch {
            _state.update { it.copy(isAnalyzing = true, errorMessage = null) }
            try {
                val cachedPeak = withContext(Dispatchers.IO) { dao.get(trackId)?.peakDb }
                val peakDb = cachedPeak ?: withContext(Dispatchers.IO) {
                    peakScanner.findPeakDb(uri)?.also { measuredPeak ->
                        dao.upsert(TrackLoudnessEntity(trackId = trackId, peakDb = measuredPeak))
                    }
                }

                if (currentTrackId != trackId || !_state.value.isEnabled) return@launch
                if (peakDb == null) {
                    applyInputGain(0f)
                    _state.update {
                        it.copy(
                            errorMessage = "Could not analyze this track; playing it without normalization."
                        )
                    }
                    return@launch
                }

                applyInputGain(TARGET_DB - peakDb)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not normalize track $trackId", e)
                if (currentTrackId == trackId) {
                    applyInputGain(0f)
                    _state.update {
                        it.copy(errorMessage = "Smart Loudness failed; playing without normalization.")
                    }
                }
            } finally {
                if (currentTrackId == trackId) {
                    _state.update { it.copy(isAnalyzing = false) }
                }
            }
        }
    }

    private fun applyInputGain(gainDb: Float) {
        if (!_state.value.isSupported || currentAudioSessionId <= 0) return
        // 0 dB with no effect attached is a no-op. Creating the effect is a blocking
        // audio-server call on the main thread, so skip it (this ran on every track
        // change, even with Smart Mode off, and stalled the UI right after a swipe).
        if (gainDb == 0f && dynamicsProcessing == null) return
        try {
            val effect = dynamicsProcessing ?: createEffect() ?: return
            effect.setInputGainAllChannelsTo(gainDb)
            effect.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not apply Smart Loudness gain", e)
            releaseEffect()
            _state.update {
                it.copy(
                    isEnabled = false,
                    isAnalyzing = false,
                    errorMessage = "Smart Loudness is unavailable on this device."
                )
            }
            scope.launch {
                try {
                    preferences.setSmartModeEnabled(false)
                } catch (preferenceError: Exception) {
                    Log.e(TAG, "Could not persist disabled Smart Loudness state", preferenceError)
                }
            }
        }
    }

    private fun createEffect(): DynamicsProcessing? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            currentAudioSessionId <= 0
        ) {
            return null
        }

        val config = DynamicsProcessing.Config.Builder(
            0,
            CHANNEL_COUNT,
            false,
            0,
            false,
            0,
            false,
            0,
            false
        ).build()

        return DynamicsProcessing(0, currentAudioSessionId, config).also {
            dynamicsProcessing = it
        }
    }

    private fun releaseEffect() {
        try {
            dynamicsProcessing?.setInputGainAllChannelsTo(0f)
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Could not release Smart Loudness audio effect", e)
        } finally {
            dynamicsProcessing = null
        }
    }

    fun release() {
        scanJob?.cancel()
        releaseEffect()
        currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }
}
