package com.offline.musicplayer.data.model

import androidx.media3.common.Player

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val duration: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isBuffering: Boolean = false
)
