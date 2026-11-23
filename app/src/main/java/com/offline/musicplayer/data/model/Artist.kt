package com.offline.musicplayer.data.model

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val songs: List<Song>
)
