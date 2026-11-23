package com.offline.musicplayer.data.model

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricLine(
    val words: List<LyricWord>,
    val startMs: Long,
    val endMs: Long
)

enum class LyricsTiming {
    WORD_SYNCED,
    LINE_SYNCED,
    PLAIN
}

data class LyricsResult(
    val lines: List<LyricLine>,
    val timing: LyricsTiming,
    val plainLyrics: String? = null
)

data class LyricsState(
    val isEnabled: Boolean = false,
    val trackId: Long? = null,
    val resolvedTitle: String? = null,
    val resolvedArtist: String? = null,
    val lines: List<LyricLine>? = null,
    val plainLyrics: String? = null,
    val timing: LyricsTiming? = null,
    val isLoading: Boolean = false,
    val message: String? = null
)
