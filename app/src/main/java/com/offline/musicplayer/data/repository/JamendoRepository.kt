package com.offline.musicplayer.data.repository

import android.net.Uri
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.data.remote.JamendoApi
import com.offline.musicplayer.data.remote.JamendoTrackDto
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class JamendoRepository(private val clientId: String) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    private val api: JamendoApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "DevilPlay/1.0")
                        .build()
                )
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.jamendo.com/v3.0/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JamendoApi::class.java)
    }

    suspend fun tracks(search: String? = null, genre: String? = null): List<Song> {
        check(isConfigured) { "Jamendo is not configured. Add JAMENDO_CLIENT_ID to local.properties." }
        val response = api.tracks(
            clientId = clientId,
            search = search?.takeIf(String::isNotBlank),
            fuzzyTags = genre?.takeIf { it.isNotBlank() && !it.equals("Trending", true) }
        )
        if (!response.isSuccessful) throw IOException("Jamendo returned HTTP ${response.code()}")
        return response.body()?.results.orEmpty().mapNotNull(::toSong)
    }

    private fun toSong(track: JamendoTrackDto): Song? {
        val id = track.id.toLongOrNull() ?: return null
        if (track.audio.isBlank()) return null
        return Song(
            id = id,
            title = track.name.ifBlank { "Untitled track" },
            artist = track.artistName.ifBlank { "Unknown artist" },
            album = track.albumName.ifBlank { "Jamendo" },
            duration = track.duration.coerceAtLeast(0).toLong() * 1000L,
            contentUri = Uri.parse(track.audio),
            albumArtUri = track.image.takeIf(String::isNotBlank)?.let(Uri::parse),
            sourceType = TrackSourceType.JAMENDO_STREAM,
            isLiveStream = false,
            sourceId = track.id
        )
    }
}
