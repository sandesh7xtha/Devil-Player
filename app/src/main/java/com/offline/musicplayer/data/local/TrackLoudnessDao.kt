package com.offline.musicplayer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.offline.musicplayer.data.model.TrackLoudnessEntity
import com.offline.musicplayer.data.model.RenamedTrackEntity

@Dao
interface TrackLoudnessDao {
    @Query("SELECT * FROM track_loudness WHERE trackId = :trackId")
    suspend fun get(trackId: Long): TrackLoudnessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackLoudnessEntity)

    @Query("SELECT * FROM renamed_tracks")
    suspend fun getRenamedTracks(): List<RenamedTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRenamedTrack(entity: RenamedTrackEntity)

    @Query("DELETE FROM renamed_tracks WHERE contentUri = :contentUri")
    suspend fun deleteRenamedTrack(contentUri: String)
}
