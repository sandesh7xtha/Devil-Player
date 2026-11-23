package com.offline.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.offline.musicplayer.data.model.RemoteFeedState
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary
import com.offline.musicplayer.ui.theme.AuraTextTertiary
import kotlinx.coroutines.delay

private data class RadioFilter(
    val label: String,
    val country: String? = null,
    val tag: String? = null,
    val favorites: Boolean = false
)

@Composable
fun RadioScreen(
    feed: RemoteFeedState,
    favoriteStations: List<Song>,
    currentSong: Song?,
    radioNowPlaying: RadioNowPlaying?,
    isPlaying: Boolean,
    onSearch: (String, String?, String?) -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = listOf(
        RadioFilter("Rock", tag = "rock"),
        RadioFilter("Metal", tag = "metal"),
        RadioFilter("Pop", tag = "pop"),
        RadioFilter("Nepal", country = "NP"),
        RadioFilter("Electronic", tag = "electronic"),
        RadioFilter("Hip-Hop", tag = "hiphop"),
        RadioFilter("Jazz", tag = "jazz"),
        RadioFilter("Classical", tag = "classical"),
        RadioFilter("Country", tag = "country"),
        RadioFilter("Reggae", tag = "reggae"),
        RadioFilter("Indie", tag = "indie"),
        RadioFilter("R&B", tag = "rnb"),
        RadioFilter("Folk", tag = "folk"),
        RadioFilter("Blues", tag = "blues"),
        RadioFilter("Chill", tag = "chill")
    )
    val favoritesFilter = remember { RadioFilter("My favorites", favorites = true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(filters.first()) }

    LaunchedEffect(query, selected) {
        if (!selected.favorites) {
            delay(300)
            onSearch(query.trim(), selected.country, selected.tag)
        }
    }

    val favoriteMatches = if (query.isBlank()) favoriteStations else favoriteStations.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.artist.contains(query, ignoreCase = true) ||
            it.streamInfo?.tags?.contains(query, ignoreCase = true) == true
    }
    val stations = if (selected.favorites) favoriteMatches else feed.items

    Column(modifier.fillMaxSize()) {
        SearchBarField(
            query = query,
            onQueryChange = { query = it },
            onClear = { query = "" },
            placeholder = "Search radio stations"
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "YOUR STATIONS",
                color = AuraTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            FilterChip(
                selected = selected.favorites,
                onClick = { selected = favoritesFilter },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFFF6B8A)
                    )
                },
                label = { Text("My favorites (${favoriteStations.size})") }
            )
        }
        Text(
            "EXPLORE BY GENRE",
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            color = AuraTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selected == filter,
                    onClick = { selected = filter },
                    label = { Text(filter.label) }
                )
            }
        }
        when {
            selected.favorites && stations.isEmpty() -> RemoteEmpty("No favorite stations yet. Tap the star on a station to save it here.")
            !selected.favorites && feed.isLoading && stations.isEmpty() -> RemoteLoading("Finding live stations…")
            !selected.favorites && feed.errorMessage != null && stations.isEmpty() -> RemoteError(feed.errorMessage, onRetry = {
                onSearch(query.trim(), selected.country, selected.tag)
            })
            stations.isEmpty() -> RemoteEmpty("No stations found. Try another search or filter.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
            ) {
                itemsIndexed(stations, key = { _, song -> song.sourceId ?: song.id.toString() }) { _, station ->
                    val isCurrentStation = currentSong?.sourceId == station.sourceId
                    val active = isCurrentStation && isPlaying
                    val nowPlaying = radioNowPlaying?.takeIf {
                        isCurrentStation && it.stationId == station.sourceId
                    }
                    val isFavorite = favoriteStations.any { it.sourceId == station.sourceId }
                    RadioStationCard(
                        station = station,
                        active = active,
                        nowPlaying = nowPlaying,
                        isFavorite = isFavorite,
                        onClick = { onPlay(station, stations) },
                        onToggleFavorite = { onToggleFavorite(station) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioStationCard(
    station: Song,
    active: Boolean,
    nowPlaying: RadioNowPlaying?,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var showGoogleSearch by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AuraCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(AuraSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!station.streamInfo?.favicon.isNullOrBlank()) {
                    AsyncImage(model = station.streamInfo?.favicon, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                } else {
                    Icon(Icons.Default.Radio, contentDescription = null, tint = AuraPrimary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(station.title, color = AuraTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (station.streamInfo?.countryCode?.isNotBlank() == true) {
                        Text(station.streamInfo.countryCode, color = AuraPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    val info = listOfNotNull(
                        station.streamInfo?.bitrate?.takeIf { it > 0 }?.let { "${it}k" },
                        station.streamInfo?.codec?.takeIf(String::isNotBlank)?.uppercase()
                    ).joinToString(" ").ifBlank { "Internet radio" }
                    Text(info, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (nowPlaying != null) {
                    Text(
                        text = buildString {
                            append(nowPlaying.title)
                            nowPlaying.artist?.takeIf(String::isNotBlank)?.let {
                                append('~')
                                append(it)
                            }
                            append('~')
                        },
                        color = AuraPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { showGoogleSearch = true }) {
                        Text("Search free download on Google", fontSize = 11.sp)
                    }
                } else if (station.streamInfo?.tags?.isNotBlank() == true) {
                    Text(station.streamInfo.tags.split(',').take(3).joinToString(" · "), color = AuraTextTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (active) {
                Text("● LIVE", color = Color(0xFFFF5252), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove ${station.title} from favorites" else "Add ${station.title} to favorites",
                    tint = if (isFavorite) Color(0xFFFF6B8A) else AuraTextSecondary
                )
            }
        }
    }
    if (showGoogleSearch && nowPlaying != null) {
        GoogleSearchDialog(
            query = radioFreeDownloadSearchQuery(nowPlaying),
            onDismiss = { showGoogleSearch = false }
        )
    }
}

@Composable
fun JamendoScreen(
    feed: RemoteFeedState,
    isConfigured: Boolean,
    currentSong: Song?,
    isPlaying: Boolean,
    onSearch: (String, String?) -> Unit,
    onPlay: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val genres = listOf("Trending", "Electronic", "Pop", "Rock", "Acoustic", "Lo-Fi")
    var query by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf(genres.first()) }
    LaunchedEffect(query, selectedGenre, isConfigured) {
        if (isConfigured) {
            delay(350)
            onSearch(query.trim(), selectedGenre.takeUnless { it == "Trending" })
        }
    }

    Column(modifier.fillMaxSize()) {
        SearchBarField(
            query = query,
            onQueryChange = { query = it },
            onClear = { query = "" },
            placeholder = "Search independent music"
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            genres.forEach { genre ->
                FilterChip(selected = selectedGenre == genre, onClick = { selectedGenre = genre }, label = { Text(genre) })
            }
        }
        when {
            !isConfigured -> RemoteError(
                "Jamendo is not configured. Add JAMENDO_CLIENT_ID to local.properties and rebuild the app.",
                onRetry = null
            )
            feed.isLoading && feed.items.isEmpty() -> RemoteLoading("Discovering independent music…")
            feed.errorMessage != null && feed.items.isEmpty() -> RemoteError(feed.errorMessage, onRetry = {
                onSearch(query.trim(), selectedGenre.takeUnless { it == "Trending" })
            })
            feed.items.isEmpty() -> RemoteEmpty("No tracks found. Try another search or genre.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp)
            ) {
                itemsIndexed(feed.items, key = { _, song -> song.sourceId ?: song.id.toString() }) { index, track ->
                    val active = currentSong?.sourceId == track.sourceId && isPlaying
                    JamendoTrackCard(track, active, onClick = { onPlay(track) })
                }
            }
        }
    }
}

@Composable
private fun JamendoTrackCard(track: Song, active: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AuraCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(AuraSurfaceVariant), contentAlignment = Alignment.Center) {
                if (track.albumArtUri != null) {
                    AsyncImage(model = track.albumArtUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                } else Icon(Icons.Default.MusicNote, contentDescription = null, tint = AuraPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = AuraTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.album} · Jamendo", color = AuraTextTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active) Text("PLAYING", color = AuraPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RemoteLoading(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AuraPrimary)
            Spacer(Modifier.height(12.dp))
            Text(message, color = AuraTextSecondary)
        }
    }
}

@Composable
private fun RemoteError(message: String?, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message.orEmpty(), color = AuraTextSecondary)
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun RemoteEmpty(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = AuraTextSecondary)
    }
}
