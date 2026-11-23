package com.offline.musicplayer.data.model

data class RemoteFeedState(
    val items: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
