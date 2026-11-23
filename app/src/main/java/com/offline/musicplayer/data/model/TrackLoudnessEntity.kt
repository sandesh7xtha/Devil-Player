package com.offline.musicplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_loudness")
data class TrackLoudnessEntity(
    @PrimaryKey val trackId: Long,
    val peakDb: Float
)
