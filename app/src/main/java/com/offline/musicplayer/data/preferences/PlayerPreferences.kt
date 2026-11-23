package com.offline.musicplayer.data.preferences

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.StreamInfo
import com.offline.musicplayer.data.model.TrackSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "music_player_settings")

class PlayerPreferences(private val context: Context) {

    companion object {
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val KEY_LAST_PLAYED_SONG_ID = longPreferencesKey("last_played_song_id")
        private val KEY_EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        private val KEY_EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        private val KEY_EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
        private val KEY_SMART_MODE_ENABLED = booleanPreferencesKey("smart_mode_enabled")
        private val KEY_LYRICS_ENABLED = booleanPreferencesKey("lyrics_online_enabled")
        private val KEY_LEGACY_LYRICS_MODE = stringPreferencesKey("lyrics_mode")
        private val KEY_FAVORITE_SONG_IDS = stringSetPreferencesKey("favorite_song_ids")
        private val KEY_FAVORITE_RADIO_STATIONS = stringPreferencesKey("favorite_radio_stations")
        private val KEY_CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        private val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        private val KEY_PITCH_LOCKED = booleanPreferencesKey("playback_pitch_locked")
        private val KEY_LYRICS_FONT_SIZE_SP = intPreferencesKey("lyrics_font_size_sp")
        private val KEY_LYRICS_OFFSET_MS = intPreferencesKey("lyrics_offset_ms")
    }

