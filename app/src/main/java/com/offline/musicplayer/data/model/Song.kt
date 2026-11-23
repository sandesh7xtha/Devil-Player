package com.offline.musicplayer.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

enum class TrackSourceType { LOCAL, RADIO_STREAM, JAMENDO_STREAM }

data class StreamInfo(
    val sourceId: String,
    val countryCode: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val favicon: String = "",
    val isHls: Boolean = false
)

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val contentUri: Uri,
    val albumArtUri: Uri?,
    val albumId: Long = 0L,
    val trackNumber: Int = 0,
    val sourceType: TrackSourceType = TrackSourceType.LOCAL,
    val isLiveStream: Boolean = false,
    val sourceId: String? = null,
    val streamInfo: StreamInfo? = null
) {
    fun toMediaItem(): MediaItem {
        val extraBundle = Bundle().apply {
            putLong(EXTRA_SONG_ID, id)
            putString(EXTRA_TITLE, title)
            putString(EXTRA_ARTIST, artist)
            putString(EXTRA_ALBUM, album)
            putLong(EXTRA_DURATION, duration)
            putString(EXTRA_CONTENT_URI, contentUri.toString())
            putString(EXTRA_ALBUM_ART_URI, albumArtUri?.toString())
            putLong(EXTRA_ALBUM_ID, albumId)
            putString(EXTRA_SOURCE_TYPE, sourceType.name)
            putBoolean(EXTRA_IS_LIVE_STREAM, isLiveStream)
            putString(EXTRA_SOURCE_ID, sourceId)
            streamInfo?.let { info ->
                putString(EXTRA_STREAM_COUNTRY, info.countryCode)
                putString(EXTRA_STREAM_TAGS, info.tags)
                putString(EXTRA_STREAM_CODEC, info.codec)
                putInt(EXTRA_STREAM_BITRATE, info.bitrate)
                putString(EXTRA_STREAM_FAVICON, info.favicon)
                putBoolean(EXTRA_STREAM_HLS, info.isHls)
            }
        }

        val mediaId = when (sourceType) {
            TrackSourceType.LOCAL -> id.toString()
            TrackSourceType.RADIO_STREAM -> "radio:${sourceId ?: id}"
            TrackSourceType.JAMENDO_STREAM -> "jamendo:${sourceId ?: id}"
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(contentUri)
            .apply {
                if (streamInfo?.isHls == true) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    .setExtras(extraBundle)
                    .build()
            )
            .build()
    }

    companion object {
        const val EXTRA_SONG_ID = "extra_song_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ALBUM = "extra_album"
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_CONTENT_URI = "extra_content_uri"
        const val EXTRA_ALBUM_ART_URI = "extra_album_art_uri"
        const val EXTRA_ALBUM_ID = "extra_album_id"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_IS_LIVE_STREAM = "extra_is_live_stream"
        const val EXTRA_SOURCE_ID = "extra_source_id"
        const val EXTRA_STREAM_COUNTRY = "extra_stream_country"
        const val EXTRA_STREAM_TAGS = "extra_stream_tags"
        const val EXTRA_STREAM_CODEC = "extra_stream_codec"
        const val EXTRA_STREAM_BITRATE = "extra_stream_bitrate"
        const val EXTRA_STREAM_FAVICON = "extra_stream_favicon"
        const val EXTRA_STREAM_HLS = "extra_stream_hls"

        fun fromMediaItem(mediaItem: MediaItem): Song {
            val extras = mediaItem.mediaMetadata.extras
            val id = extras?.getLong(EXTRA_SONG_ID) ?: (mediaItem.mediaId.toLongOrNull() ?: 0L)
            val title = extras?.getString(EXTRA_TITLE)
                ?: mediaItem.mediaMetadata.title?.toString()
                ?: "Unknown Title"
            val artist = extras?.getString(EXTRA_ARTIST)
                ?: mediaItem.mediaMetadata.artist?.toString()
                ?: "Unknown Artist"
            val album = extras?.getString(EXTRA_ALBUM)
                ?: mediaItem.mediaMetadata.albumTitle?.toString()
                ?: "Unknown Album"
            val duration = extras?.getLong(EXTRA_DURATION) ?: 0L
            val contentUriString = extras?.getString(EXTRA_CONTENT_URI)
            val contentUri = if (contentUriString != null) Uri.parse(contentUriString)
            else mediaItem.localConfiguration?.uri ?: Uri.EMPTY
            val albumArtUriString = extras?.getString(EXTRA_ALBUM_ART_URI)
            val albumArtUri = if (albumArtUriString != null) Uri.parse(albumArtUriString)
            else mediaItem.mediaMetadata.artworkUri
            val albumId = extras?.getLong(EXTRA_ALBUM_ID) ?: 0L
            val sourceType = extras?.getString(EXTRA_SOURCE_TYPE)
                ?.let { runCatching { TrackSourceType.valueOf(it) }.getOrNull() }
                ?: when {
                    mediaItem.mediaId.startsWith("radio:") -> TrackSourceType.RADIO_STREAM
                    mediaItem.mediaId.startsWith("jamendo:") -> TrackSourceType.JAMENDO_STREAM
                    else -> TrackSourceType.LOCAL
                }
            val sourceId = extras?.getString(EXTRA_SOURCE_ID)
                ?: mediaItem.mediaId.substringAfter(':', "").takeIf {
                    sourceType != TrackSourceType.LOCAL && it.isNotEmpty()
                }
            val streamInfo = if (sourceType == TrackSourceType.RADIO_STREAM) {
                StreamInfo(
                    sourceId = sourceId.orEmpty(),
                    countryCode = extras?.getString(EXTRA_STREAM_COUNTRY).orEmpty(),
                    tags = extras?.getString(EXTRA_STREAM_TAGS).orEmpty(),
                    codec = extras?.getString(EXTRA_STREAM_CODEC).orEmpty(),
                    bitrate = extras?.getInt(EXTRA_STREAM_BITRATE) ?: 0,
                    favicon = extras?.getString(EXTRA_STREAM_FAVICON).orEmpty(),
                    isHls = extras?.getBoolean(EXTRA_STREAM_HLS) ?: false
                )
            } else null

            return Song(
                id = id,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                contentUri = contentUri,
                albumArtUri = albumArtUri,
                albumId = albumId,
                sourceType = sourceType,
                isLiveStream = extras?.getBoolean(EXTRA_IS_LIVE_STREAM)
                    ?: (sourceType == TrackSourceType.RADIO_STREAM),
                sourceId = sourceId,
                streamInfo = streamInfo
            )
        }
    }
}
