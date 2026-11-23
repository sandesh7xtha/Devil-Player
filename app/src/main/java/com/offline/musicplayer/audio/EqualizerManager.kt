package com.offline.musicplayer.audio

import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.C
import com.offline.musicplayer.data.model.DEFAULT_EQUALIZER_BANDS
import com.offline.musicplayer.data.model.EqualizerBand
import com.offline.musicplayer.data.model.EqualizerState
import com.offline.musicplayer.data.model.STANDARD_PRESETS
import com.offline.musicplayer.data.preferences.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class EqualizerManager(
    private val preferences: PlayerPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EqualizerManager"
        const val PRESET_CUSTOM = "Custom"
        const val PRESET_FLAT = "Flat"
    }

    private var equalizer: Equalizer? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private val customBandLevels = mutableMapOf<Int, Int>()
    private var saveDebounceJob: Job? = null

    // Immediately initialized with active default bands so UI and spectrum curve are never empty!
    private val _equalizerState = MutableStateFlow(
        EqualizerState(
            isEnabled = true,
            activePreset = PRESET_FLAT,
            bands = DEFAULT_EQUALIZER_BANDS,
            availablePresets = STANDARD_PRESETS
        )
    )
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()

    init {
        // Load initial state from DataStore
        scope.launch {
            try {
                val enabled = preferences.equalizerEnabled.first()
                val savedPreset = preferences.equalizerPreset.first()
                val savedLevels = preferences.equalizerBandLevels.first()

                if (savedLevels.isNotEmpty()) {
                    customBandLevels.putAll(savedLevels)
                }

                val initialBands = if (savedPreset == PRESET_CUSTOM && savedLevels.isNotEmpty()) {
                    DEFAULT_EQUALIZER_BANDS.map { band ->
                        val lvl = savedLevels[band.index] ?: 0
                        band.copy(currentLevelMb = lvl)
                    }
                } else {
                    computePresetBands(savedPreset, DEFAULT_EQUALIZER_BANDS)
                }

                _equalizerState.update {
                    it.copy(
                        isEnabled = enabled,
                        activePreset = savedPreset,
                        bands = initialBands
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved equalizer preferences: ${e.message}")
            }
        }
    }

    /**
     * Called dynamically when ExoPlayer assigns or updates the audio session ID.
     */
    fun onAudioSessionIdChanged(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        if (currentAudioSessionId == audioSessionId && equalizer != null) return

        currentAudioSessionId = audioSessionId
        initHardwareEqualizer(audioSessionId)
    }

    private fun initHardwareEqualizer(audioSessionId: Int) {
        try {
            equalizer?.release()
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq

            val isEnabled = _equalizerState.value.isEnabled
            eq.enabled = isEnabled

            val numBands = eq.numberOfBands.toInt()
            val bandRange = eq.bandLevelRange
            val minLevel = bandRange[0].toInt()
            val maxLevel = bandRange[1].toInt()

            val hwBands = mutableListOf<EqualizerBand>()
            for (i in 0 until numBands) {
                val centerFreq = eq.getCenterFreq(i.toShort()) / 1000
                hwBands.add(
                    EqualizerBand(
                        index = i,
                        centerFreqHz = centerFreq,
                        minLevelMb = minLevel,
                        maxLevelMb = maxLevel,
                        currentLevelMb = 0
                    )
                )
            }

            // Merge hardware presets with standard presets
            val presetList = STANDARD_PRESETS.toMutableList()
            val hwPresetCount = eq.numberOfPresets.toInt()
            for (i in 0 until hwPresetCount) {
                val hwPresetName = eq.getPresetName(i.toShort())
                if (hwPresetName.isNotBlank() && !presetList.contains(hwPresetName)) {
                    presetList.add(presetList.size - 1, hwPresetName) // insert before Custom
                }
            }

            // Apply current preset or custom levels to the hardware effect
            val activePreset = _equalizerState.value.activePreset
            val updatedBands = if (activePreset == PRESET_CUSTOM) {
                hwBands.map { band ->
                    val lvl = (customBandLevels[band.index] ?: 0).coerceIn(minLevel, maxLevel)
                    try { eq.setBandLevel(band.index.toShort(), lvl.toShort()) } catch (_: Exception) {}
                    band.copy(currentLevelMb = lvl)
                }
            } else {
                val targetBands = computePresetBands(activePreset, hwBands)
                targetBands.forEach { band ->
                    try { eq.setBandLevel(band.index.toShort(), band.currentLevelMb.toShort()) } catch (_: Exception) {}
                }
                targetBands
            }

            _equalizerState.update {
                it.copy(
                    bands = updatedBands,
                    availablePresets = presetList
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Hardware Equalizer init failed (fallback active): ${e.message}")
        }
    }

    fun setEnabled(enabled: Boolean) {
        val eq = equalizer
        if (eq != null) {
            try {
                eq.enabled = enabled
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling equalizer enabled: ${e.message}")
            }
        }
        _equalizerState.update { it.copy(isEnabled = enabled) }
        scope.launch {
            preferences.setEqualizerEnabled(enabled)
        }
    }

    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        val currentBands = _equalizerState.value.bands
        val band = currentBands.getOrNull(bandIndex) ?: return

        val clamped = levelMb.coerceIn(band.minLevelMb, band.maxLevelMb)

        // Apply immediately to hardware equalizer if available
        equalizer?.let { eq ->
            try {
                eq.setBandLevel(bandIndex.toShort(), clamped.toShort())
            } catch (e: Exception) {
                Log.e(TAG, "Error setting band level: ${e.message}")
            }
        }

        customBandLevels[bandIndex] = clamped

        val updatedBands = currentBands.map {
            if (it.index == bandIndex) it.copy(currentLevelMb = clamped) else it
        }

        // Updating slider automatically marks preset as Custom
        _equalizerState.update {
            it.copy(
                bands = updatedBands,
                activePreset = PRESET_CUSTOM
            )
        }

        // Debounce DataStore persistence to ensure silky-smooth 60fps slider dragging
        saveDebounceJob?.cancel()
        saveDebounceJob = scope.launch {
            delay(250L)
            preferences.setEqualizerBandLevels(customBandLevels)
            preferences.setEqualizerPreset(PRESET_CUSTOM)
        }
    }

    fun setPreset(presetName: String) {
        val currentBands = _equalizerState.value.bands
        if (currentBands.isEmpty()) return

        // If toggling a preset while disabled, auto-enable
        if (!_equalizerState.value.isEnabled) {
            setEnabled(true)
        }

        val eq = equalizer
        val updatedBands: List<EqualizerBand>

        if (presetName == PRESET_CUSTOM) {
            // Restore custom levels or retain current
            updatedBands = currentBands.map { band ->
                val lvl = customBandLevels[band.index] ?: band.currentLevelMb
                eq?.let {
                    try { it.setBandLevel(band.index.toShort(), lvl.toShort()) } catch (_: Exception) {}
                }
                band.copy(currentLevelMb = lvl)
            }
        } else {
            // Check hardware presets first if attached
            var appliedHw = false
            if (eq != null) {
                try {
                    val hwPresetCount = eq.numberOfPresets.toInt()
                    for (i in 0 until hwPresetCount) {
                        if (eq.getPresetName(i.toShort()).equals(presetName, ignoreCase = true)) {
                            eq.usePreset(i.toShort())
                            appliedHw = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying hardware preset: ${e.message}")
                }
            }

            if (appliedHw && eq != null) {
                updatedBands = currentBands.map { band ->
                    val lvl = eq.getBandLevel(band.index.toShort()).toInt()
                    band.copy(currentLevelMb = lvl)
                }
            } else {
                updatedBands = computePresetBands(presetName, currentBands)
                eq?.let {
                    updatedBands.forEach { band ->
                        try {
                            it.setBandLevel(band.index.toShort(), band.currentLevelMb.toShort())
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        _equalizerState.update {
            it.copy(
                activePreset = presetName,
                bands = updatedBands
            )
        }

        scope.launch {
            preferences.setEqualizerPreset(presetName)
            if (presetName == PRESET_CUSTOM) {
                preferences.setEqualizerBandLevels(customBandLevels)
            }
        }
    }

    private fun computePresetBands(presetName: String, baseBands: List<EqualizerBand>): List<EqualizerBand> {
        val numBands = baseBands.size
        val factors = getPresetFactors(presetName, numBands)

        return baseBands.mapIndexed { index, band ->
            val factor = factors.getOrElse(index) { 0f }
            val levelMb = if (factor >= 0) {
                (factor * band.maxLevelMb).roundToInt()
            } else {
                (-factor * band.minLevelMb).roundToInt()
            }
            band.copy(currentLevelMb = levelMb.coerceIn(band.minLevelMb, band.maxLevelMb))
        }
    }

    private fun getPresetFactors(presetName: String, numBands: Int): List<Float> {
        // High-precision acoustic curves for [60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz]
        val factors5 = when (presetName) {
            "Flat" -> listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
            "Bass Boost" -> listOf(0.65f, 0.45f, 0.15f, 0.0f, 0.0f) // Deep sub & punch
            "Bass Reducer" -> listOf(-0.60f, -0.40f, -0.15f, 0.0f, 0.0f)
            "Vocal" -> listOf(-0.20f, 0.20f, 0.55f, 0.30f, -0.15f) // Crisp vocal presence
            "Rock" -> listOf(0.50f, 0.30f, -0.10f, 0.30f, 0.50f) // Classical V-curve
            "Pop" -> listOf(0.20f, 0.40f, 0.50f, 0.30f, -0.10f)
            "Jazz" -> listOf(0.40f, 0.20f, 0.10f, 0.20f, 0.40f)
            "Classical" -> listOf(0.40f, 0.30f, 0.0f, 0.20f, 0.40f)
            "Electronic" -> listOf(0.60f, 0.40f, 0.0f, 0.30f, 0.55f)
            "Hip Hop" -> listOf(0.55f, 0.45f, 0.10f, 0.20f, 0.30f)
            "Treble Boost" -> listOf(0.0f, 0.0f, 0.20f, 0.50f, 0.65f)
            else -> listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
        }

        if (numBands == 5) return factors5

        // Smooth cubic-like interpolation across arbitrary band count
        return (0 until numBands).map { i ->
            val norm = i.toFloat() / (numBands - 1).coerceAtLeast(1)
            val idxFloat = norm * 4f
            val lower = idxFloat.toInt().coerceIn(0, 4)
            val upper = (lower + 1).coerceIn(0, 4)
            val frac = idxFloat - lower
            factors5[lower] * (1f - frac) + factors5[upper] * frac
        }
    }

    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing equalizer: ${e.message}")
        } finally {
            equalizer = null
            currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
        }
    }
}
