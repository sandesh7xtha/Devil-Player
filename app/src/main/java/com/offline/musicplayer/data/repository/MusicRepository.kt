package com.offline.musicplayer.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.offline.musicplayer.data.model.Album
import com.offline.musicplayer.data.model.Artist
import com.offline.musicplayer.data.local.TrackLoudnessDatabase
import com.offline.musicplayer.data.model.RenamedTrackEntity
import com.offline.musicplayer.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DeleteResult {
    data object Success : DeleteResult()
    data class RequiresPermission(val intentSender: IntentSender) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

sealed class RenameResult {
    data class Success(val displayName: String) : RenameResult()
    data class RequiresPermission(val intentSender: IntentSender) : RenameResult()
    data class Error(val message: String) : RenameResult()
}

class MusicRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val localDao = TrackLoudnessDatabase.getInstance(context).trackLoudnessDao()

    suspend fun getDisplayName(song: Song): String? = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                song.contentUri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameColumn)?.takeIf(String::isNotBlank)
                } else null
            }
        } catch (e: SecurityException) {
            android.util.Log.e("MusicRepository", "Permission denied reading audio filename.", e)
            null
        }
    }

    suspend fun fetchAudioFiles(): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()

        val collectionUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contentResolver.query(
            collectionUri,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Unknown Track"
                    val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Unknown Album"
                    val duration = cursor.getLong(durationColumn)
                    val albumId = cursor.getLong(albumIdColumn)
                    val trackNumber = if (trackColumn != -1) cursor.getInt(trackColumn) else 0

                    // Filter out super short sound effects/ringtones (< 5 seconds)
                    if (duration < 5000) continue

                    val contentUri = ContentUris.withAppendedId(collectionUri, id)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    songList.add(
                        Song(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            contentUri = contentUri,
                            albumArtUri = albumArtUri,
                            albumId = albumId,
                            trackNumber = trackNumber
                        )
                    )
                }
        }
        val renamedTracks = localDao.getRenamedTracks().associateBy { it.contentUri }
        songList.map { song ->
            renamedTracks[song.contentUri.toString()]?.let { renamed ->
                song.copy(title = renamed.title, artist = renamed.artist)
            } ?: song
        }
    }

    /**
     * Permanently deletes a music file from the device storage and MediaStore.
     * Complies with modern Android Scoped Storage (Android 10 - 15).
     */
    suspend fun deleteSong(song: Song): DeleteResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): Requests system deletion confirmation dialog
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(song.contentUri))
                DeleteResult.RequiresPermission(pendingIntent.intentSender)
            } catch (_: Exception) {
                try {
                    val rows = contentResolver.delete(song.contentUri, null, null)
                    if (rows > 0) {
                        localDao.deleteRenamedTrack(song.contentUri.toString())
                        DeleteResult.Success
                    } else DeleteResult.Error("Could not delete track")
                } catch (se: SecurityException) {
                    DeleteResult.Error(se.message ?: "Permission denied")
                }
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // Android 10 (API 29): Scoped storage with RecoverableSecurityException
            try {
                val rows = contentResolver.delete(song.contentUri, null, null)
                if (rows > 0) {
                    localDao.deleteRenamedTrack(song.contentUri.toString())
                    DeleteResult.Success
                } else DeleteResult.Error("Could not delete track")
            } catch (rse: RecoverableSecurityException) {
                DeleteResult.RequiresPermission(rse.userAction.actionIntent.intentSender)
            } catch (e: Exception) {
                DeleteResult.Error(e.message ?: "Delete failed")
            }
        } else {
            // Android 9 and below (API <= 28): Direct MediaStore delete
            try {
                val rows = contentResolver.delete(song.contentUri, null, null)
                if (rows > 0) {
                    localDao.deleteRenamedTrack(song.contentUri.toString())
                    DeleteResult.Success
                } else DeleteResult.Error("Could not delete track")
            } catch (e: Exception) {
                DeleteResult.Error(e.message ?: "Delete failed")
            }
        }
    }

    suspend fun renameSong(
        song: Song,
        requestedBaseName: String,
        requestPermission: Boolean = true
    ): RenameResult = withContext(Dispatchers.IO) {
        val currentName = try {
            contentResolver.query(
                song.contentUri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameColumn)
                } else null
            }
        } catch (e: SecurityException) {
            return@withContext RenameResult.Error(e.message ?: "Permission denied")
        } ?: return@withContext RenameResult.Error("Could not read the current audio filename")

        val extension = currentName.substringAfterLast('.', "")
            .takeIf { currentName.lastIndexOf('.') > 0 }
        var baseName = requestedBaseName.trim()
        if (!extension.isNullOrBlank() &&
            baseName.endsWith(".$extension", ignoreCase = true)
        ) {
            baseName = baseName.dropLast(extension.length + 1).trim()
        }
        if (baseName.isBlank() || baseName == "." || baseName == ".." ||
            baseName.any { it == '/' || it == '\\' || it.code < 32 }
        ) {
            return@withContext RenameResult.Error("Enter a valid filename")
        }
        val newDisplayName = if (extension.isNullOrBlank()) {
            baseName
        } else {
            "$baseName.$extension"
        }
        if (newDisplayName == currentName) {
            return@withContext RenameResult.Success(currentName)
        }

        if (requestPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val request = MediaStore.createWriteRequest(contentResolver, listOf(song.contentUri))
                RenameResult.RequiresPermission(request.intentSender)
            } catch (e: SecurityException) {
                RenameResult.Error(e.message ?: "Permission denied")
            } catch (e: IllegalArgumentException) {
                RenameResult.Error(e.message ?: "Invalid audio file")
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName)
        }
        try {
            val rows = contentResolver.update(song.contentUri, values, null, null)
            if (rows > 0) {
                persistRenamedTrack(song)
                RenameResult.Success(newDisplayName)
            } else RenameResult.Error("Could not rename the audio file")
        } catch (e: RecoverableSecurityException) {
            RenameResult.RequiresPermission(e.userAction.actionIntent.intentSender)
        } catch (e: SecurityException) {
            RenameResult.Error(e.message ?: "Permission denied")
        } catch (e: IllegalArgumentException) {
            RenameResult.Error(e.message ?: "Invalid filename")
        }
    }

    suspend fun clearRenamedTrack(song: Song) = withContext(Dispatchers.IO) {
        localDao.deleteRenamedTrack(song.contentUri.toString())
    }

    private suspend fun persistRenamedTrack(song: Song) {
        localDao.upsertRenamedTrack(
            RenamedTrackEntity(
                contentUri = song.contentUri.toString(),
                title = song.title,
                artist = song.artist
            )
        )
    }

    fun groupIntoAlbums(songs: List<Song>): List<Album> {
        return songs.groupBy { it.albumId }
            .map { (albumId, albumSongs) ->
                val firstSong = albumSongs.first()
                Album(
                    id = albumId,
                    title = firstSong.album,
                    artist = firstSong.artist,
                    albumArtUri = firstSong.albumArtUri,
                    songCount = albumSongs.size,
                    songs = albumSongs.sortedBy { it.trackNumber }
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    fun groupIntoArtists(songs: List<Song>): List<Artist> {
        return songs.groupBy { it.artist }
            .map { (artistName, artistSongs) ->
                val uniqueAlbumCount = artistSongs.map { it.albumId }.distinct().size
                Artist(
                    name = artistName,
                    songCount = artistSongs.size,
                    albumCount = uniqueAlbumCount,
                    songs = artistSongs.sortedBy { it.title.lowercase() }
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
