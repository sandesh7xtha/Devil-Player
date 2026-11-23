package com.offline.musicplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "renamed_tracks")
data class RenamedTrackEntity(
    @PrimaryKey val contentUri: String,
    val title: String,
    val artist: String
)
