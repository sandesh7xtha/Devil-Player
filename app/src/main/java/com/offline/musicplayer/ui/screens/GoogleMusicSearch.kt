package com.offline.musicplayer.ui.screens

import com.offline.musicplayer.data.model.RadioNowPlaying

internal fun radioFreeDownloadSearchQuery(track: RadioNowPlaying): String =
    buildString {
        append(track.title.trim())
        track.artist?.trim()?.takeIf(String::isNotBlank)?.let {
            append('~')
            append(it)
        }
        append(" free download")
    }
