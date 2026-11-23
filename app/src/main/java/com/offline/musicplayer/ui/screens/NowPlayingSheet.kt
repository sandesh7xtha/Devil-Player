package com.offline.musicplayer.ui.screens

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.offline.musicplayer.data.model.PlaybackState
import com.offline.musicplayer.data.model.LyricsState
import com.offline.musicplayer.data.model.LyricsTiming
import com.offline.musicplayer.data.model.PlayerFeatureSettings
import com.offline.musicplayer.data.model.SleepTimerState
import com.offline.musicplayer.data.model.SmartModeState
import com.offline.musicplayer.data.model.TrackAudioInfo
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.ui.theme.AuraBackground
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary
import com.offline.musicplayer.ui.theme.AuraTextTertiary
import com.offline.musicplayer.ui.theme.rememberDominantColor

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NowPlayingSheet(
    visible: Boolean,
    playbackState: PlaybackState,
    positionMs: () -> Long,
    sleepTimerState: SleepTimerState,
    smartModeState: SmartModeState,
    lyricsState: LyricsState,
    playerFeatureSettings: PlayerFeatureSettings,
    trackAudioInfo: TrackAudioInfo?,
    radioNowPlaying: RadioNowPlaying? = null,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleSmartMode: () -> Unit,
    onLyricsEnabledChange: (Boolean) -> Unit,
    onRetryLyrics: () -> Unit,
    onSearchLyrics: (String, String) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onRenameSong: ((com.offline.musicplayer.data.model.Song) -> Unit)? = null,
    onDeleteSong: ((com.offline.musicplayer.data.model.Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentSong = playbackState.currentSong
    val currentRadioTrack = radioNowPlaying?.takeIf {
        currentSong?.sourceType == TrackSourceType.RADIO_STREAM &&
            it.stationId == currentSong.sourceId
    }
    val context = LocalContext.current

    AnimatedVisibility(
        visible = visible && currentSong != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        if (currentSong == null) return@AnimatedVisibility

        // Dynamic album art color extraction
        val extractedColors = rememberDominantColor(
            context = context,
            artworkUri = currentSong.albumArtUri
        )

        var showRadioSearchBrowser by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AuraBackground
        ) {
            // Ambient Morphing Fluid Gradient Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                extractedColors.dominant.copy(alpha = 0.50f),
                                extractedColors.surface.copy(alpha = 0.85f),
                                AuraBackground,
                                AuraBackground
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top App Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Collapse",
                                tint = AuraTextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        DevilPlayerLogo(Modifier.size(34.dp))

                        Row {
                            IconButton(
                                enabled = smartModeState.isSupported && currentSong.sourceType == TrackSourceType.LOCAL,
                                onClick = onToggleSmartMode
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (smartModeState.isEnabled) {
                                        "Turn Smart Loudness off"
                                    } else if (smartModeState.isSupported) {
                                        "Turn Smart Loudness on"
                                    } else {
                                        "Smart Loudness requires Android 9 or later"
                                    },
                                    tint = if (smartModeState.isEnabled) {
                                        AuraSecondary
                                    } else {
                                        AuraTextPrimary
                                    }
                                )
                            }

                            IconButton(onClick = onOpenEqualizer) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = "Audio settings",
                                    tint = AuraTextPrimary
                                )
                            }

                            IconButton(onClick = onOpenSleepTimer) {
                                BadgedBox(
                                    badge = {
                                        if (sleepTimerState.isActive) {
                                            Badge(
                                                containerColor = AuraSecondary,
                                                contentColor = AuraBackground
                                            ) {
                                                Text(
                                                    text = if (sleepTimerState.isEndOfTrackMode) "•" else "${sleepTimerState.remainingMillis / 60000}m",
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bedtime,
                                        contentDescription = "Sleep Timer",
                                        tint = if (sleepTimerState.isActive) AuraSecondary else AuraTextPrimary
                                    )
                                }
                            }

                            if (onRenameSong != null && currentSong.sourceType == TrackSourceType.LOCAL) {
                                IconButton(onClick = { onRenameSong(currentSong) }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename file",
                                        tint = AuraTextPrimary
                                    )
                                }
                            }
                            if (onDeleteSong != null && currentSong.sourceType == TrackSourceType.LOCAL) {
                                IconButton(onClick = { onDeleteSong(currentSong) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Track",
                                        tint = Color(0xFFFF5252)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (!currentSong.isLiveStream) Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AuraCard.copy(alpha = 0.86f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live Lyrics (Online)",
                                color = AuraTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = lyricsState.resolvedTitle?.let { title ->
                                    "Search used: $title · ${lyricsState.resolvedArtist.orEmpty()}"
                                } ?: "For this track only · lyrics aren’t saved",
                                color = AuraTextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = lyricsState.isEnabled,
                            onCheckedChange = onLyricsEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AuraPrimary,
                                checkedTrackColor = AuraPrimary.copy(alpha = 0.4f),
                                uncheckedThumbColor = AuraTextSecondary,
                                uncheckedTrackColor = AuraSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (!lyricsState.isEnabled || currentSong.isLiveStream) {
                        InteractiveArtworkShowcase(
                            artworkUri = currentSong.albumArtUri,
                            title = currentSong.title,
                            isPlaying = playbackState.isPlaying,
                            accentColor = extractedColors.dominant,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        AudioWaveformVisualizer(
                            isPlaying = playbackState.isPlaying,
                            primaryColor = extractedColors.dominant,
                            secondaryColor = extractedColors.secondary,
                            barCount = 28,
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(26.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Title & Artist Info with Marquee Scrolling
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentSong.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = AuraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.basicMarquee()
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${currentSong.artist} • ${currentSong.album}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AuraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )

                        if (currentRadioTrack != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuraCard.copy(alpha = 0.8f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "NOW PLAYING",
                                    color = AuraSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = currentRadioTrack.title,
                                    color = AuraTextPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                currentRadioTrack.artist?.let { artist ->
                                    Text(
                                        text = artist,
                                        color = AuraTextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = { showRadioSearchBrowser = true }
                                ) {
                                    Text("Search free download on Google")
                                }
                            }
                        }

                    if (currentSong.sourceType == TrackSourceType.RADIO_STREAM) {
                        val stream = currentSong.streamInfo
                        Text(
                            text = listOfNotNull(
                                "LIVE",
                                stream?.countryCode?.takeIf(String::isNotBlank),
                                stream?.bitrate?.takeIf { it > 0 }?.let { "${it} kbps" },
                                stream?.codec?.takeIf(String::isNotBlank)?.uppercase()
                            ).joinToString(" · "),
                            color = Color(0xFFFF5252),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        AudioMetadataBadges(trackAudioInfo)
                    }
                    }

                    if (lyricsState.isEnabled && !currentSong.isLiveStream) {
                        LiveLyricsPanel(
                            lyricsState = lyricsState,
                            fontSizeSp = playerFeatureSettings.lyricsFontSizeSp,
                            offsetMs = playerFeatureSettings.lyricsOffsetMs,
                            trackTitle = currentSong.title,
                            trackArtist = currentSong.artist,
                            positionMs = positionMs,
                            onSeek = onSeek,
                            onRetry = onRetryLyrics,
                            onSearchLyrics = onSearchLyrics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Interactive Seek Bar
                    if (currentSong.isLiveStream) {
                        Text(
                            text = "● LIVE",
                            color = Color(0xFFFF5252),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        PlaybackSeekBar(
                            positionMs = positionMs,
                            durationMs = playbackState.duration,
                            accentColor = extractedColors.dominant,
                            onSeek = onSeek
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Animated Playback Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // True Non-Repeating Shuffle Toggle
                        BouncingIconButton(onClick = onToggleShuffle) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (playbackState.isShuffleEnabled) AuraSecondary.copy(alpha = 0.2f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (playbackState.isShuffleEnabled) AuraSecondary else AuraTextTertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Skip Previous
                        BouncingIconButton(onClick = onSkipPrevious) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = AuraTextPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        // Play / Pause FAB with Pulsing Breathing Glow Ring
                        Box(
                            modifier = Modifier.size(76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (playbackState.isPlaying) {
                                PulseRing(color = extractedColors.dominant)
                            }

                            BouncingIconButton(onClick = onPlayPause) {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .background(extractedColors.dominant)
                                        .shadow(16.dp, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                        tint = AuraTextPrimary,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                            }
                        }

                        // Skip Next
                        BouncingIconButton(onClick = onSkipNext) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = AuraTextPrimary,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        // Repeat Mode Toggle
                        BouncingIconButton(onClick = onCycleRepeat) {
                            val isRepeatActive = playbackState.repeatMode != Player.REPEAT_MODE_OFF
                            val repeatIcon = when (playbackState.repeatMode) {
                                Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (isRepeatActive) AuraSecondary.copy(alpha = 0.2f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = repeatIcon,
                                    contentDescription = "Repeat",
                                    tint = if (isRepeatActive) AuraSecondary else AuraTextTertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

            }
        }
        if (showRadioSearchBrowser && currentRadioTrack != null) {
            GoogleSearchDialog(
                query = radioFreeDownloadSearchQuery(currentRadioTrack),
                onDismiss = { showRadioSearchBrowser = false }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioMetadataBadges(info: TrackAudioInfo?) {
    val labels = buildList {
        info?.format?.let(::add)
        if (info?.isLossless == true) add("Lossless")
        info?.bitrateKbps?.let { add("$it kbps") }
        info?.sampleRateHz?.let { rate ->
            val khz = rate / 1000f
            add(if (khz % 1f == 0f) "${khz.toInt()} kHz" else "${"%.1f".format(khz)} kHz")
        }
        info?.bitsPerSample?.let { add("${it}-bit") }
    }.distinct()
    if (labels.isEmpty()) return

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEach { label ->
            Surface(
                color = AuraCard.copy(alpha = 0.9f),
                contentColor = AuraTextSecondary,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(horizontal = 3.dp)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveLyricsPanel(
    lyricsState: LyricsState,
    trackTitle: String,
    trackArtist: String,
    positionMs: () -> Long,
    fontSizeSp: Int,
    offsetMs: Int,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onSearchLyrics: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lyricsLines = lyricsState.lines.orEmpty()
    val hasLyrics = lyricsLines.isNotEmpty() || !lyricsState.plainLyrics.isNullOrBlank()
    val lyricPositionMs: () -> Long = { (positionMs() - offsetMs).coerceAtLeast(0L) }
    var showSearchDialog by remember(lyricsState.trackId) { mutableStateOf(false) }
    var showBrowser by remember(lyricsState.trackId) { mutableStateOf(false) }
    var searchTitle by remember(lyricsState.trackId, trackTitle) { mutableStateOf(trackTitle) }
    var searchArtist by remember(lyricsState.trackId, trackArtist) { mutableStateOf(trackArtist) }
    // Derived, so the panel only recomposes when the active line changes, not every tick.
    val activeLineIndex by remember(lyricsLines, offsetMs, positionMs) {
        derivedStateOf {
            val position = lyricPositionMs()
            lyricsLines.indexOfFirst {
                position >= it.startMs && position < it.endMs
            }.takeIf { it >= 0 } ?: lyricsLines.indexOfLast {
                it.startMs <= position
            }.coerceAtLeast(0)
        }
    }

    LaunchedEffect(activeLineIndex, lyricsState.trackId, lyricsLines.size) {
        if (lyricsLines.isNotEmpty()) {
            listState.animateScrollToItem(activeLineIndex)
        }
    }

    if (!hasLyrics) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                if (lyricsState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = AuraSecondary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Finding lyrics", color = AuraTextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Checking LRCLIB for this track…",
                        color = AuraTextSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "No lyrics found",
                        color = AuraTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        lyricsState.message ?: "Try another search or check again later.",
                        color = AuraTextSecondary,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!lyricsState.isLoading) {
                    Row {
                        TextButton(onClick = onRetry) {
                            Text("Try again", color = AuraSecondary)
                        }
                        TextButton(onClick = { showSearchDialog = true }) {
                            Text("Edit search", color = AuraSecondary)
                        }
                    }
                    TextButton(onClick = { showBrowser = true }) {
                        Text("Search the web", color = AuraSecondary)
                    }
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when (lyricsState.timing) {
                        LyricsTiming.WORD_SYNCED -> "Word-synced lyrics"
                        LyricsTiming.LINE_SYNCED -> "Line-synced · word timing estimated"
                        LyricsTiming.PLAIN -> "Plain lyrics · not synced"
                        null -> "Lyrics"
                    },
                    color = AuraTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                TextButton(onClick = { showSearchDialog = true }) {
                    Text("Wrong lyrics? Edit search", color = AuraSecondary)
                }
            }
            TextButton(
                onClick = { showBrowser = true },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Search the web", color = AuraSecondary)
            }
            if (lyricsState.timing == LyricsTiming.PLAIN) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Text(
                            text = lyricsState.plainLyrics.orEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = AuraTextPrimary,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSizeSp.sp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    itemsIndexed(lyricsLines) { index, line ->
                        val lineIsActive = index == activeLineIndex
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeek((line.startMs + offsetMs).coerceAtLeast(0L)) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            line.words.forEach { word ->
                                // Each word recomposes only when it changes between upcoming/active/sung.
                                val progress by remember(word, offsetMs, positionMs) {
                                    derivedStateOf {
                                        val position = lyricPositionMs()
                                        when {
                                            position >= word.endMs -> 2
                                            position >= word.startMs -> 1
                                            else -> 0
                                        }
                                    }
                                }
                                val isSung = progress == 2
                                val isActive = progress == 1
                                val scale by animateFloatAsState(
                                    targetValue = if (isActive) 1.08f else 1f,
                                    label = "lyric_word_scale"
                                )
                                Text(
                                    text = word.text,
                                    modifier = Modifier
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .clickable { onSeek((word.startMs + offsetMs).coerceAtLeast(0L)) }
                                        .padding(horizontal = 2.dp, vertical = 2.dp),
                                    color = when {
                                        isActive -> AuraSecondary
                                        isSung -> AuraTextPrimary
                                        lineIsActive -> AuraTextSecondary
                                        else -> AuraTextTertiary
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = fontSizeSp.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                        shadow = if (isActive) {
                                            Shadow(color = AuraSecondary.copy(alpha = 0.65f), offset = Offset.Zero, blurRadius = 14f)
                                        } else {
                                            null
                                        }
                                    )
                                )
                            }
                        }
                    }

                }
            }
        }
    }

    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Search lyrics", color = AuraTextPrimary) },
            text = {
                Column {
                    Text(
                        "Correct the track or artist name to search LRCLIB again.",
                        color = AuraTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = searchTitle,
                        onValueChange = { searchTitle = it },
                        label = { Text("Track title") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchArtist,
                        onValueChange = { searchArtist = it },
                        label = { Text("Artist") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = searchTitle.isNotBlank(),
                    onClick = {
                        onSearchLyrics(searchTitle.trim(), searchArtist.trim())
                        showSearchDialog = false
                    }
                ) {
                    Text("Search", color = AuraSecondary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Text("Cancel", color = AuraTextSecondary)
                }
            },
            containerColor = AuraBackground
        )
    }

    if (showBrowser) {
        GoogleSearchDialog(
            query = listOf("lyrics", searchTitle.trim(), searchArtist.trim())
                .filter(String::isNotBlank)
                .joinToString(" "),
            onDismiss = { showBrowser = false }
        )
    }
}

@Composable
fun GoogleSearchDialog(
    query: String,
    onDismiss: () -> Unit
) {
    val cookieManager = remember {
        CookieManager.getInstance().apply { setAcceptCookie(true) }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(0) }
    val searchUri = remember(query) {
        Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .path("search")
            .appendQueryParameter("q", query)
            .build()
            .toString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = RoundedCornerShape(18.dp),
            color = AuraBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("GOOGLE SEARCH", color = AuraTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "Google receives your search terms. Web pages may use cookies; search results are not saved in the player.",
                            color = AuraTextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = AuraSecondary)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = webView?.canGoBack() == true,
                        onClick = { webView?.goBack() }
                    ) {
                        Text("Back")
                    }
                    TextButton(
                        enabled = webView?.canGoForward() == true,
                        onClick = { webView?.goForward() }
                    ) {
                        Text("Forward")
                    }
                    TextButton(onClick = { webView?.reload() }) {
                        Text("Reload")
                    }
                }
                LinearProgressIndicator(
                    progress = { pageProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AuraSecondary,
                    trackColor = AuraSurfaceVariant
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            cookieManager.setAcceptCookie(true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.setSaveFormData(false)
                            settings.mediaPlaybackRequiresUserGesture = true
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            clearCache(true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    pageProgress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean = request.url.scheme !in setOf("http", "https")
                            }
                            webView = this
                            loadUrl(searchUri)
                        }
                    },
                    update = {}
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                clearCache(true)
                clearFormData()
                removeAllViews()
                destroy()
            }
            cookieManager.flush()
        }
    }
}

/** Seek bar and time labels; the only part of the sheet that recomposes on position ticks. */
@Composable
private fun PlaybackSeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    accentColor: Color,
    onSeek: (Long) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)
    val current = if (isDragging) dragPositionMs.toLong() else positionMs()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = current.coerceIn(0L, duration).toFloat(),
            onValueChange = {
                isDragging = true
                dragPositionMs = it
            },
            onValueChangeFinished = {
                onSeek(dragPositionMs.toLong())
                isDragging = false
            },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = AuraSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(current),
                style = MaterialTheme.typography.labelMedium,
                color = AuraTextTertiary
            )
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelMedium,
                color = AuraTextTertiary
            )
        }
    }
}

/** Breathing ring behind the play button; animated values are read in the draw layer only. */
@Composable
private fun PulseRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "fab_pulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer {
                scaleX = ringScale
                scaleY = ringScale
                alpha = ringAlpha
            }
            .clip(CircleShape)
            .background(color)
    )
}
