package com.offline.musicplayer.data.model

data class RadioNowPlaying(
    val stationId: String,
    val title: String,
    val artist: String? = null,
    val rawTitle: String = title
)
