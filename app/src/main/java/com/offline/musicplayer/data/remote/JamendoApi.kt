package com.offline.musicplayer.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

data class JamendoResponse<T>(
    @SerializedName("results") val results: List<T> = emptyList()
)

data class JamendoTrackDto(
    val id: String = "",
    val name: String = "",
    @SerializedName("artist_name") val artistName: String = "",
    @SerializedName("album_name") val albumName: String = "",
    val duration: Int = 0,
    val image: String = "",
    val audio: String = ""
)

interface JamendoApi {
    @GET("tracks/")
    suspend fun tracks(
        @Query("client_id") clientId: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 40,
        @Query("order") order: String = "popularity_week",
        @Query("audioformat") audioFormat: String = "mp32",
        @Query("imagesize") imageSize: Int = 300,
        @Query("search") search: String? = null,
        @Query("fuzzytags") fuzzyTags: String? = null
    ): Response<JamendoResponse<JamendoTrackDto>>
}
