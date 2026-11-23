package com.offline.musicplayer.ui.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.IntentSender
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.offline.musicplayer.audio.EqualizerManager
import com.offline.musicplayer.data.model.Album
import com.offline.musicplayer.data.model.Artist
import com.offline.musicplayer.data.model.CapsulePosition
import com.offline.musicplayer.data.model.CapsuleSize
import com.offline.musicplayer.data.model.CapsuleSettings
import com.offline.musicplayer.data.model.SetupPromptState
import com.offline.musicplayer.data.model.EqualizerState
import com.offline.musicplayer.data.model.LyricsState
import com.offline.musicplayer.data.model.PlaybackState
import com.offline.musicplayer.data.model.RemoteFeedState
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.PlayerFeatureSettings
import com.offline.musicplayer.data.model.SleepTimerState
import com.offline.musicplayer.data.model.SmartModeState
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.data.model.TrackAudioInfo
import com.offline.musicplayer.data.preferences.PlayerPreferences
import com.offline.musicplayer.data.repository.DeleteResult
import com.offline.musicplayer.data.repository.LyricsRequestException
import com.offline.musicplayer.data.repository.LyricsRepository
import com.offline.musicplayer.data.repository.MusicRepository
import com.offline.musicplayer.data.repository.RadioRepository
import com.offline.musicplayer.data.repository.JamendoRepository
import com.offline.musicplayer.data.repository.RenameResult
import com.offline.musicplayer.data.repository.TrackAudioInfoReader
import com.offline.musicplayer.service.PlaybackService
import com.offline.musicplayer.service.ServiceBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingSongRename(val song: Song, val baseName: String)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val lyricsRepository = LyricsRepository(application)
    private val trackAudioInfoReader = TrackAudioInfoReader(application)
    private val preferences = PlayerPreferences(application)
    private val radioRepository = RadioRepository()
    private val jamendoRepository = JamendoRepository(com.offline.musicplayer.BuildConfig.JAMENDO_CLIENT_ID)
    private var radioLoadJob: Job? = null
    private var jamendoLoadJob: Job? = null
    private val _radioFeed = MutableStateFlow(RemoteFeedState())
    val radioFeed: StateFlow<RemoteFeedState> = _radioFeed.asStateFlow()
    private val _jamendoFeed = MutableStateFlow(RemoteFeedState())
    val jamendoFeed: StateFlow<RemoteFeedState> = _jamendoFeed.asStateFlow()
    val isJamendoConfigured: Boolean get() = jamendoRepository.isConfigured
    val favoriteRadioStations: StateFlow<List<Song>> = preferences.favoriteRadioStations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val radioNowPlaying: StateFlow<RadioNowPlaying?> = ServiceBridge.radioNowPlaying

    private var mediaController: MediaController? = null
    private var lyricsJob: Job? = null
    private var audioInfoJob: Job? = null
    private var audioInfoTrackId: Long? = null
    private val _lyricsState = MutableStateFlow(LyricsState())
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()
    val playerFeatureSettings: StateFlow<PlayerFeatureSettings> = preferences.playerFeatureSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerFeatureSettings())
    val capsuleSettings: StateFlow<CapsuleSettings> = preferences.capsuleSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, CapsuleSettings())

    /** Null until loaded, so one-time prompts are never shown before their flag is known. */
    val setupPromptState: StateFlow<SetupPromptState?> = preferences.setupPromptState
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _trackAudioInfo = MutableStateFlow<TrackAudioInfo?>(null)
    val trackAudioInfo: StateFlow<TrackAudioInfo?> = _trackAudioInfo.asStateFlow()

    // Library collections
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _libraryLoadError = MutableStateFlow<String?>(null)
    val libraryLoadError: StateFlow<String?> = _libraryLoadError.asStateFlow()
    private var libraryLoadJob: Job? = null

    // Search and tab filter
    val searchQuery = MutableStateFlow("")
    val selectedTab = MutableStateFlow(0) // 0-2 local library, 3 Radio, 4 Discover

    // Selected album/artist detail
    val selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedArtist = MutableStateFlow<Artist?>(null)

    // Playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    private val positionRefresh = MutableStateFlow(0)

    /**
     * Playback position, kept out of [playbackState] so ticking only recomposes the readers
     * of the position. It ticks only while playing and while someone collects it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val playbackPosition: StateFlow<Long> = combine(
        _playbackState.map { it.isPlaying }.distinctUntilChanged(),
        _lyricsState.map { it.isEnabled }.distinctUntilChanged(),
        positionRefresh
    ) { isPlaying, lyricsEnabled, _ -> isPlaying to lyricsEnabled }
        .flatMapLatest { (isPlaying, lyricsEnabled) ->
            flow {
                while (true) {
                    emit(mediaController?.currentPosition ?: 0L)
                    if (!isPlaying) break
                    delay(if (lyricsEnabled) 50L else 500L)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), 0L)

    private val _openNowPlayingRequest = MutableStateFlow(0)
    val openNowPlayingRequest: StateFlow<Int> = _openNowPlayingRequest.asStateFlow()

    // Permission state
    val hasStoragePermission = MutableStateFlow(false)

    // Permanent delete state
    val songPendingDelete = MutableStateFlow<Song?>(null)
    val deleteToastMessage = MutableStateFlow<String?>(null)
    val songPendingRename = MutableStateFlow<PendingSongRename?>(null)
    val renameToastMessage = MutableStateFlow<String?>(null)

    // Service bridged states
    val equalizerState: StateFlow<EqualizerState> = ServiceBridge.equalizerState
    val sleepTimerState: StateFlow<SleepTimerState> = ServiceBridge.sleepTimerState
    val smartModeState: StateFlow<SmartModeState> = ServiceBridge.smartModeState

    // Filtered lists combining search queries
    val filteredSongs: StateFlow<List<Song>> = combine(_allSongs, searchQuery) { songs, query ->
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true) ||
            it.album.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAlbums: StateFlow<List<Album>> = combine(_albums, searchQuery) { albums, query ->
        if (query.isBlank()) albums
        else albums.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredArtists: StateFlow<List<Artist>> = combine(_artists, searchQuery) { artists, query ->
        if (query.isBlank()) artists
        else artists.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        connectToPlaybackService()
        viewModelScope.launch {
            try {
                preferences.migrateLegacyLyricsMode()
            } catch (e: Exception) {
                android.util.Log.e("MusicViewModel", "Could not migrate Live Lyrics preference", e)
            }
            preferences.lyricsEnabled.collect { enabled ->
                if (_lyricsState.value.isEnabled != enabled) {
                    applyLyricsEnabled(enabled, persist = false)
                }
            }
        }
    }

    private fun connectToPlaybackService() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                mediaController = controller
                setupPlayerListener(controller)
                updateStateFromController(controller)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.update {
                    val knownDuration = player.duration
                        .takeIf { duration -> duration > 0 && it.currentSong?.isLiveStream != true }
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        duration = knownDuration ?: it.duration
                    )
                }
                refreshPosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                refreshPosition()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentSong = mediaItem?.let { Song.fromMediaItem(it) }
                val currentIndex = player.currentMediaItemIndex
                val duration = if (currentSong?.isLiveStream == true) 0L
                    else if (player.duration > 0) player.duration else currentSong?.duration ?: 0L

                _playbackState.update {
                    it.copy(
                        currentSong = currentSong,
                        duration = duration,
                        currentQueueIndex = currentIndex
                    )
                }
                refreshPosition()
                loadLyricsForTrack(
                    currentSong?.let { song ->
                        if (duration > 0) song.copy(duration = duration) else song
                    }
                )
                loadAudioInfo(currentSong)
                if (currentSong != null && currentSong.sourceType == TrackSourceType.LOCAL) {
                    viewModelScope.launch {
                        preferences.setLastPlayedSongId(currentSong.id)
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _playbackState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _playbackState.update { it.copy(repeatMode = repeatMode) }
            }
        })
    }

    private fun updateStateFromController(controller: MediaController) {
        val currentMediaItem = controller.currentMediaItem
        val currentSong = currentMediaItem?.let { Song.fromMediaItem(it) }
        val duration = if (currentSong?.isLiveStream == true) 0L
            else if (controller.duration > 0) controller.duration else currentSong?.duration ?: 0L

        if (_lyricsState.value.trackId != currentSong?.id) {
            loadLyricsForTrack(
                currentSong?.let { song ->
                    if (duration > 0) song.copy(duration = duration) else song
                }
            )
        }
        if (audioInfoTrackId != currentSong?.id) {
            loadAudioInfo(currentSong)
        }
        _playbackState.update {
            it.copy(
                currentSong = currentSong,
                isPlaying = controller.isPlaying,
                duration = duration,
                isShuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                currentQueueIndex = controller.currentMediaItemIndex
            )
        }
        refreshPosition()
    }

    private fun refreshPosition() {
        positionRefresh.update { it + 1 }
    }

    fun loadLibrary() {
        if (libraryLoadJob?.isActive == true) return
        libraryLoadJob = viewModelScope.launch {
            _isLoading.value = true
            _libraryLoadError.value = null
            try {
                val songs = repository.fetchAudioFiles()
                _allSongs.value = songs
                _albums.value = repository.groupIntoAlbums(songs)
                _artists.value = repository.groupIntoArtists(songs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MusicViewModel", "Could not scan the music library", e)
                _libraryLoadError.value =
                    "Could not scan your music. Check audio access, then try again."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onPermissionGranted() {
        hasStoragePermission.value = true
        loadLibrary()
    }

    fun playSong(song: Song, queue: List<Song> = _allSongs.value) {
        val controller = mediaController ?: return
        val startIndex = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val mediaItems = queue.map { it.toMediaItem() }

        _playbackState.update {
            it.copy(
                queue = queue,
                currentSong = song,
                currentQueueIndex = startIndex,
                duration = song.duration
            )
        }

        controller.setMediaItems(mediaItems, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun loadTopRadio() = loadRadioFeed { radioRepository.topStations() }

    fun searchRadio(name: String = "", countryCode: String? = null, tag: String? = null) {
        if (name.isBlank() && countryCode == null && tag == null) {
            loadTopRadio()
        } else {
            loadRadioFeed { radioRepository.search(name.takeIf(String::isNotBlank), countryCode, tag) }
        }
    }

    fun toggleFavoriteRadioStation(station: Song) {
        viewModelScope.launch { preferences.toggleFavoriteRadioStation(station) }
    }

    private fun loadRadioFeed(load: suspend () -> List<Song>) {
        radioLoadJob?.cancel()
        radioLoadJob = viewModelScope.launch {
            _radioFeed.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val items = load()
                _radioFeed.value = RemoteFeedState(items = items)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MusicViewModel", "Could not load radio stations", e)
                _radioFeed.update {
                    it.copy(isLoading = false, errorMessage = "Could not load stations. Check your connection and try again.")
                }
            }
        }
    }

    fun playRadioStation(station: Song, queue: List<Song> = _radioFeed.value.items) {
        station.sourceId?.let { id -> viewModelScope.launch { radioRepository.recordClick(id) } }
        playSong(station, queue.ifEmpty { listOf(station) })
    }

    fun loadJamendoTracks(search: String = "", genre: String? = null) {
        jamendoLoadJob?.cancel()
        if (!jamendoRepository.isConfigured) {
            _jamendoFeed.value = RemoteFeedState(
                errorMessage = "Jamendo needs an API client ID. Add JAMENDO_CLIENT_ID to local.properties, then rebuild the app."
            )
            return
        }
        jamendoLoadJob = viewModelScope.launch {
            _jamendoFeed.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val items = jamendoRepository.tracks(search, genre)
                _jamendoFeed.value = RemoteFeedState(items = items)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MusicViewModel", "Could not load Jamendo tracks", e)
                _jamendoFeed.update {
                    it.copy(isLoading = false, errorMessage = "Could not load music. Check your connection and try again.")
                }
            }
        }
    }

    fun playJamendoTrack(track: Song, queue: List<Song> = _jamendoFeed.value.items) {
        playSong(track, queue.ifEmpty { listOf(track) })
    }

    fun playAlbum(album: Album) {
        if (album.songs.isNotEmpty()) {
            playSong(album.songs.first(), album.songs)
        }
    }

    fun playArtist(artist: Artist) {
        if (artist.songs.isNotEmpty()) {
            playSong(artist.songs.first(), artist.songs)
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (_playbackState.value.currentSong?.isLiveStream == true) return
        val controller = mediaController ?: return
        controller.seekTo(positionMs)
        refreshPosition()
    }

    fun skipNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 3000) {
            controller.seekTo(0)
        } else {
            controller.seekToPreviousMediaItem()
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val newShuffle = !controller.shuffleModeEnabled

        // Execute via custom command to ensure service Fisher-Yates generator is invoked
        controller.sendCustomCommand(
            SessionCommand(ServiceBridge.COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY),
            Bundle.EMPTY
        )
        _playbackState.update { it.copy(isShuffleEnabled = newShuffle) }
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val nextMode = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = nextMode
        _playbackState.update { it.copy(repeatMode = nextMode) }
    }

    // Equalizer controls
    fun setEqualizerEnabled(enabled: Boolean) {
        ServiceBridge.equalizerManager?.setEnabled(enabled)
            ?: mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_ENABLED, Bundle.EMPTY),
                Bundle().apply { putBoolean(ServiceBridge.ARG_ENABLED, enabled) }
            )
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelMb: Int) {
        ServiceBridge.equalizerManager?.setBandLevel(bandIndex, levelMb)
            ?: mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_BAND, Bundle.EMPTY),
                Bundle().apply {
                    putInt(ServiceBridge.ARG_BAND_INDEX, bandIndex)
                    putInt(ServiceBridge.ARG_BAND_LEVEL, levelMb)
                }
            )
    }

    fun setEqualizerPreset(presetName: String) {
        ServiceBridge.equalizerManager?.setPreset(presetName)
            ?: mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_PRESET, Bundle.EMPTY),
                Bundle().apply { putString(ServiceBridge.ARG_PRESET_NAME, presetName) }
            )
    }

    fun setSmartModeEnabled(enabled: Boolean) {
        ServiceBridge.smartModeManager?.setEnabled(enabled)
            ?: mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_SMART_MODE_ENABLED, Bundle.EMPTY),
                Bundle().apply { putBoolean(ServiceBridge.ARG_ENABLED, enabled) }
            )
    }

    fun showFloatingPlayer() {
        viewModelScope.launch { preferences.setCapsuleEnabled(true) }
        mediaController?.sendCustomCommand(
            SessionCommand(ServiceBridge.COMMAND_SHOW_FLOATING_PLAYER, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun requestOpenNowPlaying() {
        _openNowPlayingRequest.update { it + 1 }
    }

    fun clearSmartModeError() {
        ServiceBridge.smartModeManager?.clearError()
    }

    fun setLyricsEnabled(enabled: Boolean) {
        applyLyricsEnabled(enabled, persist = true)
    }

    fun setCapsuleEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setCapsuleEnabled(enabled) }
    }

    fun setCapsuleSize(size: CapsuleSize) {
        viewModelScope.launch { preferences.setCapsuleSize(size) }
    }

    fun setCapsulePosition(position: CapsulePosition) {
        viewModelScope.launch { preferences.setCapsulePosition(position) }
    }

    fun setCapsuleSwipeGestures(enabled: Boolean) {
        viewModelScope.launch { preferences.setCapsuleSwipeGestures(enabled) }
    }

    fun markOverlaySetupPrompted() {
        viewModelScope.launch { preferences.markOverlaySetupPrompted() }
    }

    fun markBatteryGuidanceShown() {
        viewModelScope.launch { preferences.markBatteryGuidanceShown() }
    }

    fun resetSettings() {
        viewModelScope.launch { preferences.resetSettings() }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setCrossfadeSeconds(seconds) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val rounded = (
            speed.coerceIn(
                PlayerPreferences.MIN_PLAYBACK_SPEED,
                PlayerPreferences.MAX_PLAYBACK_SPEED
            ) * 100f
        ).toInt() / 100f
        viewModelScope.launch { preferences.setPlaybackSpeed(rounded) }
    }

    fun setPitchLocked(locked: Boolean) {
        viewModelScope.launch { preferences.setPitchLocked(locked) }
    }

    fun setLyricsFontSizeSp(sizeSp: Int) {
        viewModelScope.launch { preferences.setLyricsFontSizeSp(sizeSp) }
    }

    fun setLyricsOffsetMs(offsetMs: Int) {
        viewModelScope.launch { preferences.setLyricsOffsetMs(offsetMs) }
    }

    private fun loadAudioInfo(song: Song?) {
        audioInfoJob?.cancel()
        audioInfoTrackId = song?.id
        _trackAudioInfo.value = null
        if (song == null || song.sourceType != TrackSourceType.LOCAL) return
        audioInfoJob = viewModelScope.launch {
            val info = runCatching { trackAudioInfoReader.read(song.contentUri) }.getOrNull()
            if (_playbackState.value.currentSong?.id == song.id) {
                _trackAudioInfo.value = info
            }
        }
    }

    fun retryLyrics() {
        if (_lyricsState.value.isEnabled) {
            loadLyricsForTrack(_playbackState.value.currentSong)
        }
    }

    fun searchLyrics(title: String, artist: String) {
        val currentSong = _playbackState.value.currentSong ?: return
        val cleanedTitle = title.trim()
        if (cleanedTitle.isEmpty()) {
            _lyricsState.update {
                it.copy(message = "Enter a track title to search for lyrics.")
            }
            return
        }
        loadLyricsForTrack(currentSong.copy(title = cleanedTitle, artist = artist.trim()))
    }

    private fun applyLyricsEnabled(enabled: Boolean, persist: Boolean) {
        if (enabled == _lyricsState.value.isEnabled) return

        if (!enabled) {
            lyricsJob?.cancel()
            lyricsRepository.cancelActiveRequest()
            _lyricsState.value = LyricsState(
                isEnabled = false,
                trackId = _playbackState.value.currentSong?.id
            )
        } else {
            _lyricsState.value = LyricsState(
                isEnabled = true,
                trackId = _playbackState.value.currentSong?.id
            )
            loadLyricsForTrack(_playbackState.value.currentSong)
        }

        if (persist) {
            viewModelScope.launch {
                try {
                    preferences.setLyricsEnabled(enabled)
                } catch (e: Exception) {
                    android.util.Log.e("MusicViewModel", "Could not save lyrics mode", e)
                    _lyricsState.update {
                        it.copy(message = "Could not save the Live Lyrics setting.")
                    }
                }
            }
        }
    }

    private fun loadLyricsForTrack(song: Song?) {
        if (song?.isLiveStream == true) {
            lyricsJob?.cancel()
            lyricsRepository.cancelActiveRequest()
            _lyricsState.value = LyricsState(
                isEnabled = _lyricsState.value.isEnabled,
                trackId = song.id,
                message = "Live radio has no track lyrics."
            )
            return
        }
        val playerDuration = mediaController?.duration?.takeIf { it > 0L }
            ?: _playbackState.value.duration.takeIf {
                it > 0L && _playbackState.value.currentSong?.id == song?.id
            }
        val activeSong = song?.let { current ->
            playerDuration?.let { current.copy(duration = it) } ?: current
        }
        lyricsJob?.cancel()
        lyricsRepository.cancelActiveRequest()
        val lyricsEnabled = _lyricsState.value.isEnabled
        _lyricsState.value = LyricsState(
            isEnabled = lyricsEnabled,
            trackId = activeSong?.id,
            isLoading = activeSong != null && lyricsEnabled
        )

        if (activeSong == null || !lyricsEnabled) return

        lyricsJob = viewModelScope.launch {
            _lyricsState.update { it.copy(isLoading = true) }
            try {
                val knownArtists = _artists.value.map { it.name }
                val resolvedName = lyricsRepository.resolveTrackName(activeSong, knownArtists)
                if (_lyricsState.value.trackId != activeSong.id ||
                    !_lyricsState.value.isEnabled
                ) return@launch
                _lyricsState.update {
                    it.copy(
                        resolvedTitle = resolvedName.title,
                        resolvedArtist = resolvedName.artist
                    )
                }
                val result = lyricsRepository.fetch(
                    activeSong,
                    knownArtists = knownArtists,
                    resolvedName = resolvedName
                )
                if (_lyricsState.value.trackId != activeSong.id ||
                    !_lyricsState.value.isEnabled
                ) return@launch

                if (result != null &&
                    (result.lines.isNotEmpty() || !result.plainLyrics.isNullOrBlank())
                ) {
                    _lyricsState.update {
                        it.copy(
                            lines = result.lines,
                            plainLyrics = result.plainLyrics,
                            timing = result.timing,
                            isLoading = false,
                            message = null
                        )
                    }
                } else {
                    _lyricsState.update {
                        it.copy(
                            isLoading = false,
                            message = "No lyrics were found for this title and artist on LRCLIB. Check the search details or try again later."
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_lyricsState.value.trackId == activeSong.id &&
                    _lyricsState.value.isEnabled
                ) {
                    val message = when (e) {
                        is LyricsRequestException -> when (e.statusCode) {
                            400 -> "LRCLIB rejected the track information. Check the title and artist tags."
                            429, 503 -> e.retryAfterSeconds?.let { seconds ->
                                "LRCLIB is temporarily busy. Try again in $seconds seconds."
                            } ?: "LRCLIB is temporarily busy. Try again shortly."
                            else -> "LRCLIB request failed (HTTP ${e.statusCode}). Try again."
                        }
                        else -> "Could not reach LRCLIB. Check your internet connection and try again."
                    }
                    _lyricsState.update {
                        it.copy(
                            isLoading = false,
                            message = message
                        )
                    }
                }
            }
        }
    }

    // Sleep Timer controls
    fun startSleepTimer(minutes: Int) {
        val player = ServiceBridge.exoPlayer
        if (player != null && ServiceBridge.sleepTimerManager != null) {
            ServiceBridge.sleepTimerManager?.startDurationTimer(minutes, player)
        } else {
            mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply {
                    putInt(ServiceBridge.ARG_MINUTES, minutes)
                    putBoolean(ServiceBridge.ARG_END_OF_TRACK, false)
                }
            )
        }
    }

    fun startEndOfTrackSleepTimer() {
        val player = ServiceBridge.exoPlayer
        if (player != null && ServiceBridge.sleepTimerManager != null) {
            ServiceBridge.sleepTimerManager?.startEndOfTrackTimer(player)
        } else {
            mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply {
                    putBoolean(ServiceBridge.ARG_END_OF_TRACK, true)
                }
            )
        }
    }

    fun cancelSleepTimer() {
        val player = ServiceBridge.exoPlayer
        if (player != null && ServiceBridge.sleepTimerManager != null) {
            ServiceBridge.sleepTimerManager?.cancelTimer(player)
        } else {
            mediaController?.sendCustomCommand(
                SessionCommand(ServiceBridge.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
    }

    /**
     * Initiates permanent deletion of a track from disk and MediaStore.
     */
    fun initiateDeleteSong(song: Song, onLaunchIntentSender: (IntentSender) -> Unit) {
        viewModelScope.launch {
            songPendingDelete.value = song
            when (val result = repository.deleteSong(song)) {
                is DeleteResult.Success -> {
                    onSongDeletedSuccessfully(song)
                }
                is DeleteResult.RequiresPermission -> {
                    onLaunchIntentSender(result.intentSender)
                }
                is DeleteResult.Error -> {
                    deleteToastMessage.value = result.message
                    songPendingDelete.value = null
                }
            }
        }
    }

    /**
     * Called when the system or direct deletion is finalized.
     */
    fun onSongDeletedSuccessfully(song: Song) {
        viewModelScope.launch {
            repository.clearRenamedTrack(song)
        }

        // If currently playing the deleted song, skip or stop
        if (_playbackState.value.currentSong?.id == song.id) {
            val queue = _playbackState.value.queue
            if (queue.size > 1) {
                skipNext()
            } else {
                mediaController?.stop()
                mediaController?.clearMediaItems()
                _playbackState.update {
                    it.copy(
                        currentSong = null,
                        isPlaying = false,
                        duration = 0L,
                        queue = emptyList()
                    )
                }
            }
        }

        // Remove song from all collections
        val updated = _allSongs.value.filter { it.id != song.id }
        _allSongs.value = updated
        _albums.value = repository.groupIntoAlbums(updated)
        _artists.value = repository.groupIntoArtists(updated)

        // Update selected album or artist if currently viewing
        selectedAlbum.value?.let { alb ->
            if (alb.songs.any { it.id == song.id }) {
                val updatedAlbSongs = alb.songs.filter { it.id != song.id }
                selectedAlbum.value = if (updatedAlbSongs.isNotEmpty()) {
                    alb.copy(songs = updatedAlbSongs, songCount = updatedAlbSongs.size)
                } else null
            }
        }

        selectedArtist.value?.let { art ->
            if (art.songs.any { it.id == song.id }) {
                val updatedArtSongs = art.songs.filter { it.id != song.id }
                selectedArtist.value = if (updatedArtSongs.isNotEmpty()) {
                    art.copy(songs = updatedArtSongs, songCount = updatedArtSongs.size)
                } else null
            }
        }

        deleteToastMessage.value = "\"${song.title}\" deleted permanently from device"
        songPendingDelete.value = null
    }

    fun clearDeleteToastMessage() {
        deleteToastMessage.value = null
    }

    fun initiateRenameSong(
        song: Song,
        baseName: String,
        onLaunchIntentSender: (IntentSender) -> Unit
    ) {
        val pending = PendingSongRename(song, baseName)
        songPendingRename.value = pending
        viewModelScope.launch {
            handleRenameResult(
                pending,
                repository.renameSong(song, baseName),
                onLaunchIntentSender
            )
        }
    }

    suspend fun getAudioDisplayName(song: Song): String =
        repository.getDisplayName(song) ?: song.title

    fun completePendingSongRename() {
        val pending = songPendingRename.value ?: return
        viewModelScope.launch {
            handleRenameResult(
                pending,
                repository.renameSong(
                    pending.song,
                    pending.baseName,
                    requestPermission = false
                )
            ) {}
        }
    }

    private fun handleRenameResult(
        pending: PendingSongRename,
        result: RenameResult,
        onLaunchIntentSender: ((IntentSender) -> Unit)? = null
    ) {
        when (result) {
            is RenameResult.Success -> {
                applyRenamedTrack(pending)
                renameToastMessage.value = "\"${result.displayName}\" renamed"
                songPendingRename.value = null
                if (_playbackState.value.currentSong?.id == pending.song.id &&
                    _lyricsState.value.isEnabled
                ) {
                    loadLyricsForTrack(_playbackState.value.currentSong)
                }
            }
            is RenameResult.RequiresPermission -> {
                if (onLaunchIntentSender != null) {
                    onLaunchIntentSender(result.intentSender)
                } else {
                    renameToastMessage.value = "Permission was not granted to rename this file"
                    songPendingRename.value = null
                }
            }
            is RenameResult.Error -> {
                renameToastMessage.value = result.message
                songPendingRename.value = null
            }
        }
    }

    private fun applyRenamedTrack(pending: PendingSongRename) {
        // A file rename changes only the filename; embedded library title and artist tags stay intact.
        val renamedSong = pending.song
        mediaController?.let { controller ->
            if (controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                for (index in 0 until controller.mediaItemCount) {
                    if (controller.getMediaItemAt(index).mediaId == renamedSong.id.toString()) {
                        controller.replaceMediaItem(index, renamedSong.toMediaItem())
                    }
                }
            } else {
                android.util.Log.w(
                    "MusicViewModel",
                    "Playback controller cannot update the renamed track metadata."
                )
            }
        }
        fun updateSongs(songs: List<Song>) = songs.map { song ->
            if (song.id == renamedSong.id) renamedSong else song
        }

        val updatedSongs = updateSongs(_allSongs.value)
        _allSongs.value = updatedSongs
        _albums.value = repository.groupIntoAlbums(updatedSongs)
        _artists.value = repository.groupIntoArtists(updatedSongs)
        selectedAlbum.value?.let { album ->
            selectedAlbum.value = album.copy(songs = updateSongs(album.songs))
        }
        selectedArtist.value?.let { artist ->
            selectedArtist.value = artist.copy(songs = updateSongs(artist.songs))
        }
        _playbackState.update { state ->
            state.copy(
                currentSong = state.currentSong?.let {
                    if (it.id == renamedSong.id) renamedSong else it
                },
                queue = updateSongs(state.queue)
            )
        }
    }

    fun clearRenameToastMessage() {
        renameToastMessage.value = null
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        lyricsRepository.cancelActiveRequest()
        audioInfoJob?.cancel()
        _lyricsState.value = LyricsState()
        mediaController?.release()
        mediaController = null
        super.onCleared()
    }
}
