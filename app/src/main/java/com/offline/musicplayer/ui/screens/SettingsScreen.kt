package com.offline.musicplayer.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.offline.musicplayer.BuildConfig
import com.offline.musicplayer.data.model.CapsulePosition
import com.offline.musicplayer.data.model.CapsuleSize
import com.offline.musicplayer.data.model.CapsuleSettings
import com.offline.musicplayer.data.model.EqualizerState
import com.offline.musicplayer.data.model.PlayerFeatureSettings
import com.offline.musicplayer.data.model.SmartModeState
import com.offline.musicplayer.data.preferences.PlayerPreferences
import com.offline.musicplayer.service.CapsuleAccessibilityService
import com.offline.musicplayer.ui.theme.AuraAccentSuccess
import com.offline.musicplayer.ui.theme.AuraAccentWarning
import com.offline.musicplayer.ui.theme.AuraBackground
import com.offline.musicplayer.ui.theme.AuraCard
import com.offline.musicplayer.ui.theme.AuraDivider
import com.offline.musicplayer.ui.theme.AuraPrimary
import com.offline.musicplayer.ui.theme.AuraSecondary
import com.offline.musicplayer.ui.theme.AuraSurfaceVariant
import com.offline.musicplayer.ui.theme.AuraTextPrimary
import com.offline.musicplayer.ui.theme.AuraTextSecondary
import com.offline.musicplayer.ui.theme.AuraTextTertiary

class SettingsActions(
    val onPlaybackSpeedChange: (Float) -> Unit,
    val onPitchLockChange: (Boolean) -> Unit,
    val onCrossfadeSecondsChange: (Int) -> Unit,
    val onSmartModeEnabledChange: (Boolean) -> Unit,
    val onOpenEqualizer: () -> Unit,
    val onLyricsEnabledChange: (Boolean) -> Unit,
    val onLyricsFontSizeChange: (Int) -> Unit,
    val onLyricsOffsetChange: (Int) -> Unit,
    val onCapsuleEnabledChange: (Boolean) -> Unit,
    val onCapsuleSizeChange: (CapsuleSize) -> Unit,
    val onCapsulePositionChange: (CapsulePosition) -> Unit,
    val onCapsuleSwipeGesturesChange: (Boolean) -> Unit,
    val onRescanLibrary: () -> Unit,
    val onResetSettings: () -> Unit
)

@Composable
fun SettingsScreen(
    featureSettings: PlayerFeatureSettings,
    capsuleSettings: CapsuleSettings,
    smartModeState: SmartModeState,
    equalizerState: EqualizerState,
    lyricsEnabled: Boolean,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuraBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AuraTextPrimary
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AuraTextPrimary
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            PlaybackSection(featureSettings, actions)
            SoundSection(smartModeState, equalizerState, actions)
            LyricsSection(featureSettings, lyricsEnabled, actions)
            CapsuleSection(capsuleSettings, actions)
            PermissionsSection()
            LibrarySection(actions, onResetClick = { showResetConfirm = true })
            Text(
                text = "Devil Play ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraTextTertiary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp)
            )
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset settings?") },
            text = {
                Text(
                    "Playback, lyrics and floating capsule settings return to their defaults. " +
                        "Your library, favorites and equalizer are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    actions.onResetSettings()
                }) { Text("Reset", color = AuraSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = AuraTextSecondary)
                }
            },
            containerColor = AuraCard
        )
    }
}

@Composable
private fun PlaybackSection(settings: PlayerFeatureSettings, actions: SettingsActions) {
    SettingsSection("Playback") {
        var speed by remember(settings.playbackSpeed) { mutableFloatStateOf(settings.playbackSpeed) }
        SliderRow(
            title = "Playback speed",
            valueLabel = "${"%.2f".format(speed)}×",
            value = speed,
            range = PlayerPreferences.MIN_PLAYBACK_SPEED..PlayerPreferences.MAX_PLAYBACK_SPEED,
            steps = 14,
            onValueChange = { speed = ((it * 100f).toInt() / 100f) },
            onValueChangeFinished = { actions.onPlaybackSpeedChange(speed) }
        )
        RowDivider()
        SwitchRow(
            title = "Keep original pitch",
            subtitle = if (settings.pitchLocked) {
                "Speed changes without raising or lowering the pitch"
            } else {
                "Pitch follows the playback speed"
            },
            checked = settings.pitchLocked,
            onCheckedChange = actions.onPitchLockChange
        )
        RowDivider()
        var crossfade by remember(settings.crossfadeSeconds) {
            mutableFloatStateOf(settings.crossfadeSeconds.toFloat())
        }
        SliderRow(
            title = "Crossfade",
            subtitle = if (crossfade.toInt() == 0) "Off · gapless playback" else "Blend the end of a track into the next",
            valueLabel = if (crossfade.toInt() == 0) "Off" else "${crossfade.toInt()}s",
            value = crossfade,
            range = 0f..PlayerPreferences.MAX_CROSSFADE_SECONDS.toFloat(),
            steps = PlayerPreferences.MAX_CROSSFADE_SECONDS - 1,
            onValueChange = { crossfade = it.toInt().toFloat() },
            onValueChangeFinished = { actions.onCrossfadeSecondsChange(crossfade.toInt()) }
        )
    }
}

