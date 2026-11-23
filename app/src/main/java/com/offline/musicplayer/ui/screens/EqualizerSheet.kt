package com.offline.musicplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.musicplayer.data.model.EqualizerBand
import com.offline.musicplayer.data.model.EqualizerState
import com.offline.musicplayer.data.model.LyricsState
import com.offline.musicplayer.data.model.PlayerFeatureSettings
import com.offline.musicplayer.data.model.SmartModeState
import com.offline.musicplayer.ui.theme.AuraBackground
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary
import com.offline.musicplayer.ui.theme.AuraTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    equalizerState: EqualizerState,
    onEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandLevelChange: (bandIndex: Int, levelMb: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraBackground,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header: Title & Master Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Equalizer",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AuraTextPrimary
                    )
                    Text(
                        text = "Shape your sound with presets or custom bands",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraTextSecondary
                    )
                }

                Switch(
                    checked = equalizerState.isEnabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AuraPrimary,
                        checkedTrackColor = AuraPrimary.copy(alpha = 0.4f),
                        uncheckedThumbColor = AuraTextSecondary,
                        uncheckedTrackColor = AuraSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Professional Audio Spectrum Response Curve Canvas
            AudioSpectrumCurveCanvas(
                bands = equalizerState.bands,
                isEnabled = equalizerState.isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AuraCard)
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Presets Horizontal Chip Scroll
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sound Profiles",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTextPrimary
                )

                if (equalizerState.activePreset == "Custom") {
                    Text(
                        text = "Custom Mode",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                equalizerState.availablePresets.forEach { preset ->
                    val isSelected = equalizerState.activePreset.equals(preset, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (!equalizerState.isEnabled) {
                                onEnabledChange(true)
                            }
                            onPresetSelected(preset)
                        },
                        label = {
                            Text(
                                text = if (preset == "Custom") "✦ Custom" else preset,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = AuraCard,
                            labelColor = AuraTextSecondary,
                            selectedContainerColor = AuraPrimary,
                            selectedLabelColor = AuraTextPrimary,
                            disabledContainerColor = AuraSurfaceVariant.copy(alpha = 0.4f),
                            disabledLabelColor = AuraTextTertiary
                        ),
                        border = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Frequency Band Sliders Header + Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (equalizerState.activePreset == "Custom") "Custom Frequency Bands" else "Frequency Bands",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraTextPrimary
                )

                if (equalizerState.activePreset != "Flat") {
                    TextButton(onClick = { onPresetSelected("Flat") }) {
                        Text(text = "Reset to Flat", fontSize = 12.sp, color = AuraSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            equalizerState.bands.forEach { band ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${band.displayFrequency} Hz",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (equalizerState.isEnabled) AuraTextPrimary else AuraTextTertiary
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text(
                                text = "• ${band.bandTitle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AuraTextTertiary
                            )
                        }

                        Text(
                            text = band.displayDb,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (equalizerState.isEnabled) AuraSecondary else AuraTextTertiary
                        )
                    }

                    Slider(
                        value = band.currentLevelMb.toFloat(),
                        onValueChange = { newValue ->
                            if (!equalizerState.isEnabled) {
                                onEnabledChange(true)
                            }
                            onBandLevelChange(band.index, newValue.toInt())
                        },
                        valueRange = band.minLevelMb.toFloat()..band.maxLevelMb.toFloat(),
                        enabled = equalizerState.isEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = AuraPrimary,
                            activeTrackColor = AuraPrimary,
                            inactiveTrackColor = AuraSurfaceVariant,
                            disabledThumbColor = AuraTextTertiary,
                            disabledActiveTrackColor = AuraSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * DAW-grade real-time frequency curve visualizer drawn with smooth cubic Bezier paths.
 */
@Composable
fun AudioSpectrumCurveCanvas(
    bands: List<EqualizerBand>,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f

        // Draw 0 dB reference dashed/faint center line
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(0f, centerY),
            end = Offset(w, centerY),
            strokeWidth = 1.dp.toPx()
        )

        if (bands.isEmpty()) return@Canvas

        val minLevel = bands.first().minLevelMb.toFloat()
        val maxLevel = bands.first().maxLevelMb.toFloat()
        val levelRange = (maxLevel - minLevel).coerceAtLeast(1f)

        // Calculate (x, y) coordinates for each frequency band node
        val points = mutableListOf<Offset>()
        val paddingHorizontal = 24.dp.toPx()
        val usableWidth = w - 2 * paddingHorizontal

        for (i in bands.indices) {
            val band = bands[i]
            val x = paddingHorizontal + (i.toFloat() / (bands.size - 1).coerceAtLeast(1)) * usableWidth

            // Map currentLevelMb (min..max) -> y coordinate (bottom to top)
            val normalized = if (isEnabled) {
                ((band.currentLevelMb - minLevel) / levelRange).coerceIn(0f, 1f)
            } else {
                0.5f // flat center line when disabled
            }
            val y = h - (normalized * (h - 24.dp.toPx()) + 12.dp.toPx())
            points.add(Offset(x, y))
        }

        // Construct smooth cubic Bezier path
        val strokePath = Path()
        val fillPath = Path()

        strokePath.moveTo(0f, points.first().y)
        strokePath.lineTo(points.first().x, points.first().y)

        fillPath.moveTo(0f, h)
        fillPath.lineTo(0f, points.first().y)
        fillPath.lineTo(points.first().x, points.first().y)

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val controlPoint1 = Offset(p0.x + (p1.x - p0.x) / 2f, p0.y)
            val controlPoint2 = Offset(p0.x + (p1.x - p0.x) / 2f, p1.y)

            strokePath.cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p1.x, p1.y)
            fillPath.cubicTo(controlPoint1.x, controlPoint1.y, controlPoint2.x, controlPoint2.y, p1.x, p1.y)
        }

        strokePath.lineTo(w, points.last().y)

        fillPath.lineTo(w, points.last().y)
        fillPath.lineTo(w, h)
        fillPath.close()

        // Draw translucent glowing gradient under curve
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = if (isEnabled) listOf(
                    AuraPrimary.copy(alpha = 0.35f),
                    AuraSecondary.copy(alpha = 0.12f),
                    Color.Transparent
                ) else listOf(
                    AuraTextTertiary.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h
            )
        )

        // Draw neon stroke along curve
        drawPath(
            path = strokePath,
            brush = Brush.horizontalGradient(
                colors = if (isEnabled) listOf(AuraPrimary, AuraSecondary) else listOf(AuraTextTertiary, AuraTextTertiary)
            ),
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw glowing frequency node points
        for (pt in points) {
            if (isEnabled) {
                drawCircle(
                    color = AuraSecondary.copy(alpha = 0.3f),
                    radius = 7.dp.toPx(),
                    center = pt
                )
                drawCircle(
                    color = AuraSecondary,
                    radius = 3.5.dp.toPx(),
                    center = pt
                )
            } else {
                drawCircle(
                    color = AuraTextTertiary,
                    radius = 3.dp.toPx(),
                    center = pt
                )
            }
        }
    }
}
