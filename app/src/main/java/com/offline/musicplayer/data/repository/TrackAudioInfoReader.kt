package com.offline.musicplayer.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.offline.musicplayer.data.model.TrackAudioInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrackAudioInfoReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun read(uri: Uri): TrackAudioInfo = withContext(Dispatchers.IO) {
        val displayName = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else null
                }
        }.getOrNull()
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.uppercase()
        val mimeFromResolver = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()

        var mimeFromMedia: String? = null
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        runCatching {
            MediaMetadataRetriever().useRetriever { retriever ->
                retriever.setDataSource(appContext, uri)
                mimeFromMedia = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull()
                    ?.div(1000)
                    ?.takeIf { it > 0 }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                    bitsPerSample = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                }
            }
        }

        val mime = (mimeFromMedia ?: mimeFromResolver).orEmpty().lowercase()
        val format = when {
            extension != null && extension in knownExtensions -> extension
            mime.contains("flac") -> "FLAC"
            mime.contains("mpeg") -> "MP3"
            mime.contains("mp4") || mime.contains("m4a") -> "M4A"
            mime.contains("wav") || mime.contains("wave") -> "WAV"
            mime.contains("ogg") -> "OGG"
            mime.contains("opus") -> "OPUS"
            mime.contains("aac") -> "AAC"
            mime.contains("wma") -> "WMA"
            else -> null
        }
        val isLossless = format in losslessFormats

        TrackAudioInfo(
            format = format,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitsPerSample = bitsPerSample,
            isLossless = isLossless
        )
    }

    private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T {
        return try {
            block(this)
        } finally {
            release()
        }
    }

    private companion object {
        val knownExtensions = setOf("MP3", "FLAC", "WAV", "WAVE", "OGG", "OPUS", "M4A", "AAC", "ALAC", "APE", "DSF", "DFF", "WMA")
        val losslessFormats = setOf("FLAC", "WAV", "WAVE", "ALAC", "APE", "DSF", "DFF")
    }
}