@Composable
private fun SoundSection(
    smartModeState: SmartModeState,
    equalizerState: EqualizerState,
    actions: SettingsActions
) {
    SettingsSection("Sound") {
        SwitchRow(
            title = "Smart Loudness",
            subtitle = when {
                !smartModeState.isSupported -> "Requires Android 9 or later"
                smartModeState.isAnalyzing -> "Analyzing track peak…"
                else -> "Even out volume between tracks (-1 dB peak target)"
            },
            checked = smartModeState.isEnabled,
            enabled = smartModeState.isSupported,
            onCheckedChange = actions.onSmartModeEnabledChange
        )
        RowDivider()
        NavigationRow(
            title = "Equalizer",
            subtitle = if (equalizerState.isEnabled) "On" else "Off",
            onClick = actions.onOpenEqualizer
        )
    }
}

@Composable
private fun LyricsSection(
    settings: PlayerFeatureSettings,
    lyricsEnabled: Boolean,
    actions: SettingsActions
) {
    SettingsSection("Lyrics") {
        SwitchRow(
            title = "Live lyrics",
            subtitle = "Fetch synced lyrics from LRCLIB while playing. Nothing is saved.",
            checked = lyricsEnabled,
            onCheckedChange = actions.onLyricsEnabledChange
        )
        RowDivider()
        var fontSize by remember(settings.lyricsFontSizeSp) {
            mutableFloatStateOf(settings.lyricsFontSizeSp.toFloat())
        }
        SliderRow(
            title = "Text size",
            valueLabel = "${fontSize.toInt()} sp",
            value = fontSize,
            range = PlayerPreferences.MIN_LYRICS_FONT_SP.toFloat()..PlayerPreferences.MAX_LYRICS_FONT_SP.toFloat(),
            steps = PlayerPreferences.MAX_LYRICS_FONT_SP - PlayerPreferences.MIN_LYRICS_FONT_SP - 1,
            onValueChange = { fontSize = it.toInt().toFloat() },
            onValueChangeFinished = { actions.onLyricsFontSizeChange(fontSize.toInt()) }
        )
        RowDivider()
        var offset by remember(settings.lyricsOffsetMs) {
            mutableFloatStateOf(settings.lyricsOffsetMs.toFloat())
        }
        val offsetMs = offset.toInt()
        SliderRow(
            title = "Timing offset",
            subtitle = "Shift highlighting earlier or later for all tracks",
            valueLabel = if (offsetMs > 0) "+${offsetMs} ms" else "$offsetMs ms",
            value = offset,
            range = -PlayerPreferences.MAX_LYRICS_OFFSET_MS.toFloat()..PlayerPreferences.MAX_LYRICS_OFFSET_MS.toFloat(),
            steps = 19,
            onValueChange = { offset = (it.toInt() / 50 * 50).toFloat() },
            onValueChangeFinished = { actions.onLyricsOffsetChange(offset.toInt()) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapsuleSection(settings: CapsuleSettings, actions: SettingsActions) {
    SettingsSection("Floating capsule") {
        SwitchRow(
            title = "Show capsule",
            subtitle = "Floating music controls while you use other apps",
            checked = settings.enabled,
            onCheckedChange = actions.onCapsuleEnabledChange
        )
        RowDivider()
        ChoiceRow(
            title = "Size",
            subtitle = "Small matches Below status bar; larger sizes add room around the camera",
            options = CapsuleSize.entries,
            selected = settings.size,
            label = { it.label },
            enabled = settings.enabled,
            onSelect = actions.onCapsuleSizeChange
        )
        RowDivider()
        ChoiceRow(
            title = "Position",
            subtitle = when (settings.position) {
                CapsulePosition.BELOW_STATUS_BAR -> "Floats under the status bar, always fully visible"
                CapsulePosition.AROUND_CAMERA ->
                    "Wraps the front camera. Allow Capsule above status bar below so the clock can't cover it"
            },
            options = CapsulePosition.entries,
            selected = settings.position,
            label = { it.label },
            enabled = settings.enabled,
            onSelect = actions.onCapsulePositionChange
        )
        RowDivider()
        SwitchRow(
            title = "Swipe to change track",
            subtitle = "Swipe the capsule left for next, right for previous",
            checked = settings.swipeGestures,
            enabled = settings.enabled,
            onCheckedChange = actions.onCapsuleSwipeGesturesChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = rowTitleColor(enabled), fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AuraPrimary.copy(alpha = 0.25f),
                        activeContentColor = AuraTextPrimary,
                        activeBorderColor = AuraPrimary,
                        inactiveContainerColor = AuraCard,
                        inactiveContentColor = AuraTextSecondary,
                        inactiveBorderColor = AuraDivider
                    )
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(PermissionStatus.read(context)) }
    LifecycleResumeEffect(Unit) {
        status = PermissionStatus.read(context)
        onPauseOrDispose { }
    }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { status = PermissionStatus.read(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { status = PermissionStatus.read(context) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }

    SettingsSection("Permissions") {
        PermissionRow(
            title = "Display over other apps",
            subtitle = "Needed for the floating capsule",
            granted = status.overlay,
            onFix = {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RowDivider()
            PermissionRow(
                title = "Notifications",
                subtitle = "Media controls in the shade and lock screen",
                granted = status.notifications,
                onFix = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }
        RowDivider()
        PermissionRow(
            title = "Capsule above status bar",
            subtitle = "Accessibility access lets the capsule cover the clock and icons around the camera and take taps there",
            granted = status.capsuleAccessibility,
            onFix = { showAccessibilityDisclosure = true }
        )
        RowDivider()
        PermissionRow(
            title = "Unrestricted battery",
            subtitle = "Keeps playback and the capsule alive in the background",
            granted = status.batteryUnrestricted,
            onFix = { openBatterySettings(context) }
        )
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Use Accessibility for the capsule?") },
            text = {
                Text(
                    "Android only lets accessibility services draw above the status bar. " +
                        "Devil Play uses this to show the capsule around the front camera and " +
                        "to hide it while the notification shade is open.\n\n" +
                        "It does not read, store or share anything on your screen.\n\n" +
                        "On the next screen, open Devil Play capsule and turn it on."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Continue", color = AuraSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text("Not now", color = AuraTextSecondary)
                }
            },
            containerColor = AuraCard
        )
    }
}

@Composable
private fun LibrarySection(actions: SettingsActions, onResetClick: () -> Unit) {
    SettingsSection("Library & app") {
        NavigationRow(
            title = "Rescan music",
            subtitle = "Look for new or removed audio files",
            onClick = actions.onRescanLibrary
        )
        RowDivider()
        NavigationRow(
            title = "Reset settings",
            subtitle = "Restore playback, lyrics and capsule defaults",
            onClick = onResetClick
        )
    }
}

private data class PermissionStatus(
    val overlay: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val capsuleAccessibility: Boolean
) {
    companion object {
        fun read(context: Context): PermissionStatus {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return PermissionStatus(
                overlay = Settings.canDrawOverlays(context),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED,
                batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName),
                capsuleAccessibility = isCapsuleAccessibilityEnabled(context)
            )
        }
    }
}

private fun isCapsuleAccessibilityEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
                info.resolveInfo.serviceInfo.name == CapsuleAccessibilityService::class.java.name
        }
}

internal fun openBatterySettings(context: Context) {
    val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (batteryIntent.resolveActivity(context.packageManager) != null) {
        context.startActivity(batteryIntent)
    } else {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = AuraSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AuraCard),
        content = content
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = AuraDivider,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

private fun rowTitleColor(enabled: Boolean) = if (enabled) AuraTextPrimary else AuraTextTertiary

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = rowTitleColor(enabled), fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AuraPrimary,
                checkedTrackColor = AuraPrimary.copy(alpha = 0.4f),
                uncheckedThumbColor = AuraTextSecondary,
                uncheckedTrackColor = AuraSurfaceVariant
            )
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    subtitle: String? = null
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = AuraTextPrimary, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(subtitle, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(valueLabel, color = AuraSecondary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it.coerceIn(range.start, range.endInclusive)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AuraSecondary,
                activeTrackColor = AuraSecondary,
                inactiveTrackColor = AuraSurfaceVariant
            )
        )
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AuraTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AuraTextTertiary
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AuraTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = AuraTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (granted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AuraAccentSuccess,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Allowed", color = AuraAccentSuccess, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            TextButton(onClick = onFix) {
                Text("Allow", color = AuraAccentWarning, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
