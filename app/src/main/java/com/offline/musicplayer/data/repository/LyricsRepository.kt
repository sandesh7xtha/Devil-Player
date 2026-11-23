package com.offline.musicplayer.data.repository

import android.content.Context
import android.util.Log
import android.provider.MediaStore
import com.offline.musicplayer.data.model.LyricLine
import com.offline.musicplayer.data.model.LyricWord
import com.offline.musicplayer.data.model.LyricsResult
import com.offline.musicplayer.data.model.LyricsTiming
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.data.parser.ParsedTrack
import com.offline.musicplayer.data.parser.TrackNameParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class LyricsRepository(context: Context) {
    companion object {
        private const val TAG = "LyricsRepository"
        private const val USER_AGENT = "OfflineMusicPlayer/1.0"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private val LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{1,3})]""")
        private val INLINE_TIMESTAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>""")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
    private val contentResolver = context.contentResolver
    private val activeCall = AtomicReference<Call?>(null)
    private val requestGeneration = AtomicLong(0L)

    suspend fun fetch(
        song: Song,
        knownArtists: Collection<String> = emptyList(),
        resolvedName: ParsedTrack? = null
    ): LyricsResult? {
        val requestId = requestGeneration.incrementAndGet()
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            checkCurrentRequest(requestId)
            val parsedName = resolvedName ?: resolveTrackNameBlocking(song, knownArtists)
            val title = parsedName.title
            val trackArtist = parsedName.artist ?: song.artist
            val cleanedSong = song.copy(title = title, artist = trackArtist)
            val durationSeconds = song.duration / 1000L

            val exactResponse = if (trackArtist.isValidMetadata() &&
                title.isNotBlank() &&
                durationSeconds in 1..3600
            ) {
                val exactQuery = HttpUrl.Builder()
                    .scheme("https")
                    .host("lrclib.net")
                    .addPathSegments("api/get")
                    .addQueryParameter("artist_name", trackArtist)
                    .addQueryParameter("track_name", title)
                    .addQueryParameter(
                        "album_name",
                        song.album.takeIf { it.isValidMetadata() } ?: "Unknown Album"
                    )
                    .addQueryParameter("duration", durationSeconds.toString())
                    .build()
                request(exactQuery, requestId)
            } else {
                HttpResponse(404, null)
            }
            currentCoroutineContext().ensureActive()
            checkCurrentRequest(requestId)
            val durationMs = song.duration.coerceAtLeast(0L)
            var bestResult: LyricsResult? = null
            exactResponse.body?.let { body ->
                val exactRecord = parseRecord(body)
                bestResult = parseLyricsResult(exactRecord, durationMs)
                if (bestResult?.timing == LyricsTiming.WORD_SYNCED) {
                    return@withContext bestResult
                }
            }

            if (exactResponse.statusCode != 404 &&
                exactResponse.body == null
            ) {
                return@withContext null
            }

            val searchMatches = searchForMatches(cleanedSong, title, requestId)
            for (record in searchMatches) {
                currentCoroutineContext().ensureActive()
                checkCurrentRequest(requestId)
                val result = parseLyricsResult(record, durationMs) ?: continue
                if (bestResult == null || result.timing.priority < bestResult!!.timing.priority) {
                    bestResult = result
                }
                if (result.timing == LyricsTiming.WORD_SYNCED) {
                    return@withContext result
                }
            }
            bestResult
        }
    }

    suspend fun resolveTrackName(
        song: Song,
        knownArtists: Collection<String> = emptyList()
    ): ParsedTrack = withContext(Dispatchers.IO) {
        resolveTrackNameBlocking(song, knownArtists)
    }

    private fun resolveTrackNameBlocking(
        song: Song,
        knownArtists: Collection<String>
    ): ParsedTrack {
        if (song.sourceType != TrackSourceType.LOCAL) {
            return ParsedTrack(
                artist = song.artist.takeIf { it.isValidMetadata() },
                title = song.title
            )
        }
        val parsedName = TrackNameParser(knownArtists + song.artist).parse(
            rawName = readDisplayName(song) ?: song.title,
            artistHint = song.artist,
            albumHint = song.album
        )
        return ParsedTrack(
            artist = parsedName.artist?.takeIf { it.isValidMetadata() } ?: song.artist,
            title = parsedName.title
        )
    }

    fun cancelActiveRequest() {
        requestGeneration.incrementAndGet()
        activeCall.getAndSet(null)?.cancel()
    }

    private suspend fun searchForMatches(
        song: Song,
        cleanedTitle: String,
        requestId: Long
    ): List<JSONObject> {
        val durationSeconds = song.duration / 1000.0
        val searchText = listOfNotNull(
            song.artist.takeIf { it.isValidMetadata() },
            cleanedTitle.takeIf(String::isNotBlank)
        ).joinToString(" ")
        if (searchText.isBlank()) return emptyList()
        val searchQuery = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")
            .addQueryParameter("q", searchText)
            .build()
        val response = request(searchQuery, requestId)
        if (response.statusCode == 404) return emptyList()
        val body = response.body ?: return emptyList()
        val results = org.json.JSONArray(body)
        return (0 until results.length())
            .asSequence()
            .mapNotNull { results.optJSONObject(it) }
            .filterNot { it.optBoolean("instrumental") }
            .filter {
                it.textField("syncedLyrics").isNotBlank() ||
                    it.optBoolean("hasWordSync") ||
                    it.textField("plainLyrics").isNotBlank()
            }
            .filter {
                val titleScore = titleMatchScore(it.optString("trackName"), cleanedTitle)
                titleScore <= 1 &&
                    (!song.artist.isValidMetadata() ||
                        titleMatchScore(it.optString("artistName"), song.artist) <= 1)
            }
            .sortedWith(
                compareBy<JSONObject> {
                    parseLyricsResult(it, 0L)?.timing?.priority ?: Int.MAX_VALUE
                }.thenBy {
                    titleMatchScore(it.optString("trackName"), cleanedTitle)
                }.thenBy {
                    titleMatchScore(it.optString("artistName"), song.artist)
                }.thenBy {
                    val candidateDuration = it.optDouble("duration", Double.NaN)
                    if (candidateDuration.isFinite() && durationSeconds > 0) {
                        kotlin.math.abs(candidateDuration - durationSeconds)
                    } else if (durationSeconds > 0) {
                        Double.MAX_VALUE
                    } else {
                        0.0
                    }
                }
            )
            .toList()
    }

    private fun parseLyricsResult(record: JSONObject, durationMs: Long): LyricsResult? {
        if (record.optBoolean("instrumental")) return null
        if (record.optBoolean("hasWordSync")) {
            parseLyricsFile(record.textField("lyricsfile"), durationMs)
                .takeIf { it.isNotEmpty() }
                ?.let { return LyricsResult(it, LyricsTiming.WORD_SYNCED) }
        }

        val rawSyncedLyrics = record.textField("syncedLyrics")
        val parsedLines = parseSyncedLyrics(rawSyncedLyrics, durationMs)
        if (parsedLines.isNotEmpty()) {
            val sourceLines = rawSyncedLyrics.lineSequence()
                .filter { LRC_TIMESTAMP.containsMatchIn(it) }
                .toList()
            val timing = if (sourceLines.isNotEmpty() &&
                sourceLines.all { INLINE_TIMESTAMP.containsMatchIn(it) }
            ) {
                LyricsTiming.WORD_SYNCED
            } else {
                LyricsTiming.LINE_SYNCED
            }
            return LyricsResult(parsedLines, timing)
        }

        val plainLyrics = record.textField("plainLyrics")
            .replace("\r\n", "\n")
            .trim()
            .takeIf(String::isNotBlank)
        return plainLyrics?.let {
            LyricsResult(emptyList(), LyricsTiming.PLAIN, it)
        }
    }

    private val LyricsTiming.priority: Int
        get() = when (this) {
            LyricsTiming.WORD_SYNCED -> 0
            LyricsTiming.LINE_SYNCED -> 1
            LyricsTiming.PLAIN -> 2
        }

    private fun JSONObject.textField(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    /** Read the filename only for the active track when online lyrics are requested. */
    private fun readDisplayName(song: Song): String? = try {
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
            } else {
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read active track filename; using its MediaStore title", e)
        null
    }

    private fun normalizeForMatch(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

    private fun titleMatchScore(candidate: String, expected: String): Int {
        val normalizedCandidate = normalizeForMatch(candidate)
        val normalizedExpected = normalizeForMatch(expected)
        return when {
            normalizedCandidate == normalizedExpected -> 0
            tokenMatch(normalizedCandidate, normalizedExpected) -> 1
            else -> 2
        }
    }

    private fun tokenMatch(candidate: String, expected: String): Boolean {
        val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
        val expectedTokens = expected.split(' ').filter(String::isNotBlank).toSet()
        if (candidateTokens.isEmpty() || expectedTokens.isEmpty()) return false
        return (expectedTokens.size >= 2 && candidateTokens.containsAll(expectedTokens)) ||
            (candidateTokens.size >= 2 && expectedTokens.containsAll(candidateTokens))
    }

    private fun String.isValidMetadata(): Boolean =
        isNotBlank() && !equals("Unknown Artist", ignoreCase = true) &&
            !equals("Unknown Album", ignoreCase = true) && !equals("<unknown>", ignoreCase = true)

    private suspend fun request(url: HttpUrl, requestId: Long): HttpResponse {
        currentCoroutineContext().ensureActive()
        checkCurrentRequest(requestId)
        val call = httpClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        )
        activeCall.set(call)
        try {
            currentCoroutineContext().ensureActive()
            checkCurrentRequest(requestId)
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                checkCurrentRequest(requestId)
                if (response.code == 404) return HttpResponse(response.code, null)
                if (!response.isSuccessful) {
                    throw LyricsRequestException(
                        statusCode = response.code,
                        retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                    )
                }
                val body = response.body?.string()
                currentCoroutineContext().ensureActive()
                checkCurrentRequest(requestId)
                return HttpResponse(response.code, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "LRCLIB request failed: $url", e)
            throw e
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun parseRecord(body: String): JSONObject = JSONObject(body)

    private fun checkCurrentRequest(requestId: Long) {
        if (requestId != requestGeneration.get()) {
            throw CancellationException("Lyrics request superseded")
        }
    }

    private fun parseSyncedLyrics(raw: String, durationMs: Long): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = mutableListOf<TimedSourceLine>()
        raw.lineSequence().forEach { sourceLine ->
            val lrcTags = LRC_TIMESTAMP.findAll(sourceLine).toList()
            if (lrcTags.isEmpty()) return@forEach
            val textStart = lrcTags.last().range.last + 1
            val text = sourceLine.substring(textStart).trim()
            if (text.isBlank()) return@forEach
            lrcTags.forEach { tag ->
                parsed += TimedSourceLine(
                    startMs = timestampToMs(tag),
                    text = text,
                    inlineWords = parseInlineWords(text)
                )
            }
        }
        parsed.sortBy { it.startMs }

        return parsed.mapIndexedNotNull { index, line ->
            val lineEnd = parsed.getOrNull(index + 1)?.startMs
                ?: durationMs.takeIf { it > line.startMs }
                ?: line.startMs + 4_000L
            val words = line.inlineWords?.takeIf { it.isNotEmpty() }
                ?: distributeWords(line.text, line.startMs, lineEnd)
            words.takeIf { it.isNotEmpty() }?.let {
                LyricLine(it, line.startMs, max(line.startMs + 1, lineEnd))
            }
        }
    }

    private fun parseInlineWords(text: String): List<LyricWord>? {
        val markers = INLINE_TIMESTAMP.findAll(text).toList()
        if (markers.isEmpty()) return null
        val result = mutableListOf<LyricWord>()
        markers.forEachIndexed { index, marker ->
            val endOfText = markers.getOrNull(index + 1)?.range?.first ?: text.length
            val wordText = text.substring(marker.range.last + 1, endOfText).trim()
            if (wordText.isNotEmpty()) {
                val startMs = timestampToMs(marker)
                val nextStart = markers.getOrNull(index + 1)?.let(::timestampToMs)
                val endMs = nextStart?.takeIf { it > startMs } ?: startMs + 350L
                result += LyricWord(wordText, startMs, endMs)
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseLyricsFile(raw: String, durationMs: Long): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val parsedLines = mutableListOf<YamlLine>()
        var currentLine: YamlLine? = null
        var currentWord: MutableMap<String, String>? = null
        var lineIndent = -1
        var wordIndent = -1
        var inLines = false
        var inWords = false

        for (source in raw.lineSequence()) {
            val trimmed = source.trim()
            if (trimmed == "lines:") {
                inLines = true
                continue
            }
            if (!inLines || trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = source.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val listEntry = trimmed.startsWith("- ")
            val pair = (if (listEntry) trimmed.removePrefix("- ").trim() else trimmed)
                .split(':', limit = 2)
            val key = pair.firstOrNull()?.trim()?.trim('"', '\'') ?: continue
            val value = pair.getOrNull(1)?.trim()?.trim('"', '\'') ?: ""

            if (listEntry && (lineIndent < 0 || indent <= lineIndent)) {
                currentLine?.let(parsedLines::add)
                currentLine = YamlLine()
                currentWord = null
                lineIndent = indent
                wordIndent = -1
                inWords = false
                if (value.isNotEmpty()) currentLine?.fields?.set(key, value)
                continue
            }

            val line = currentLine ?: continue
            if (key == "words") {
                inWords = true
                wordIndent = indent
                continue
            }
            if (listEntry && inWords && indent >= wordIndent) {
                currentWord = mutableMapOf()
                line.words += currentWord!!
                if (value.isNotEmpty()) currentWord!![key] = value
                continue
            }
            if (inWords && currentWord != null && indent > wordIndent) {
                currentWord!![key] = value
            } else if (indent > lineIndent) {
                inWords = false
                line.fields[key] = value
            }
        }
        currentLine?.let(parsedLines::add)

        return parsedLines.mapNotNull { line ->
            val lineStart = parseTime(line.fields["startMs"] ?: line.fields["start_ms"])
                ?: return@mapNotNull null
            val lineEnd = parseTime(line.fields["endMs"] ?: line.fields["end_ms"])
                ?: durationMs.takeIf { it > lineStart }
                ?: lineStart + 4_000L
            val words = line.words.mapIndexedNotNull { index, word ->
                val text = word["text"] ?: word["word"] ?: return@mapNotNull null
                val start = parseTime(word["startMs"] ?: word["start_ms"])
                    ?: return@mapNotNull null
                val end = parseTime(word["endMs"] ?: word["end_ms"])
                    ?: line.words.getOrNull(index + 1)?.let {
                        parseTime(it["startMs"] ?: it["start_ms"])
                    }
                    ?: lineEnd
                LyricWord(text, start, max(start + 1, end))
            }
            words.takeIf { it.isNotEmpty() }?.let {
                LyricLine(it, lineStart, max(lineStart + 1, lineEnd))
            }
        }.sortedBy { it.startMs }
    }

    private fun distributeWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        val tokens = text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (tokens.isEmpty()) return emptyList()
        val duration = max(tokens.size.toLong(), endMs - startMs)
        return tokens.mapIndexed { index, token ->
            val start = startMs + duration * index / tokens.size
            val end = max(start + 1, startMs + duration * (index + 1) / tokens.size)
            LyricWord(token, start, end)
        }
    }

    private fun timestampToMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
        val fractionRaw = match.groupValues[3]
        val fraction = fractionRaw.toLongOrNull() ?: 0L
        val millis = when (fractionRaw.length) {
            1 -> fraction * 100
            2 -> fraction * 10
            else -> fraction
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun parseTime(value: String?): Long? = value?.toLongOrNull()

    private data class TimedSourceLine(
        val startMs: Long,
        val text: String,
        val inlineWords: List<LyricWord>?
    )

    private class YamlLine {
        val fields = mutableMapOf<String, String>()
        val words = mutableListOf<MutableMap<String, String>>()
    }

    private data class HttpResponse(val statusCode: Int, val body: String?)
}

class LyricsRequestException(
    val statusCode: Int,
    val retryAfterSeconds: Long?
) : java.io.IOException("LRCLIB request failed with HTTP $statusCode")
