package com.offline.musicplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.offline.musicplayer.data.model.RenamedTrackEntity
import com.offline.musicplayer.data.model.TrackLoudnessEntity

@Database(
    entities = [TrackLoudnessEntity::class, RenamedTrackEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TrackLoudnessDatabase : RoomDatabase() {
    abstract fun trackLoudnessDao(): TrackLoudnessDao

    companion object {
        @Volatile
        private var instance: TrackLoudnessDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `renamed_tracks` " +
                        "(`contentUri` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, PRIMARY KEY(`contentUri`))"
                )
            }
        }

        fun getInstance(context: Context): TrackLoudnessDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackLoudnessDatabase::class.java,
                    "track_loudness.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
