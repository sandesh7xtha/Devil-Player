package com.offline.musicplayer.service

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.offline.musicplayer.MainActivity
import com.offline.musicplayer.MusicApplication
import com.offline.musicplayer.R
import com.offline.musicplayer.audio.EqualizerManager
import com.offline.musicplayer.audio.ShuffleManager
import com.offline.musicplayer.audio.SleepTimerManager
import com.offline.musicplayer.audio.SmartModeManager
import com.offline.musicplayer.data.preferences.PlayerPreferences
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PlaybackService : MediaSessionService() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "offline_music_player_channel"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var preferences: PlayerPreferences
    private lateinit var exoPlayer: ExoPlayer
    private var standbyPlayer: ExoPlayer? = null
    private lateinit var equalizerManager: EqualizerManager
    private lateinit var shuffleManager: ShuffleManager
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var smartModeManager: SmartModeManager
    private lateinit var floatingPlayerOverlay: FloatingPlayerOverlay
    private var mediaSession: MediaSession? = null
    private var crossfadeSeconds = 0
    private var crossfadeMonitorJob: kotlinx.coroutines.Job? = null
    private var incomingPreparationJob: kotlinx.coroutines.Job? = null
    private var crossfadeJob: kotlinx.coroutines.Job? = null
    private var standbyTargetMediaId: String? = null
    private var crossfadeSourceMediaId: String? = null

    private fun MediaItem.isLiveRadioItem(): Boolean =
        mediaMetadata.extras?.getBoolean(com.offline.musicplayer.data.model.Song.EXTRA_IS_LIVE_STREAM)
            ?: mediaId.startsWith("radio:")

    private val activePlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val sessionId = exoPlayer.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                equalizerManager.onAudioSessionIdChanged(sessionId)
                smartModeManager.onAudioSessionIdChanged(sessionId)
            }
            updateCrossfadeMonitor()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && standbyTargetMediaId != null) abortCrossfade()
            updateCrossfadeMonitor()
        }

        override fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && standbyTargetMediaId != null) {
                abortCrossfade()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            ServiceBridge.updateRadioNowPlaying(null)
            val incoming = standbyPlayer
            if (crossfadeJob?.isActive == true) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    incoming != null && mediaItem != null &&
                    incoming.currentMediaItem?.mediaId == mediaItem.mediaId
                ) {
                    incoming.volume = 1f
                    exoPlayer.volume = 0f
                    promoteStandbyPlayer()
                    return
                }
                abortCrossfade()
            } else if (standbyTargetMediaId != null &&
                mediaItem?.mediaId == standbyTargetMediaId
            ) {
                // The main deck reached the next item before the incoming deck was ready.
                // Drop the pre-roll and let ExoPlayer's regular gapless playlist transition continue.
                abortCrossfade()
            }

            val sessionId = exoPlayer.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                equalizerManager.onAudioSessionIdChanged(sessionId)
                smartModeManager.onAudioSessionIdChanged(sessionId)
            }
            val currentIndex = exoPlayer.currentMediaItemIndex
            shuffleManager.onMediaItemTransition(exoPlayer, currentIndex)
            sleepTimerManager.onMediaItemTransition(exoPlayer)
            smartModeManager.onMediaItemTransition(mediaItem)
            updateCrossfadeMonitor()
        }

        override fun onMetadata(metadata: Metadata) {
            val item = exoPlayer.currentMediaItem ?: return
            if (!item.isLiveRadioItem()) {
                ServiceBridge.updateRadioNowPlaying(null)
                return
            }
            val icyInfo = (0 until metadata.length())
                .asSequence()
                .mapNotNull { metadata[it] as? IcyInfo }
                .lastOrNull()
                ?: return
            val song = Song.fromMediaItem(item)
            ServiceBridge.updateRadioNowPlaying(
                parseIcyTrackMetadata(song.sourceId.orEmpty(), icyInfo.title.orEmpty())
            )
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (standbyTargetMediaId != null) abortCrossfade()
            serviceScope.launch { preferences.setShuffleEnabled(shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (standbyTargetMediaId != null) abortCrossfade()
            serviceScope.launch { preferences.setRepeatMode(repeatMode) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (standbyTargetMediaId != null) abortCrossfade()
            if (exoPlayer.shuffleModeEnabled && exoPlayer.mediaItemCount > 0 &&
                shuffleManager.getCurrentShuffledIndices().size != exoPlayer.mediaItemCount
            ) {
                shuffleManager.enableShuffle(exoPlayer)
            }
            updateCrossfadeMonitor()
        }
    }

    private fun parseIcyTrackMetadata(stationId: String, rawTitle: String): RadioNowPlaying? {
        val cleaned = rawTitle
            .replace('\u0000', ' ')
            .trim()
            .trim('\'', '"')
            .replace(Regex("(?i)^streamtitle\\s*=\\s*"), "")
            .trim()
            .takeIf {
                it.isNotBlank() && !it.equals("unknown", true) &&
                    !it.equals("no song", true)
            }
            ?: return null

        val tildeFields = cleaned.split('~').map(String::trim)
        if (tildeFields.size >= 2 && tildeFields[0].isNotBlank() && tildeFields[1].isNotBlank()) {
            return RadioNowPlaying(
                stationId = stationId,
                title = tildeFields[0],
                artist = tildeFields[1],
                rawTitle = "${tildeFields[0]}~${tildeFields[1]}"
            )
        }

        val byMatch = Regex("(?i)^(.+?)\\s+by\\s+(.+)$").matchEntire(cleaned)
        if (byMatch != null) {
            return RadioNowPlaying(
                stationId = stationId,
                title = byMatch.groupValues[1].trim(),
                artist = byMatch.groupValues[2].trim(),
                rawTitle = cleaned
            )
        }

        val parts = Regex("\\s+(?:-|–|—|\\|)\\s+").split(cleaned, limit = 2)
        return if (parts.size == 2) {
            RadioNowPlaying(
                stationId = stationId,
                title = parts[1].trim().ifBlank { cleaned },
                artist = parts[0].trim().takeIf(String::isNotBlank),
                rawTitle = cleaned
            )
        } else {
            RadioNowPlaying(stationId = stationId, title = cleaned, rawTitle = cleaned)
        }
    }

    private val audioSessionListener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(
            eventTime: AnalyticsListener.EventTime,
            audioSessionId: Int
        ) {
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId > 0) {
                equalizerManager.onAudioSessionIdChanged(audioSessionId)
                smartModeManager.onAudioSessionIdChanged(audioSessionId)
            }
        }
    }
    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) floatingPlayerOverlay.setAppVisible(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity) {
                serviceScope.launch {
                    delay(400L)
                    floatingPlayerOverlay.setAppVisible(
                        (application as MusicApplication).isMainActivityVisible
                    )
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        preferences = PlayerPreferences(applicationContext)

        createNotificationChannel()
        shuffleManager = ShuffleManager()
        equalizerManager = EqualizerManager(preferences, serviceScope)
        sleepTimerManager = SleepTimerManager(serviceScope)
        sleepTimerManager.setActivePlayerProvider { exoPlayer }
        smartModeManager = SmartModeManager(applicationContext, preferences, serviceScope)
        initExoPlayer()
        floatingPlayerOverlay = FloatingPlayerOverlay(this, exoPlayer, preferences, serviceScope)
        floatingPlayerOverlay.setAppVisible(
            (application as MusicApplication).isMainActivityVisible
        )
        (application as Application).registerActivityLifecycleCallbacks(activityLifecycleCallbacks)

        initMediaSession()

        // Bind references to ServiceBridge for reactive UI consumption
        ServiceBridge.bindService(
            eqManager = equalizerManager,
            stManager = sleepTimerManager,
            shManager = shuffleManager,
            smartManager = smartModeManager,
            player = exoPlayer
        )

        // Observe and bridge states
        serviceScope.launch {
            equalizerManager.equalizerState.collect { state ->
                ServiceBridge.updateEqualizerState(state)
            }
        }
        serviceScope.launch {
            sleepTimerManager.sleepTimerState.collect { state ->
                ServiceBridge.updateSleepTimerState(state)
                if (state.isActive && standbyTargetMediaId != null) abortCrossfade()
                updateCrossfadeMonitor()
            }
        }
        serviceScope.launch {
            smartModeManager.state.collect { state ->
                ServiceBridge.updateSmartModeState(state)
            }
        }

        // Restore playback preferences and keep the inactive deck ready only when crossfade is enabled.
        serviceScope.launch {
            combine(
                preferences.crossfadeSeconds,
                preferences.playbackSpeed,
                preferences.pitchLocked
            ) { crossfade, speed, pitchLocked -> Triple(crossfade, speed, pitchLocked) }
                .collect { (crossfade, speed, pitchLocked) ->
                    crossfadeSeconds = crossfade
                    val pitch = if (pitchLocked) 1f else speed
                    val parameters = PlaybackParameters(speed, pitch)
                    exoPlayer.playbackParameters = parameters
                    standbyPlayer?.playbackParameters = parameters

                    if (crossfadeSeconds > 0) {
                        ensureStandbyPlayer()
                    } else {
                        abortCrossfade()
                        standbyPlayer?.release()
                        standbyPlayer = null
                    }
                    updateCrossfadeMonitor()
                }
        }

        // Restore saved shuffle and repeat preferences
        serviceScope.launch {
            val isShuffle = preferences.shuffleEnabled.first()
            val repeatMode = preferences.repeatMode.first()
            exoPlayer.repeatMode = repeatMode
            if (isShuffle) {
                shuffleManager.enableShuffle(exoPlayer)
            } else {
                shuffleManager.disableShuffle(exoPlayer)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun initExoPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Attach equalizer dynamically to audioSessionId (never hardcoded 0)
        val initialSessionId = exoPlayer.audioSessionId
        if (initialSessionId != C.AUDIO_SESSION_ID_UNSET && initialSessionId > 0) {
            equalizerManager.onAudioSessionIdChanged(initialSessionId)
            smartModeManager.onAudioSessionIdChanged(initialSessionId)
        }

        exoPlayer.addAnalyticsListener(audioSessionListener)
        exoPlayer.addListener(activePlayerListener)
        updateCrossfadeMonitor()
    }

    @OptIn(UnstableApi::class)
    private fun ensureStandbyPlayer(): ExoPlayer {
        standbyPlayer?.let { return it }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            )
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val sessionId = exoPlayer.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
            player.setAudioSessionId(sessionId)
        }
        player.addAnalyticsListener(audioSessionListener)
        player.volume = 0f
        standbyPlayer = player
        return player
    }

    private fun updateCrossfadeMonitor() {
        if (crossfadeSeconds <= 0) {
            crossfadeMonitorJob?.cancel()
            crossfadeMonitorJob = null
            return
        }
        if (crossfadeMonitorJob?.isActive == true) return

        crossfadeMonitorJob = serviceScope.launch {
            while (true) {
                val active = exoPlayer
                val duration = active.duration
                val nextIndex = active.nextMediaItemIndex
                val eligible = active.isPlaying &&
                    active.mediaItemCount > 1 &&
                    active.currentMediaItem?.isLiveRadioItem() == false &&
                    active.repeatMode != Player.REPEAT_MODE_ONE &&
                    nextIndex >= 0 &&
                    nextIndex != active.currentMediaItemIndex &&
                    duration > 0 &&
                    !sleepTimerManager.sleepTimerState.value.isActive

                if (eligible) {
                    val fadeMs = minOf(crossfadeSeconds * 1000L, duration / 2L)
                    val remainingMs = (duration - active.currentPosition).coerceAtLeast(0L)
                    val speed = active.playbackParameters.speed.coerceAtLeast(0.5f)
                    val targetItem = active.getMediaItemAt(nextIndex)
                    if (targetItem.isLiveRadioItem()) {
                        abortCrossfade()
                        delay(100L)
                        continue
                    }
                    val targetId = targetItem.mediaId

                    if (remainingMs <= (fadeMs * speed).toLong() + 1_200L &&
                        standbyTargetMediaId != targetId &&
                        incomingPreparationJob?.isActive != true &&
                        crossfadeJob?.isActive != true
                    ) {
                        prepareIncomingPlayer(active, nextIndex, targetId)
                    }

                    if (remainingMs <= (fadeMs * speed).toLong() &&
                        standbyTargetMediaId == targetId &&
                        standbyPlayer?.playbackState == Player.STATE_READY &&
                        crossfadeJob?.isActive != true
                    ) {
                        beginCrossfade(active, targetId, fadeMs)
                    }
                } else if (standbyTargetMediaId != null && crossfadeJob?.isActive != true) {
                    abortCrossfade()
                }

                delay(100L)
            }
        }
    }

    private fun prepareIncomingPlayer(active: ExoPlayer, targetIndex: Int, targetId: String) {
        incomingPreparationJob?.cancel()
        incomingPreparationJob = serviceScope.launch {
            val sourceId = active.currentMediaItem?.mediaId ?: return@launch
            val incoming = ensureStandbyPlayer()
            standbyTargetMediaId = targetId
            crossfadeSourceMediaId = sourceId
            incoming.pause()
            incoming.volume = 0f
            incoming.clearMediaItems()

            val items = (0 until active.mediaItemCount).map(active::getMediaItemAt)
            val shuffleIndices = shuffleManager.getCurrentShuffledIndices()
            if (active.shuffleModeEnabled && shuffleIndices.size == items.size) {
                incoming.setShuffleOrder(
                    ShuffleOrder.DefaultShuffleOrder(shuffleIndices.copyOf(), SystemClock.elapsedRealtime())
                )
            }
            incoming.repeatMode = active.repeatMode
            incoming.shuffleModeEnabled = active.shuffleModeEnabled
            incoming.playbackParameters = active.playbackParameters
            incoming.setMediaItems(items, targetIndex, 0L)
            incoming.prepare()

            var waitedMs = 0
            while (incoming.playbackState != Player.STATE_READY &&
                incoming.playbackState != Player.STATE_ENDED &&
                waitedMs < 3_500
            ) {
                delay(50L)
                waitedMs += 50
            }

            if (exoPlayer !== active || active.currentMediaItem?.mediaId != sourceId ||
                active.nextMediaItemIndex != targetIndex || incoming.playbackState != Player.STATE_READY
            ) {
                if (standbyTargetMediaId == targetId) abortCrossfade()
            }
        }
    }

    private fun beginCrossfade(active: ExoPlayer, targetId: String, fadeMs: Long) {
        val incoming = standbyPlayer ?: return
        val sourceId = crossfadeSourceMediaId ?: return
        crossfadeJob = serviceScope.launch {
            if (!active.isPlaying || active.currentMediaItem?.mediaId != sourceId ||
                incoming.currentMediaItem?.mediaId != targetId
            ) {
                abortCrossfade()
                return@launch
            }

            incoming.volume = 0f
            incoming.play()
            val startTime = SystemClock.elapsedRealtime()
            val duration = fadeMs.coerceAtLeast(1L)
            while (true) {
                if (!active.isPlaying || active.currentMediaItem?.mediaId != sourceId ||
                    incoming.currentMediaItem?.mediaId != targetId
                ) {
                    abortCrossfade()
                    return@launch
                }
                val progress = ((SystemClock.elapsedRealtime() - startTime).toFloat() / duration)
                    .coerceIn(0f, 1f)
                val angle = progress * (PI.toFloat() / 2f)
                active.volume = cos(angle)
                incoming.volume = sin(angle)
                if (progress >= 1f) break
                delay(40L)
            }
            promoteStandbyPlayer()
        }
    }

    private fun abortCrossfade() {
        incomingPreparationJob?.cancel()
        incomingPreparationJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        exoPlayer.volume = 1f
        standbyPlayer?.let { player ->
            player.pause()
            player.volume = 1f
            player.clearMediaItems()
        }
        standbyTargetMediaId = null
        crossfadeSourceMediaId = null
    }

    private fun promoteStandbyPlayer() {
        val incoming = standbyPlayer ?: return
        if (incoming.currentMediaItem == null) {
            abortCrossfade()
            return
        }
        incomingPreparationJob?.cancel()
        incomingPreparationJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null

        val outgoing = exoPlayer
        outgoing.removeListener(activePlayerListener)
        exoPlayer = incoming
        standbyPlayer = outgoing
        exoPlayer.volume = 1f
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(activePlayerListener)
        mediaSession?.setPlayer(exoPlayer)
        ServiceBridge.updateActivePlayer(exoPlayer)
        floatingPlayerOverlay.updatePlayer(exoPlayer)
        outgoing.pause()
        outgoing.volume = 1f
        outgoing.clearMediaItems()
        standbyTargetMediaId = null
        crossfadeSourceMediaId = null
        shuffleManager.onMediaItemTransition(exoPlayer, exoPlayer.currentMediaItemIndex)
        sleepTimerManager.onMediaItemTransition(exoPlayer)
        smartModeManager.onMediaItemTransition(exoPlayer.currentMediaItem)
        updateCrossfadeMonitor()
    }

    private fun initMediaSession() {
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(CustomSessionCallback())
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        (application as Application).unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
        crossfadeMonitorJob?.cancel()
        incomingPreparationJob?.cancel()
        crossfadeJob?.cancel()
        floatingPlayerOverlay.release()
        ServiceBridge.unbindService()
        equalizerManager.release()
        smartModeManager.release()
        sleepTimerManager.cancelTimer(exoPlayer)

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        standbyPlayer?.release()
        standbyPlayer = null

        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class CustomSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val defaultCommands = super.onConnect(session, controller).availableSessionCommands
            val customCommands = defaultCommands.buildUpon()
                .add(SessionCommand(ServiceBridge.COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_BAND, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_SET_EQUALIZER_PRESET, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_SET_SMART_MODE_ENABLED, Bundle.EMPTY))
                .add(SessionCommand(ServiceBridge.COMMAND_SHOW_FLOATING_PLAYER, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(customCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ServiceBridge.COMMAND_SET_SLEEP_TIMER -> {
                    val endOfTrack = args.getBoolean(ServiceBridge.ARG_END_OF_TRACK, false)
                    if (endOfTrack) {
                        sleepTimerManager.startEndOfTrackTimer(exoPlayer)
                    } else {
                        val minutes = args.getInt(ServiceBridge.ARG_MINUTES, 15)
                        sleepTimerManager.startDurationTimer(minutes, exoPlayer)
                    }
                }
                ServiceBridge.COMMAND_CANCEL_SLEEP_TIMER -> {
                    sleepTimerManager.cancelTimer(exoPlayer)
                }
                ServiceBridge.COMMAND_SET_EQUALIZER_ENABLED -> {
                    val enabled = args.getBoolean(ServiceBridge.ARG_ENABLED, false)
                    equalizerManager.setEnabled(enabled)
                }
                ServiceBridge.COMMAND_SET_EQUALIZER_BAND -> {
                    val bandIndex = args.getInt(ServiceBridge.ARG_BAND_INDEX, -1)
                    val bandLevel = args.getInt(ServiceBridge.ARG_BAND_LEVEL, 0)
                    if (bandIndex >= 0) {
                        equalizerManager.setBandLevel(bandIndex, bandLevel)
                    }
                }
                ServiceBridge.COMMAND_SET_EQUALIZER_PRESET -> {
                    val presetName = args.getString(ServiceBridge.ARG_PRESET_NAME)
                    if (!presetName.isNullOrBlank()) {
                        equalizerManager.setPreset(presetName)
                    }
                }
                ServiceBridge.COMMAND_TOGGLE_SHUFFLE -> {
                    if (exoPlayer.shuffleModeEnabled) {
                        shuffleManager.disableShuffle(exoPlayer)
                    } else {
                        shuffleManager.enableShuffle(exoPlayer)
                    }
                }
                ServiceBridge.COMMAND_SET_SMART_MODE_ENABLED -> {
                    smartModeManager.setEnabled(args.getBoolean(ServiceBridge.ARG_ENABLED, false))
                }
                ServiceBridge.COMMAND_SHOW_FLOATING_PLAYER -> {
                    if (!floatingPlayerOverlay.show()) {
                        android.util.Log.e(
                            "PlaybackService",
                            "Floating controls could not be shown; overlay permission may have been revoked."
                        )
                        return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                        )
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