    val shuffleEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SHUFFLE_ENABLED] ?: false
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHUFFLE_ENABLED] = enabled
        }
    }

    val repeatMode: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_REPEAT_MODE] ?: 0
    }

    suspend fun setRepeatMode(repeatMode: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_REPEAT_MODE] = repeatMode
        }
    }

    val lastPlayedSongId: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_PLAYED_SONG_ID] ?: -1L
    }

    suspend fun setLastPlayedSongId(songId: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_PLAYED_SONG_ID] = songId
        }
    }

    val favoriteSongIds: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_FAVORITE_SONG_IDS] ?: emptySet()
    }

    suspend fun toggleFavoriteSong(songId: String): Boolean {
        var isFavorite = false
        context.dataStore.edit { preferences ->
            val favorites = preferences[KEY_FAVORITE_SONG_IDS].orEmpty().toMutableSet()
            isFavorite = if (favorites.add(songId)) {
                true
            } else {
                favorites.remove(songId)
                false
            }
            preferences[KEY_FAVORITE_SONG_IDS] = favorites
        }
        return isFavorite
    }

    val favoriteRadioStations: Flow<List<Song>> = context.dataStore.data.map { preferences ->
        parseFavoriteRadioStations(preferences[KEY_FAVORITE_RADIO_STATIONS].orEmpty())
    }

    suspend fun toggleFavoriteRadioStation(station: Song): Boolean {
        val stationId = station.sourceId?.takeIf(String::isNotBlank) ?: return false
        var isFavorite = false
        context.dataStore.edit { preferences ->
            val favorites = parseFavoriteRadioStations(
                preferences[KEY_FAVORITE_RADIO_STATIONS].orEmpty()
            ).toMutableList()
            val existingIndex = favorites.indexOfFirst { it.sourceId == stationId }
            if (existingIndex >= 0) {
                favorites.removeAt(existingIndex)
                isFavorite = false
            } else {
                favorites.add(station.copy(isLiveStream = true, sourceType = TrackSourceType.RADIO_STREAM))
                isFavorite = true
            }
            preferences[KEY_FAVORITE_RADIO_STATIONS] = serializeFavoriteRadioStations(favorites)
        }
        return isFavorite
    }

    private fun serializeFavoriteRadioStations(stations: List<Song>): String =
        JSONArray().apply {
            stations.forEach { station ->
                val info = station.streamInfo
                put(JSONObject().apply {
                    put("id", station.sourceId.orEmpty())
                    put("name", station.title)
                    put("url", station.contentUri.toString())
                    put("favicon", info?.favicon.orEmpty())
                    put("country", info?.countryCode.orEmpty())
                    put("tags", info?.tags.orEmpty())
                    put("codec", info?.codec.orEmpty())
                    put("bitrate", info?.bitrate ?: 0)
                    put("hls", info?.isHls ?: false)
                })
            }
        }.toString()

    private fun parseFavoriteRadioStations(raw: String): List<Song> = try {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                val url = item.optString("url").takeIf(String::isNotBlank) ?: continue
                val name = item.optString("name").ifBlank { "Radio station" }
                val favicon = item.optString("favicon")
                val country = item.optString("country")
                val tags = item.optString("tags")
                val codec = item.optString("codec")
                val bitrate = item.optInt("bitrate", 0)
                val isHls = item.optBoolean("hls", false)
                add(
                    Song(
                        id = id.hashCode().toLong(),
                        title = name,
                        artist = country.ifBlank { "Internet Radio" },
                        album = "Live Radio",
                        duration = 0L,
                        contentUri = Uri.parse(url),
                        albumArtUri = favicon.takeIf(String::isNotBlank)?.let(Uri::parse),
                        sourceType = TrackSourceType.RADIO_STREAM,
                        isLiveStream = true,
                        sourceId = id,
                        streamInfo = StreamInfo(
                            sourceId = id,
                            countryCode = country,
                            tags = tags,
                            codec = codec,
                            bitrate = bitrate,
                            favicon = favicon,
                            isHls = isHls
                        )
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    val equalizerEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_EQUALIZER_ENABLED] ?: false
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_EQUALIZER_ENABLED] = enabled
        }
    }

    val equalizerPreset: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_EQUALIZER_PRESET] ?: "Flat"
    }

    suspend fun setEqualizerPreset(presetName: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_EQUALIZER_PRESET] = presetName
        }
    }

    val equalizerBandLevels: Flow<Map<Int, Int>> = context.dataStore.data.map { preferences ->
        val raw = preferences[KEY_EQUALIZER_BAND_LEVELS] ?: ""
        parseBandLevels(raw)
    }

    suspend fun setEqualizerBandLevels(levels: Map<Int, Int>) {
        val serialized = levels.entries.joinToString(";") { "${it.key}:${it.value}" }
        context.dataStore.edit { preferences ->
            preferences[KEY_EQUALIZER_BAND_LEVELS] = serialized
        }
    }

    val smartModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SMART_MODE_ENABLED] ?: false
    }

    suspend fun setSmartModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SMART_MODE_ENABLED] = enabled
        }
    }

    val lyricsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_LYRICS_ENABLED] ?: false
    }

    suspend fun setLyricsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LYRICS_ENABLED] = enabled
            preferences.remove(KEY_LEGACY_LYRICS_MODE)
        }
    }

    val crossfadeSeconds: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[KEY_CROSSFADE_SECONDS] ?: 0).coerceIn(0, 8)
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CROSSFADE_SECONDS] = seconds.coerceIn(0, 8)
        }
    }

    val playbackSpeed: Flow<Float> = context.dataStore.data.map { preferences ->
        (preferences[KEY_PLAYBACK_SPEED] ?: 1f).coerceIn(0.75f, 1.5f)
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PLAYBACK_SPEED] = speed.coerceIn(0.75f, 1.5f)
        }
    }

    val pitchLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_PITCH_LOCKED] ?: true
    }

    suspend fun setPitchLocked(locked: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PITCH_LOCKED] = locked
        }
    }

    val lyricsFontSizeSp: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[KEY_LYRICS_FONT_SIZE_SP] ?: 22).coerceIn(16, 32)
    }

    suspend fun setLyricsFontSizeSp(sizeSp: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LYRICS_FONT_SIZE_SP] = sizeSp.coerceIn(16, 32)
        }
    }

    val lyricsOffsetMs: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[KEY_LYRICS_OFFSET_MS] ?: 0).coerceIn(-500, 500)
    }

    suspend fun setLyricsOffsetMs(offsetMs: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LYRICS_OFFSET_MS] = offsetMs.coerceIn(-500, 500)
        }
    }

    suspend fun migrateLegacyLyricsMode() {
        context.dataStore.edit { preferences ->
            if (KEY_LYRICS_ENABLED !in preferences) {
                preferences[KEY_LYRICS_ENABLED] =
                    preferences[KEY_LEGACY_LYRICS_MODE]?.let { it != "off" } ?: false
            }
            preferences.remove(KEY_LEGACY_LYRICS_MODE)
        }
    }

    private fun parseBandLevels(raw: String): Map<Int, Int> {
        if (raw.isBlank()) return emptyMap()
        return try {
            raw.split(";")
                .mapNotNull {
                    val parts = it.split(":")
                    if (parts.size == 2) {
                        parts[0].toInt() to parts[1].toInt()
                    } else null
                }
                .toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
