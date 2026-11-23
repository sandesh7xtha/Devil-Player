package com.offline.musicplayer.ui.screens

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.musicplayer.ui.theme.AuraBackground
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextSecondary

enum class ArtworkDisplayMode {
    MIRRORED_COVER,
    SPINNING_VINYL,
    VINYL_SLEEVE_HYBRID
}

@Composable
fun InteractiveArtworkShowcase(
    artworkUri: Uri?,
    title: String,
    isPlaying: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    var displayMode by remember { mutableStateOf(ArtworkDisplayMode.VINYL_SLEEVE_HYBRID) }

    // Ambient Breathing Glow Animation
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isPlaying) 0.25f else 0.10f,
        targetValue = if (isPlaying) 0.55f else 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            // Ambient Neon Halo behind the album art
            Box(
                modifier = Modifier
                    .fillMaxSize(0.95f)
                    .scale(glowScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = glowAlpha),
                                AuraPrimary.copy(alpha = glowAlpha * 0.6f),
                                Color.Transparent
                            )
                        )
                    )
            )

            when (displayMode) {
                ArtworkDisplayMode.VINYL_SLEEVE_HYBRID -> {
                    VinylSleeveHybridView(
                        artworkUri = artworkUri,
                        title = title,
                        isPlaying = isPlaying,
                        onToggleMode = {
                            displayMode = ArtworkDisplayMode.MIRRORED_COVER
                        }
                    )
                }
                ArtworkDisplayMode.MIRRORED_COVER -> {
                    MirroredCoverView(
                        artworkUri = artworkUri,
                        title = title,
                        onToggleMode = {
                            displayMode = ArtworkDisplayMode.SPINNING_VINYL
                        }
                    )
                }
                ArtworkDisplayMode.SPINNING_VINYL -> {
                    SpinningVinylView(
                        artworkUri = artworkUri,
                        title = title,
                        isPlaying = isPlaying,
                        onToggleMode = {
                            displayMode = ArtworkDisplayMode.VINYL_SLEEVE_HYBRID
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Visual Mode Switcher Pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AuraSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                displayMode = when (displayMode) {
                    ArtworkDisplayMode.VINYL_SLEEVE_HYBRID -> ArtworkDisplayMode.MIRRORED_COVER
                    ArtworkDisplayMode.MIRRORED_COVER -> ArtworkDisplayMode.SPINNING_VINYL
                    ArtworkDisplayMode.SPINNING_VINYL -> ArtworkDisplayMode.VINYL_SLEEVE_HYBRID
                }
            }
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    text = when (displayMode) {
                        ArtworkDisplayMode.VINYL_SLEEVE_HYBRID -> "✦ Vinyl & Jacket (Tap to switch)"
                        ArtworkDisplayMode.MIRRORED_COVER -> "✦ Mirrored Cover Flow (Tap to switch)"
                        ArtworkDisplayMode.SPINNING_VINYL -> "✦ Turntable Disc (Tap to switch)"
                    },
                    fontSize = 11.sp,
                    color = AuraTextSecondary
                )
            }
        }
    }
}

/**
 * High-end Vinyl Record Disc that peeks out from the album jacket and spins when playing!
 */
