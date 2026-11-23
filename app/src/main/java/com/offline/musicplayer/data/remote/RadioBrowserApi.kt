package com.offline.musicplayer.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class RadioStationDto(
    val stationuuid: String = "",
    val name: String = "",
    val url: String = "",
    val url_resolved: String = "",
    val favicon: String = "",
    val countrycode: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0
)

interface RadioBrowserApi {
    @GET("stations/topvote")
    suspend fun topVoted(
        @Query("limit") limit: Int = 40,
        @Query("hidebroken") hideBroken: Boolean = true
    ): Response<List<RadioStationDto>>

    @GET("stations/search")
    suspend fun search(
        @Query("name") name: String? = null,
        @Query("countrycode") countryCode: String? = null,
        @Query("tag") tag: String? = null,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("limit") limit: Int = 50
    ): Response<List<RadioStationDto>>

    @GET("url/{stationuuid}")
    suspend fun clickStation(@Path("stationuuid") stationUuid: String): Response<ResponseBody>
}
