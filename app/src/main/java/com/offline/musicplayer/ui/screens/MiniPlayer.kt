package com.offline.musicplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.musicplayer.data.model.PlaybackState
import com.offline.musicplayer.data.model.RadioNowPlaying
import com.offline.musicplayer.data.model.TrackSourceType
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    radioNowPlaying: RadioNowPlaying? = null,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onShowFloatingController: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong = playbackState.currentSong

    AnimatedVisibility(
        visible = currentSong != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (currentSong == null) return@AnimatedVisibility
        val currentRadioTrack = radioNowPlaying?.takeIf {
            currentSong.sourceType == TrackSourceType.RADIO_STREAM &&
                it.stationId == currentSong.sourceId
        }

        // Sleek frosted glass card with glowing neon hairline border
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = AuraCard.copy(alpha = 0.94f)),
            border = BorderStroke(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        AuraPrimary.copy(alpha = 0.45f),
                        AuraSecondary.copy(alpha = 0.35f),
                        AuraPrimary.copy(alpha = 0.15f)
                    )
                )
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // High-precision smooth progress bar with gradient glow
                if (currentSong.isLiveStream) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFFF5252)))
                } else {
                    LinearProgressIndicator(
                        progress = { playbackState.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = AuraSecondary,
                        trackColor = AuraSurfaceVariant.copy(alpha = 0.4f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Artwork Thumbnail with mini equalizer overlay
                    Box(contentAlignment = Alignment.Center) {
                        ArtworkImage(
                            artworkUri = currentSong.albumArtUri,
                            contentDescription = currentSong.title,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )

                        if (playbackState.isPlaying) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                PlayingEqualizerIndicator(
                                    isPlaying = true,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Title with auto-marquee & artist
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentRadioTrack?.title ?: currentSong.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AuraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentRadioTrack?.artist?.let { "$it · ${currentSong.title}" }
                                ?: currentSong.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentSong.isLiveStream) {
                            Text("● LIVE", color = Color(0xFFFF5252), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Play/Pause button with bouncing spring animation
                    BouncingIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(AuraPrimary.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = AuraTextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Next button with bouncing spring animation
                    BouncingIconButton(
                        onClick = onSkipNext,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = AuraTextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    BouncingIconButton(
                        onClick = onShowFloatingController,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Show floating player controls",
                            tint = AuraSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