@Composable
fun VinylSleeveHybridView(
    artworkUri: Uri?,
    title: String,
    isPlaying: Boolean,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Disc Slide Out animation: slides out 28% to the right when playing, slides back when paused
    val discOffsetRatio by animateFloatAsState(
        targetValue = if (isPlaying) 0.28f else 0.12f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "disc_slide"
    )

    // Smooth continuous vinyl rotation
    var currentRotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis() - (currentRotation / 360f * 3000L).toLong()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                currentRotation = (elapsed % 3000L) / 3000f * 360f
                kotlinx.coroutines.delay(16L) // ~60fps
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onToggleMode),
        contentAlignment = Alignment.Center
    ) {
        // Spinning Vinyl Disc peeking out behind jacket
        Box(
            modifier = Modifier
                .fillMaxSize(0.82f)
                .graphicsLayer {
                    translationX = size.width * discOffsetRatio
                    rotationZ = currentRotation
                },
            contentAlignment = Alignment.Center
        ) {
            VinylGroovesCanvas(modifier = Modifier.fillMaxSize())

            // Center Vinyl Sticker Label
            ArtworkImage(
                artworkUri = artworkUri,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize(0.38f)
                    .clip(CircleShape)
            )

            // Center Spindle Hole
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(AuraBackground)
            )
        }

        // Album Jacket on top with subtle drop shadow
        Box(
            modifier = Modifier
                .fillMaxSize(0.80f)
                .graphicsLayer {
                    translationX = -size.width * 0.10f
                }
                .shadow(28.dp, RoundedCornerShape(18.dp), spotColor = Color.Black)
                .clip(RoundedCornerShape(18.dp))
        ) {
            ArtworkImage(
                artworkUri = artworkUri,
                contentDescription = title,
                modifier = Modifier.fillMaxSize()
            )

            // Glossy Sleeve Sheen Gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.15f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f)
                            ),
                            start = Offset.Zero,
                            end = Offset(400f, 600f)
                        )
                    )
            )
        }
    }
}

/**
 * Full Authentic Mirrored Floor Reflection (wet floor / glass table effect)
 */
@Composable
fun MirroredCoverView(
    artworkUri: Uri?,
    title: String,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onToggleMode),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Primary Cover Jacket
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f)
                .shadow(24.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
        ) {
            ArtworkImage(
                artworkUri = artworkUri,
                contentDescription = title,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Inverted Mirror Floor Reflection
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(76.dp)
                .graphicsLayer {
                    scaleY = -1f // Flips vertically for authentic reflection
                    alpha = 0.5f
                }
                .clip(RoundedCornerShape(18.dp))
        ) {
            ArtworkImage(
                artworkUri = artworkUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )

            // Floor fade gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                AuraBackground.copy(alpha = 0.6f),
                                AuraBackground
                            )
                        )
                    )
            )
        }
    }
}

/**
 * Pure Spinning Vinyl Turntable Disc Mode
 */
@Composable
fun SpinningVinylView(
    artworkUri: Uri?,
    title: String,
    isPlaying: Boolean,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val startTime = System.currentTimeMillis() - (rotationAngle / 360f * 3200L).toLong()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                rotationAngle = (elapsed % 3200L) / 3200f * 360f
                kotlinx.coroutines.delay(16L)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize(0.92f)
            .shadow(28.dp, CircleShape)
            .rotate(rotationAngle)
            .clickable(onClick = onToggleMode),
        contentAlignment = Alignment.Center
    ) {
        // Grooved vinyl surface
        VinylGroovesCanvas(modifier = Modifier.fillMaxSize())

        // Center Album Art Sticker Label
        ArtworkImage(
            artworkUri = artworkUri,
            contentDescription = title,
            modifier = Modifier
                .fillMaxSize(0.42f)
                .clip(CircleShape)
        )

        // Center Vinyl Spindle Hole with metallic rim
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(AuraBackground),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.LightGray.copy(alpha = 0.8f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

/**
 * Custom Canvas drawing realistic concentric micro-grooves and specular highlights of a vinyl record.
 */
@Composable
fun VinylGroovesCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f

        // Dark obsidian vinyl body
        drawCircle(
            color = Color(0xFF101014),
            radius = outerRadius,
            center = center
        )

        // Concentric audio micro-grooves
        val grooveSteps = 24
        val grooveStart = outerRadius * 0.42f
        val grooveEnd = outerRadius * 0.96f
        val stepSize = (grooveEnd - grooveStart) / grooveSteps

        for (i in 0..grooveSteps) {
            val r = grooveStart + i * stepSize
            drawCircle(
                color = Color.White.copy(alpha = if (i % 3 == 0) 0.08f else 0.035f),
                radius = r,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )
        }

        // Realistic vinyl specular reflection shine (cross sheen)
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.03f),
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.03f),
                    Color.White.copy(alpha = 0.16f),
                    Color.White.copy(alpha = 0.03f)
                ),
                center = center
            ),
            radius = outerRadius,
            center = center
        )

        // Outer rim edge
        drawCircle(
            color = Color(0xFF22222A),
            radius = outerRadius,
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}
