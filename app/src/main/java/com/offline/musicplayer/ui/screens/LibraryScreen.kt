package com.offline.musicplayer.ui.screens

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.offline.musicplayer.data.model.Album
import com.offline.musicplayer.data.model.Artist
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.ui.theme.AuraBackground
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary
import com.offline.musicplayer.ui.theme.AuraTextTertiary
import com.offline.musicplayer.ui.viewmodel.MusicViewModel

@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val hasPermission by viewModel.hasStoragePermission.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val libraryLoadError by viewModel.libraryLoadError.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val favoriteRadioStations by viewModel.favoriteRadioStations.collectAsStateWithLifecycle()
    val radioNowPlaying by viewModel.radioNowPlaying.collectAsStateWithLifecycle()
    val openNowPlayingRequest by viewModel.openNowPlayingRequest.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val smartModeState by viewModel.smartModeState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val playerFeatureSettings by viewModel.playerFeatureSettings.collectAsStateWithLifecycle()
    val trackAudioInfo by viewModel.trackAudioInfo.collectAsStateWithLifecycle()
    val capsuleSettings by viewModel.capsuleSettings.collectAsStateWithLifecycle()
    val setupPromptState by viewModel.setupPromptState.collectAsStateWithLifecycle()
    // Kept as a State and read lazily so position ticks don't recompose this screen.
    val playbackPosition = viewModel.playbackPosition.collectAsStateWithLifecycle()
    val positionMs: () -> Long = remember(playbackPosition) { { playbackPosition.value } }

    val filteredSongs by viewModel.filteredSongs.collectAsStateWithLifecycle()
    val filteredAlbums by viewModel.filteredAlbums.collectAsStateWithLifecycle()
    val filteredArtists by viewModel.filteredArtists.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
    val selectedArtist by viewModel.selectedArtist.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val songPendingDelete by viewModel.songPendingDelete.collectAsStateWithLifecycle()
    val deleteToastMessage by viewModel.deleteToastMessage.collectAsStateWithLifecycle()
    val renameToastMessage by viewModel.renameToastMessage.collectAsStateWithLifecycle()
    var songToDeleteConfirm by remember { mutableStateOf<Song?>(null) }
    var songToRename by remember { mutableStateOf<Song?>(null) }
    var renameBaseName by remember { mutableStateOf("") }
    var googleRenameQuery by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var showOverlaySetupDialog by remember { mutableStateOf(false) }
    var showBatteryGuidanceDialog by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    val showBatteryGuidance = {
        if (setupPromptState?.batteryGuidanceShown == false) {
            viewModel.markBatteryGuidanceShown()
            showBatteryGuidanceDialog = true
        }
    }

    val deleteIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            songPendingDelete?.let { song ->
                viewModel.onSongDeletedSuccessfully(song)
            }
        } else {
            viewModel.songPendingDelete.value = null
        }
    }

    val renameIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.completePendingSongRename()
        } else {
            viewModel.songPendingRename.value = null
        }
    }

    val requestRenameSong: (Song) -> Unit = { song ->
        googleRenameQuery = null
        val title = song.title
        val artist = song.artist
        renameBaseName = if (
            artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true)
        ) "$artist - $title" else title
        songToRename = song
    }

    val completeFloatingSetup = {
        viewModel.showFloatingPlayer()
        showBatteryGuidance()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Notifications are off, so media controls won't appear in the shade.",
                Toast.LENGTH_LONG
            ).show()
        }
        completeFloatingSetup()
    }

    val showFloatingPlayerWithNotifications = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            completeFloatingSetup()
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            showFloatingPlayerWithNotifications()
        } else {
            Toast.makeText(
                context,
                "Allow display over other apps to use the floating player.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(deleteToastMessage) {
        deleteToastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearDeleteToastMessage()
        }
    }

    LaunchedEffect(renameToastMessage) {
        renameToastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearRenameToastMessage()
        }
    }

    LaunchedEffect(smartModeState.errorMessage) {
        smartModeState.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearSmartModeError()
        }
    }

    LaunchedEffect(playbackState.isPlaying, hasPermission, setupPromptState, capsuleSettings.enabled) {
        if (
            hasPermission &&
            playbackState.isPlaying &&
            capsuleSettings.enabled &&
            setupPromptState?.overlaySetupPrompted == false &&
            !Settings.canDrawOverlays(context)
        ) {
            viewModel.markOverlaySetupPrompted()
            showOverlaySetupDialog = true
        }
    }

    var isNowPlayingOpen by remember { mutableStateOf(false) }
    var isEqualizerOpen by remember { mutableStateOf(false) }
    var isSleepTimerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(openNowPlayingRequest) {
        if (openNowPlayingRequest > 0) isNowPlayingOpen = true
    }

    if (!hasPermission) {
        PermissionRequiredScreen(
            onPermissionGranted = { viewModel.onPermissionGranted() }
        )
        return
    }

    if (showOverlaySetupDialog) {
        SetupStepsDialog(
            title = "Turn on the floating capsule",
            intro = "Control music from any app with a capsule around the camera cutout.",
            steps = listOf(
                "Allow Display over other apps on the next screen.",
                "Allow notifications so media controls stay available.",
                "If your phone also shows its own media capsule, turn that off for Devil Play."
            ),
            confirmLabel = "Continue",
            dismissLabel = "Not now",
            onConfirm = {
                showOverlaySetupDialog = false
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            onDismiss = { showOverlaySetupDialog = false }
        )
    }

    if (showBatteryGuidanceDialog) {
        SetupStepsDialog(
            title = "Keep the capsule running",
            intro = "Some phones stop background apps to save battery.",
            steps = listOf(
                "Set Battery usage for Devil Play to Unrestricted or Allow background activity.",
                "Enable Auto launch if your phone has it."
            ),
            confirmLabel = "Open battery settings",
            dismissLabel = "Later",
            onConfirm = {
                showBatteryGuidanceDialog = false
                openBatterySettings(context)
            },
            onDismiss = { showBatteryGuidanceDialog = false }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AuraBackground,
        bottomBar = {
            MiniPlayer(
                playbackState = playbackState,
                radioNowPlaying = radioNowPlaying,
                positionMs = positionMs,
                onPlayPause = { viewModel.togglePlayPause() },
                onSkipNext = { viewModel.skipNext() },
                onClick = { isNowPlayingOpen = true }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header: App Brand & Top Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DevilPlayerLogo(Modifier.size(40.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Devil Play",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = AuraTextPrimary
                            )
                            Text(
                                text = when {
                                    smartModeState.isEnabled ->
                                        "Smart Loudness on · -1 dB peak target"
                                    !smartModeState.isSupported ->
                                        "Smart Loudness requires Android 9+"
                                    else -> "Pure Offline Hi-Fi Audio"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = AuraTextSecondary
                            )
                        }
                    }

                    Row {
                        BouncingIconButton(onClick = { isEqualizerOpen = true }) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (equalizerState.isEnabled) AuraPrimary.copy(alpha = 0.2f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = "Equalizer",
                                    tint = if (equalizerState.isEnabled) AuraSecondary else AuraTextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        BouncingIconButton(onClick = { isSleepTimerOpen = true }) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (sleepTimerState.isActive) AuraSecondary.copy(alpha = 0.2f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
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
                                        tint = if (sleepTimerState.isActive) AuraSecondary else AuraTextSecondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        BouncingIconButton(onClick = { isSettingsOpen = true }) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = AuraTextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // If viewing Album or Artist drilldown
                if (selectedAlbum != null) {
                    AlbumDetailView(
                        album = selectedAlbum!!,
                        currentSongId = playbackState.currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onBack = { viewModel.selectedAlbum.value = null },
                        onPlaySong = { song -> viewModel.playSong(song, selectedAlbum!!.songs) },
                        onPlayAll = { viewModel.playAlbum(selectedAlbum!!) },
                        onDeleteSong = { songToDeleteConfirm = it },
                        onRenameSong = requestRenameSong
                    )
                } else if (selectedArtist != null) {
                    ArtistDetailView(
                        artist = selectedArtist!!,
                        currentSongId = playbackState.currentSong?.id,
                        isPlaying = playbackState.isPlaying,
                        onBack = { viewModel.selectedArtist.value = null },
                        onPlaySong = { song -> viewModel.playSong(song, selectedArtist!!.songs) },
                        onPlayAll = { viewModel.playArtist(selectedArtist!!) },
                        onDeleteSong = { songToDeleteConfirm = it },
                        onRenameSong = requestRenameSong
                    )
                } else {
                    // Tabs stay pinned under the header; each tab's search sits below them
                    // at the same spot, so switching tabs never moves the tab row.
                    LibraryTabs(
                        selectedTabIndex = selectedTab,
                        onTabSelected = { viewModel.selectedTab.value = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedTab < 3) {
                        SearchBarField(
                            query = searchQuery,
                            onQueryChange = { viewModel.searchQuery.value = it },
                            onClear = { viewModel.searchQuery.value = "" }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    when (selectedTab) {
                        3 -> {
                            val radioFeed by viewModel.radioFeed.collectAsStateWithLifecycle()
                            RadioScreen(
                                feed = radioFeed,
                                favoriteStations = favoriteRadioStations,
                                currentSong = playbackState.currentSong,
                                radioNowPlaying = radioNowPlaying,
                                isPlaying = playbackState.isPlaying,
                                onSearch = viewModel::searchRadio,
                                onPlay = { station, queue -> viewModel.playRadioStation(station, queue) },
                                onToggleFavorite = viewModel::toggleFavoriteRadioStation,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        4 -> {
                            val jamendoFeed by viewModel.jamendoFeed.collectAsStateWithLifecycle()
                            JamendoScreen(
                                feed = jamendoFeed,
                                isConfigured = viewModel.isJamendoConfigured,
                                currentSong = playbackState.currentSong,
                                isPlaying = playbackState.isPlaying,
                                onSearch = viewModel::loadJamendoTracks,
                                onPlay = { viewModel.playJamendoTrack(it, jamendoFeed.items) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        else -> if (isLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            DevilPlayerLogo(Modifier.size(72.dp))
                            Spacer(modifier = Modifier.height(20.dp))
                            CircularProgressIndicator(color = AuraPrimary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Scanning your music...", color = AuraTextSecondary)
                        }
                    } else {
                        if (
                            libraryLoadError != null &&
                            filteredSongs.isEmpty() &&
                            filteredAlbums.isEmpty() &&
                            filteredArtists.isEmpty()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = libraryLoadError.orEmpty(),
                                    color = AuraTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                TextButton(onClick = { viewModel.loadLibrary() }) {
                                    Text("Try again", color = AuraSecondary)
                                }
                            }
                        } else {
                            when (selectedTab) {
                                0 -> SongsTabContent(
                                    songs = filteredSongs,
                                    currentSongId = playbackState.currentSong?.id,
                                    isPlaying = playbackState.isPlaying,
                                    onSongClick = { song -> viewModel.playSong(song, filteredSongs) },
                                    onDeleteSong = { songToDeleteConfirm = it },
                                    onRenameSong = requestRenameSong,
                                    searchQuery = searchQuery,
                                    onRefresh = { viewModel.loadLibrary() }
                                )
                                1 -> AlbumsTabContent(
                                    albums = filteredAlbums,
                                    onAlbumClick = { album -> viewModel.selectedAlbum.value = album }
                                )
                                2 -> ArtistsTabContent(
                                    artists = filteredArtists,
                                    onArtistClick = { artist -> viewModel.selectedArtist.value = artist }
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    // Permanent Delete Confirmation Dialog
    if (songToDeleteConfirm != null) {
        val targetSong = songToDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { songToDeleteConfirm = null },
            title = {
                Text(
                    text = "Permanent Delete",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AuraTextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${targetSong.title}\" from your device? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val song = targetSong
                        songToDeleteConfirm = null
                        viewModel.initiateDeleteSong(song) { intentSender ->
                            deleteIntentSenderLauncher.launch(
                                IntentSenderRequest.Builder(intentSender).build()
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDeleteConfirm = null }) {
                    Text("Cancel", color = AuraTextSecondary)
                }
            },
            containerColor = AuraCard
        )
    }

    if (songToRename != null) {
        val targetSong = songToRename!!
        AlertDialog(
            onDismissRequest = {
                songToRename = null
                googleRenameQuery = null
            },
            title = {
                Text(
                    text = "Rename audio file",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AuraTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Choose a filename. The original file extension will be preserved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameBaseName,
                        onValueChange = { renameBaseName = it },
                        label = { Text("Filename") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                val filename = viewModel.getAudioDisplayName(targetSong)
                                googleRenameQuery =
                                    "\"$filename\" help identify the correct song title and artist. Suggest an LRCLIB-compatible filename in this exact format: Artist - Title. Return only the artist and title, without album, year, or file extension."
                            }
                        }
                    ) {
                        Text("Suggest a name with Google", color = AuraSecondary)
                    }
                    Text(
                        text = "Ask Google to identify the track and return its LRCLIB filename as Artist - Title. Copy the suggestion into the filename field.",
                        color = AuraTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameBaseName.isNotBlank(),
                    onClick = {
                        val song = targetSong
                        val baseName = renameBaseName.trim()
                        songToRename = null
                        googleRenameQuery = null
                        viewModel.initiateRenameSong(song, baseName) { intentSender ->
                            renameIntentSenderLauncher.launch(
                                IntentSenderRequest.Builder(intentSender).build()
                            )
                        }
                    }
                ) {
                    Text("Rename", color = AuraSecondary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        songToRename = null
                        googleRenameQuery = null
                    }
                ) {
                    Text("Cancel", color = AuraTextSecondary)
                }
            },
            containerColor = AuraCard
        )
    }

    googleRenameQuery?.let { query ->
        GoogleSearchDialog(
            query = query,
            onDismiss = { googleRenameQuery = null }
        )
    }

    // Now Playing Full-Screen Sheet
    NowPlayingSheet(
        visible = isNowPlayingOpen,
        playbackState = playbackState,
        positionMs = positionMs,
        sleepTimerState = sleepTimerState,
        smartModeState = smartModeState,
        lyricsState = lyricsState,
        playerFeatureSettings = playerFeatureSettings,
        trackAudioInfo = trackAudioInfo,
        radioNowPlaying = radioNowPlaying,
        onDismiss = { isNowPlayingOpen = false },
        onPlayPause = { viewModel.togglePlayPause() },
        onSeek = { pos -> viewModel.seekTo(pos) },
        onSkipNext = { viewModel.skipNext() },
        onSkipPrevious = { viewModel.skipPrevious() },
        onToggleShuffle = { viewModel.toggleShuffle() },
        onCycleRepeat = { viewModel.cycleRepeatMode() },
        onToggleSmartMode = {
            viewModel.setSmartModeEnabled(!smartModeState.isEnabled)
        },
        onLyricsEnabledChange = { viewModel.setLyricsEnabled(it) },
        onRetryLyrics = { viewModel.retryLyrics() },
        onSearchLyrics = { title, artist -> viewModel.searchLyrics(title, artist) },
        onOpenEqualizer = { isEqualizerOpen = true },
        onOpenSleepTimer = { isSleepTimerOpen = true },
        onRenameSong = requestRenameSong,
        onDeleteSong = { song -> songToDeleteConfirm = song }
    )

    AnimatedVisibility(
        visible = isSettingsOpen,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        val settingsActions = remember(viewModel) {
            SettingsActions(
                onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
                onPitchLockChange = viewModel::setPitchLocked,
                onCrossfadeSecondsChange = viewModel::setCrossfadeSeconds,
                onSmartModeEnabledChange = viewModel::setSmartModeEnabled,
                onOpenEqualizer = { isEqualizerOpen = true },
                onLyricsEnabledChange = viewModel::setLyricsEnabled,
                onLyricsFontSizeChange = viewModel::setLyricsFontSizeSp,
                onLyricsOffsetChange = viewModel::setLyricsOffsetMs,
                onCapsuleEnabledChange = viewModel::setCapsuleEnabled,
                onCapsuleSizeChange = viewModel::setCapsuleSize,
                onCapsulePositionChange = viewModel::setCapsulePosition,
                onCapsuleSwipeGesturesChange = viewModel::setCapsuleSwipeGestures,
                onRescanLibrary = viewModel::loadLibrary,
                onResetSettings = viewModel::resetSettings
            )
        }
        SettingsScreen(
            featureSettings = playerFeatureSettings,
            capsuleSettings = capsuleSettings,
            smartModeState = smartModeState,
            equalizerState = equalizerState,
            lyricsEnabled = lyricsState.isEnabled,
            actions = settingsActions,
            onBack = { isSettingsOpen = false }
        )
    }

    // Equalizer Sheet
    if (isEqualizerOpen) {
        EqualizerSheet(
            equalizerState = equalizerState,
            onEnabledChange = { viewModel.setEqualizerEnabled(it) },
            onPresetSelected = { viewModel.setEqualizerPreset(it) },
            onBandLevelChange = { index, level -> viewModel.setEqualizerBandLevel(index, level) },
            onDismiss = { isEqualizerOpen = false }
        )
    }

    // Sleep Timer Sheet
    if (isSleepTimerOpen) {
        SleepTimerSheet(
            sleepTimerState = sleepTimerState,
            onStartDuration = { mins -> viewModel.startSleepTimer(mins) },
            onStartEndOfTrack = { viewModel.startEndOfTrackSleepTimer() },
            onCancel = { viewModel.cancelSleepTimer() },
            onDismiss = { isSleepTimerOpen = false }
        )
    }
}

@Composable
fun SongsTabContent(
    songs: List<Song>,
    currentSongId: Long?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onDeleteSong: (Song) -> Unit,
    onRenameSong: (Song) -> Unit,
    searchQuery: String,
    onRefresh: () -> Unit
) {
    if (songs.isEmpty()) {
        if (searchQuery.isNotBlank()) {
            EmptyLibraryView(message = "No tracks match \"$searchQuery\"")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                DevilPlayerLogo(Modifier.size(76.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Your music library is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = AuraTextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Add audio files to your device, then scan again. Devil Play plays local music and does not download tracks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraTextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRefresh) {
                    Text("Scan music")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    isPlaying = isPlaying,
                    isCurrent = song.id == currentSongId,
                    onClick = { onSongClick(song) },
                    onDelete = { onDeleteSong(song) },
                    onRename = { onRenameSong(song) }
                )
            }
        }
    }
}

@Composable
fun AlbumsTabContent(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyLibraryView(message = "No albums found")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumGridItem(
                    album = album,
                    onClick = { onAlbumClick(album) }
                )
            }
        }
    }
}

@Composable
fun ArtistsTabContent(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit
) {
    if (artists.isEmpty()) {
        EmptyLibraryView(message = "No artists found")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(artists, key = { it.name }) { artist ->
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist) }
                )
            }
        }
    }
}

@Composable
fun AlbumDetailView(
    album: Album,
    currentSongId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onDeleteSong: (Song) -> Unit,
    onRenameSong: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AuraTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleLarge,
                color = AuraTextPrimary,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                artworkUri = album.albumArtUri,
                contentDescription = album.title,
                modifier = Modifier
                    .size(96.dp)
                    .shadow(16.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AuraTextPrimary
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraTextSecondary
                )
                Text(
                    text = "${album.songCount} tracks",
                    style = MaterialTheme.typography.labelMedium,
                    color = AuraTextTertiary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onPlayAll,
                    colors = ButtonDefaults.buttonColors(containerColor = AuraPrimary),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Play All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(album.songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    isPlaying = isPlaying,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlaySong(song) },
                    onDelete = { onDeleteSong(song) },
                    onRename = { onRenameSong(song) }
                )
            }
        }
    }
}

@Composable
fun ArtistDetailView(
    artist: Artist,
    currentSongId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onDeleteSong: (Song) -> Unit,
    onRenameSong: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AuraTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleLarge,
                color = AuraTextPrimary,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(AuraPrimary.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = artist.name.take(1).uppercase(),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = AuraPrimary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AuraTextPrimary
                )
                Text(
                    text = "${artist.songCount} tracks • ${artist.albumCount} albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraTextSecondary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onPlayAll,
                    colors = ButtonDefaults.buttonColors(containerColor = AuraPrimary),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Play All", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(artist.songs, key = { it.id }) { song ->
                SongListItem(
                    song = song,
                    isPlaying = isPlaying,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlaySong(song) },
                    onDelete = { onDeleteSong(song) },
                    onRename = { onRenameSong(song) }
                )
            }
        }
    }
}

@Composable
fun EmptyLibraryView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DevilPlayerLogo(Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = AuraTextSecondary
            )
        }
    }
}

@Composable
private fun SetupStepsDialog(
    title: String,
    intro: String,
    steps: List<String>,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AuraTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(intro, color = AuraTextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(AuraPrimary.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = AuraTextPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(step, color = AuraTextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = AuraSecondary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = AuraTextSecondary) }
        },
        containerColor = AuraCard
    )
}
