package com.offline.musicplayer.data.repository

import android.net.Uri
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.StreamInfo
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.data.remote.RadioBrowserApi
import com.offline.musicplayer.data.remote.RadioStationDto
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class RadioRepository {
    companion object {
        private val mirrors = listOf(
            "https://all.api.radio-browser.info/json/",
            "https://de1.api.radio-browser.info/json/",
            "https://nl1.api.radio-browser.info/json/",
            "https://at1.api.radio-browser.info/json/"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "DevilPlay/1.0")
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()

    private fun api(baseUrl: String) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RadioBrowserApi::class.java)

    suspend fun topStations(): List<Song> = fetch { service -> service.topVoted() }

    suspend fun search(name: String? = null, countryCode: String? = null, tag: String? = null): List<Song> =
        fetch { service -> service.search(name, countryCode, tag) }

    suspend fun recordClick(stationUuid: String) {
        if (stationUuid.isBlank()) return
        runCatching { request { it.clickStation(stationUuid) } }
    }

    private suspend fun fetch(
        call: suspend (RadioBrowserApi) -> Response<List<RadioStationDto>>
    ): List<Song> = request(call).mapNotNull(::toSong)

    private suspend fun <T> request(call: suspend (RadioBrowserApi) -> Response<T>): T {
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            try {
                val response = call(api(mirror))
                if (response.isSuccessful) return response.body() ?: throw IOException("Empty Radio Browser response")
                lastError = IOException("Radio Browser returned HTTP ${response.code()}")
                if (response.code() in 400..499 && response.code() != 429) throw lastError
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Radio Browser is unavailable")
    }

    private fun toSong(station: RadioStationDto): Song? {
        val uuid = station.stationuuid.takeIf(String::isNotBlank) ?: return null
        val streamUrl = station.url_resolved.takeIf(String::isNotBlank)
            ?: station.url.takeIf(String::isNotBlank)
            ?: return null
        val name = station.name.trim().ifBlank { "Unnamed station" }
        val hls = station.hls == 1 || streamUrl.substringBefore('?').endsWith(".m3u8", true)
        val streamInfo = StreamInfo(
            sourceId = uuid,
            countryCode = station.countrycode,
            tags = station.tags,
            codec = station.codec,
            bitrate = station.bitrate,
            favicon = station.favicon,
            isHls = hls
        )
        return Song(
            id = uuid.hashCode().toLong(),
            title = name,
            artist = station.countrycode.ifBlank { "Internet Radio" },
            album = "Live Radio",
            duration = 0L,
            contentUri = Uri.parse(streamUrl),
            albumArtUri = station.favicon.takeIf(String::isNotBlank)?.let(Uri::parse),
            sourceType = TrackSourceType.RADIO_STREAM,
            isLiveStream = true,
            sourceId = uuid,
            streamInfo = streamInfo
        )
    }
}
