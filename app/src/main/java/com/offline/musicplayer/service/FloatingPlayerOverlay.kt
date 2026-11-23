package com.offline.musicplayer.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.res.Configuration
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.offline.musicplayer.MainActivity
import com.offline.musicplayer.R
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.preferences.PlayerPreferences
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingPlayerOverlay(
    private val context: Context,
    private var player: ExoPlayer,
    private val preferences: PlayerPreferences,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "FloatingPlayerOverlay"
        private const val ANIMATION_DURATION_MS = 240L
        private const val UNLOCK_CHECK_INTERVAL_MS = 750L
        private const val EXPANDED_UPDATE_INTERVAL_MS = 250L

        // Visible capsule height as a fraction of the camera cutout height.
        // Lower = shorter capsule. The touch area is NOT affected by this.
        private const val CAPSULE_HEIGHT_SCALE = 0.72f
        private const val MIN_CAPSULE_HEIGHT_DP = 18
        private const val CAPSULE_UPWARD_SHIFT_DP = 7
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val artworkExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "floating-player-artwork").apply { isDaemon = true }
    }

    private var root: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var expandedCard: View? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var elapsedView: TextView? = null
    private var durationView: TextView? = null
    private var playPauseView: PlaybackControlButton? = null
    private var seekBar: SeekBar? = null
    private var timeRowView: LinearLayout? = null
    private var liveBadgeView: TextView? = null
    private var userIsSeeking = false
    private var isExpanded = false
    private var isCollapsing = false
    private var isTransitioning = false
    private var dismissedByUser = false
    private var appVisible = false
    private var screenInteractive = powerManager.isInteractive
    private var isAnimatingOut = false
    private var cutoutBounds: Rect? = findDisplayCutout()
    private var loadedArtworkUri: Uri? = null
    private var loadedArtworkBitmap: Bitmap? = null
    private var artworkRequestId = 0
    private val artworkViews = mutableListOf<ImageView>()
    private val waveformViews = mutableListOf<AudioWaveformView>()
    private var windowAnimator: ValueAnimator? = null
    private var collapsedArtworkView: ImageView? = null
    private var artworkRotationAnimator: ObjectAnimator? = null
    private var favoriteButtonView: TextView? = null
    private var favoriteSongId: String? = null
    private var favoriteSongIds: Set<String> = emptySet()

    private val transitionInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // The expanded card is built once, ahead of time, so tapping the capsule
    // doesn't have to create ~30 views on the main thread at that moment.
    private class ExpandedViews(
        val panel: View,
        val title: TextView,
        val artist: TextView,
        val elapsed: TextView,
        val duration: TextView,
        val playPause: PlaybackControlButton,
        val seek: SeekBar,
        val timeRow: LinearLayout,
        val liveBadge: TextView,
        val favorite: TextView,
        val artwork: ImageView
    )

    private var preparedPanel: ExpandedViews? = null
    private var activePanel: ExpandedViews? = null
    private val prebuildPanel = Runnable {
        if (root != null && !isExpanded && !isCollapsing && preparedPanel == null) {
            preparedPanel = buildExpandedCard()
        }
    }

    private val keyguardUnlockCheck = object : Runnable {
        override fun run() {
            screenInteractive = powerManager.isInteractive
            if (!screenInteractive) return
            if (keyguardManager.isKeyguardLocked) {
                handler.postDelayed(this, UNLOCK_CHECK_INTERVAL_MS)
            } else {
                syncVisibility()
            }
        }
    }

    private fun mediaControlButton(
        icon: PlaybackButtonIcon,
        description: String,
        primary: Boolean = false,
        action: () -> Unit
    ) = PlaybackControlButton(context, icon, primary).apply {
        contentDescription = description
        background = if (primary) {
            rippleBackground(circleBackground(0xFF75D9C6.toInt()), 0x66FFFFFF)
        } else {
            rippleOnly(circleBackground(0xFFFFFFFF.toInt()), 0x35FFFFFF)
        }
        attachPressFeedback(this)
        setOnClickListener {
            action()
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    handler.removeCallbacks(keyguardUnlockCheck)
                    syncVisibility()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    syncVisibility()
                    handler.removeCallbacks(keyguardUnlockCheck)
                    handler.postDelayed(keyguardUnlockCheck, UNLOCK_CHECK_INTERVAL_MS)
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    handler.removeCallbacks(keyguardUnlockCheck)
                    syncVisibility()
                }
            }
        }
    }

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            cutoutBounds = findDisplayCutout()
            if (root != null && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                if (isExpanded) collapse() else renderCollapsed()
            }
            syncVisibility()
        }

        override fun onLowMemory() = Unit
    }

    private val updater = object : Runnable {
        override fun run() {
            if (root == null) return
            updatePlaybackState()
            // Only poll while the card is open (progress bar + clock). When collapsed,
            // everything is driven by player events, so there are no periodic wake-ups.
            if (isExpanded) handler.postDelayed(this, EXPANDED_UPDATE_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) {
            if (mediaItem != null) dismissedByUser = false
            artworkRotationAnimator?.cancel()
            artworkRotationAnimator = null
            collapsedArtworkView?.rotation = 0f
            updatePlaybackState()
            syncVisibility()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updatePlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
            syncVisibility()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                dismissedByUser = false
            }
            updatePlaybackState()
            syncVisibility()
        }
    }

    init {
        player.addListener(playerListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenStateReceiver, filter)
        }
        context.registerComponentCallbacks(configurationCallbacks)
        if (screenInteractive && keyguardManager.isKeyguardLocked) {
            handler.postDelayed(keyguardUnlockCheck, UNLOCK_CHECK_INTERVAL_MS)
        }
        serviceScope.launch {
            preferences.favoriteSongIds.collect { ids ->
                favoriteSongIds = ids
                updateFavoriteButton()
            }
        }
    }

    fun updatePlayer(newPlayer: ExoPlayer) {
        if (player === newPlayer) return
        player.removeListener(playerListener)
        player = newPlayer
        player.addListener(playerListener)
        updatePlaybackState()
        updateFavoriteButton()
        syncVisibility()
    }

    fun show(): Boolean {
        dismissedByUser = false
        syncVisibility()
        return Settings.canDrawOverlays(context)
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        syncVisibility()
    }

    private fun syncVisibility() {
        val playbackIsActive = player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        val isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val shouldShow = Settings.canDrawOverlays(context) &&
            !appVisible &&
            screenInteractive &&
            !keyguardManager.isKeyguardLocked &&
            !isLandscape &&
            playbackIsActive &&
            !dismissedByUser

        if (shouldShow) {
            showOverlay()
        } else if (!playbackIsActive && root != null) {
            animateOut()
        } else {
            removeOverlay()
        }
    }

    private fun showOverlay(): Boolean {
        if (root != null) {
            root?.animate()?.cancel()
            root?.apply {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            }
            isAnimatingOut = false
            handler.removeCallbacks(updater)
            handler.post(updater)
            return true
        }
        if (!Settings.canDrawOverlays(context)) return false

        val view = object : FrameLayout(context) {
            // Without this, Android waits ~100ms before showing pressed state / ripple.
            override fun shouldDelayChildPressedState(): Boolean = false
        }.apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Floating music controls"
        }
        val params = WindowManager.LayoutParams(
            pillTouchWidth(),
            pillTouchHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pillTouchLeft()
            y = pillTouchTop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root = view
        windowParams = params
        configureOutsideTap(view)
        renderCollapsed()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            view.setOnApplyWindowInsetsListener { _, insets ->
                insets.displayCutout?.boundingRects?.let(::applyWindowCutout)
                insets
            }
        }

        return try {
            windowManager.addView(view, params)
            view.alpha = 0f
            view.scaleX = 0.82f
            view.scaleY = 0.82f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .start()
            handler.post(updater)
            view.requestApplyInsets()
            view.post {
                if (
                    view.isAttachedToWindow &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                ) {
                    view.rootWindowInsets?.displayCutout?.boundingRects?.let(::applyWindowCutout)
                }
            }
            updatePlaybackState()
            schedulePanelPrebuild()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked before the player could be shown.", e)
            root = null
            windowParams = null
            false
        }
    }

    fun hide() {
        dismissedByUser = true
        removeOverlay()
    }

    private fun removeOverlay() {
        handler.removeCallbacks(updater)
        handler.removeCallbacks(prebuildPanel)
        preparedPanel = null
        activePanel = null
        windowAnimator?.cancel()
        windowAnimator = null
        artworkRotationAnimator?.cancel()
        artworkRotationAnimator = null
        expandedCard?.animate()?.cancel()
        val attachedView = root
        attachedView?.animate()?.cancel()
        root = null
        isAnimatingOut = false
        windowParams = null
        isExpanded = false
        expandedCard = null
        isCollapsing = false
        isTransitioning = false
        titleView = null
        artistView = null
        elapsedView = null
        durationView = null
        playPauseView = null
        favoriteButtonView = null
        favoriteSongId = null
        seekBar = null
        clearRenderedViews()
        loadedArtworkUri = null
        loadedArtworkBitmap = null
        artworkRequestId++
        if (attachedView?.isAttachedToWindow == true) {
            windowManager.removeView(attachedView)
        }
    }

    private fun animateOut() {
        val view = root ?: return
        if (isAnimatingOut) return
        isAnimatingOut = true
        isTransitioning = true
        handler.removeCallbacks(updater)
        windowAnimator?.cancel()
        windowAnimator = null
        view.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                if (root === view) removeOverlay()
            }
            .start()
    }

    fun release() {
        handler.removeCallbacks(keyguardUnlockCheck)
        removeOverlay()
        player.removeListener(playerListener)
        context.unregisterReceiver(screenStateReceiver)
        context.unregisterComponentCallbacks(configurationCallbacks)
        artworkExecutor.shutdownNow()
    }

    private fun renderCollapsed() {
        val container = root ?: return
        isExpanded = false
        expandedCard = null
        titleView = null
        artistView = null
        elapsedView = null
        durationView = null
        playPauseView = null
        seekBar = null
        favoriteButtonView = null
        favoriteSongId = null
        clearRenderedViews()

        val pill = buildPill()
        container.removeAllViews()
        container.addView(
            pill,
            FrameLayout.LayoutParams(pillTouchWidth(), pillTouchHeight()).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        container.setOnTouchListener(null)
        windowParams?.let {
            it.width = pillTouchWidth()
            it.height = pillTouchHeight()
            it.x = pillTouchLeft()
            it.y = pillTouchTop()
            updateWindowLayout(container, it)
        }
        updatePlaybackState()
    }

    private fun expand() {
        val container = root ?: return
        if (isExpanded || isCollapsing || isTransitioning) return

        val capsule = container.getChildAt(0) ?: return
        isTransitioning = true
        val toWidth = expandedWidth()
        val toHeight = expandedHeight()
        val toX = (displayWidth() - toWidth) / 2
        val views = preparedPanel ?: buildExpandedCard()
        preparedPanel = null
        clearRenderedViews()
        attachExpandedViews(views)
        val panel = views.panel
        panel.alpha = 0f
        panel.translationY = -dp(6).toFloat()
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f

        isExpanded = true
        expandedCard = panel
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                toWidth - dp(24),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(12)
                topMargin = expandedCardTop()
            }
        )
        configureOutsideTap(container)
        setWatchOutsideTouch(true)
        resizeWindow(toWidth, toHeight, toX, capsule)
        capsule.animate()
            .withLayer()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .withEndAction {
                if (root === container && isExpanded) container.removeView(capsule)
            }
            .start()
        panel.animate()
            .withLayer()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .withEndAction {
                if (root === container && isExpanded) isTransitioning = false
            }
            .start()
        updatePlaybackState()
        handler.removeCallbacks(updater)
        handler.postDelayed(updater, EXPANDED_UPDATE_INTERVAL_MS)
    }

    private fun collapse() {
        val container = root ?: return
        if (!isExpanded || isCollapsing || isTransitioning) return
        isTransitioning = true
        isCollapsing = true
        windowAnimator?.cancel()
        windowAnimator = null
        clearRenderedViews()
        val capsule = buildPill()
        capsule.alpha = 0f
        capsule.scaleX = 0.96f
        capsule.scaleY = 0.96f
        val currentWindowX = windowParams?.x ?: pillTouchLeft()
        container.addView(
            capsule,
            FrameLayout.LayoutParams(pillTouchWidth(), pillTouchHeight()).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pillTouchLeft() - currentWindowX
            }
        )
        capsule.animate()
            .withLayer()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .start()
        val panel = expandedCard
        if (panel == null) {
            finishCollapse(container, capsule)
            return
        }
        panel.animate()
            .withLayer()
            .alpha(0f)
            .translationY(-dp(6).toFloat())
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(transitionInterpolator)
            .withEndAction {
                if (root === container) finishCollapse(container, capsule)
            }
            .start()
    }

    private fun finishCollapse(container: FrameLayout, capsule: View) {
        if (root !== container) return
        val toWidth = pillTouchWidth()
        val toHeight = pillTouchHeight()
        val toX = pillTouchLeft()

        isExpanded = false
        isCollapsing = false
        isTransitioning = false
        expandedCard = null
        titleView = null
        artistView = null
        elapsedView = null
        durationView = null
        playPauseView = null
        seekBar = null
        favoriteButtonView = null
        favoriteSongId = null
        userIsSeeking = false
        container.removeAllViews()
        // The detached card is reused next time instead of being rebuilt.
        preparedPanel = activePanel ?: preparedPanel
        activePanel = null
        container.addView(
            capsule,
            FrameLayout.LayoutParams(toWidth, toHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pillTouchLeft() - (windowParams?.x ?: toX)
            }
        )
        container.setOnTouchListener(null)
        setWatchOutsideTouch(false)
        resizeWindow(toWidth, toHeight, toX, capsule)
        updatePlaybackState()
        schedulePanelPrebuild()
    }

    private fun buildPill(): View {
        val pill = CapsulePill(context).apply {
            background = capsuleBackground()
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            translationY = -dp(CAPSULE_UPWARD_SHIFT_DP).toFloat()
            elevation = dp(10).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Expand or collapse floating music controls"
            setOnClickListener {
                if (isExpanded) collapse() else expand()
            }
        }

        val tapTargetSize = dp(32)
        val touchHeight = pillTouchHeight()
        val visibleHeight = capsuleVisibleHeight()
        val artSize = min(dp(16), visibleHeight - dp(4))
        val waveformHeight = min(dp(16), visibleHeight - dp(6))
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circleBackground(0xFF282828.toInt())
            clipToOutline = true
            setImageResource(R.drawable.ic_launcher_foreground)
            isClickable = false
        }
        val artworkTarget = FrameLayout(context).apply {
            background = capsuleTargetRipple(tapTargetSize)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Expand floating music controls"
            setOnClickListener {
                if (isExpanded) collapse() else expand()
            }
            addView(
                image,
                FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER)
            )
        }
        collapsedArtworkView = image
        updateArtworkRotation()
        artworkViews.add(image)
        val cameraLeft = cutoutBounds?.left?.minus(pillTouchLeft())
            ?: (pillTouchWidth() / 2 - dp(6))
        val cameraRight = cutoutBounds?.right?.minus(pillTouchLeft())
            ?: (pillTouchWidth() / 2 + dp(6))
        val artLeft = cameraLeft - tapTargetSize - dp(1)
        pill.addView(
            artworkTarget,
            FrameLayout.LayoutParams(tapTargetSize, touchHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = artLeft.coerceIn(
                    dp(1),
                    max(dp(1), pillTouchWidth() - tapTargetSize - dp(1))
                )
                topMargin = 0
            }
        )

        val waveform = AudioWaveformView(context).apply {
            isPlaying = player.isPlaying
        }
        val waveformTarget = FrameLayout(context).apply {
            background = capsuleTargetRipple(tapTargetSize)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Expand floating music controls"
            setOnClickListener {
                if (isExpanded) collapse() else expand()
            }
            addView(
                waveform,
                FrameLayout.LayoutParams(dp(14), waveformHeight, Gravity.CENTER)
            )
        }
        waveformViews.add(waveform)
        val waveformLeft = cameraRight + dp(1)
        pill.addView(
            waveformTarget,
            FrameLayout.LayoutParams(tapTargetSize, touchHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = waveformLeft.coerceIn(
                    dp(1),
                    max(dp(1), pillTouchWidth() - tapTargetSize - dp(1))
                )
                topMargin = 0
            }
        )
        return pill
    }

    private fun buildExpandedCard(): ExpandedViews {
        val panel = object : LinearLayout(context) {
            // Lets the seek bar and buttons react instantly (no touch-slop dead zone).
            override fun shouldDelayChildPressedState(): Boolean = false
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = cardBackground()
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedArtworkBackground()
            clipToOutline = true
            setImageResource(R.drawable.ic_launcher_foreground)
            contentDescription = "Open music player"
            setOnClickListener {
                openMusicPlayer()
            }
        }
        header.addView(image, LinearLayout.LayoutParams(dp(48), dp(48)))

        val metadata = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val title = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = "Open the current track in Devil Play"
            setOnClickListener {
                openMusicPlayer()
            }
        }
        val artist = TextView(context).apply {
            setTextColor(0xFFB7B7B7.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metadata.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        metadata.addView(artist, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        header.addView(
            metadata,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val favorite = controlButton("♡", "Add to favorites", 22f) {
            toggleFavorite()
        }
        header.addView(favorite, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        val liveBadge = TextView(context).apply {
            text = "● LIVE"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFFF5252.toInt())
            visibility = View.GONE
        }
        panel.addView(liveBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(24)
        ))

        val progress = SeekBar(context).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF75D9C6.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    userIsSeeking = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val duration = player.duration
                    if (duration > 0) player.seekTo(duration * bar.progress / 1000)
                    userIsSeeking = false
                    updatePlaybackState()
                }

                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val total = player.duration
                        if (total > 0) {
                            setTextIfChanged(elapsedView, formatTime(total * value / 1000))
                        }
                    }
                }
            })
        }
        panel.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(36)
        ).apply {
            topMargin = dp(2)
        })

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val elapsed = TextView(context).apply {
            textSize = 10f
            setTextColor(0xFFBBBBBB.toInt())
        }
        val duration = TextView(context).apply {
            textSize = 10f
            gravity = Gravity.END
            setTextColor(0xFFBBBBBB.toInt())
        }
        timeRow.addView(elapsed, LinearLayout.LayoutParams(0, dp(16), 1f))
        timeRow.addView(duration, LinearLayout.LayoutParams(0, dp(16), 1f))
        panel.addView(timeRow)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(
            mediaControlButton(PlaybackButtonIcon.PREVIOUS, "Previous track") {
                skipToPreviousTrack()
            },
            LinearLayout.LayoutParams(dp(48), dp(58)).apply {
                rightMargin = dp(18)
            }
        )
        val playPause = mediaControlButton(PlaybackButtonIcon.PLAY_PAUSE, "Play", true) {
            if (player.isPlaying) player.pause() else player.play()
            updatePlaybackState()
        }
        controls.addView(playPause, LinearLayout.LayoutParams(dp(58), dp(58)))
        controls.addView(
            mediaControlButton(PlaybackButtonIcon.NEXT, "Next track") {
                player.seekToNextMediaItem()
            },
            LinearLayout.LayoutParams(dp(48), dp(58)).apply {
                leftMargin = dp(18)
            }
        )
        panel.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58)
        ))

        panel.setOnClickListener { }
        return ExpandedViews(
            panel = panel,
            title = title,
            artist = artist,
            elapsed = elapsed,
            duration = duration,
            playPause = playPause,
            seek = progress,
            timeRow = timeRow,
            liveBadge = liveBadge,
            favorite = favorite,
            artwork = image
        )
    }

    /** Wires a (new or reused) card into the overlay's live state. */
    private fun attachExpandedViews(views: ExpandedViews) {
        titleView = views.title
        artistView = views.artist
        elapsedView = views.elapsed
        durationView = views.duration
        playPauseView = views.playPause
        seekBar = views.seek
        timeRowView = views.timeRow
        liveBadgeView = views.liveBadge
        favoriteButtonView = views.favorite
        activePanel = views
        // A reused card may still show the previous track's artwork.
        val bitmap = loadedArtworkBitmap
        if (bitmap != null) {
            views.artwork.setImageBitmap(bitmap)
            views.artwork.tag = bitmap
        } else {
            views.artwork.setImageResource(R.drawable.ic_launcher_foreground)
            views.artwork.tag = null
        }
        artworkViews.add(views.artwork)
        // Start the bar at the right spot instead of sweeping in from an old position.
        views.seek.progress = currentSeekProgress()
    }

    private fun currentSeekProgress(): Int {
        val duration = player.duration.takeIf { it > 0L } ?: return 0
        val position = player.currentPosition.coerceIn(0L, duration)
        return (position * 1000L / duration).toInt().coerceIn(0, 1000)
    }

    private fun schedulePanelPrebuild() {
        handler.removeCallbacks(prebuildPanel)
        if (preparedPanel == null) handler.postDelayed(prebuildPanel, 500L)
    }

    /** Outside-touch events are only needed while the card is open. */
    private fun setWatchOutsideTouch(enabled: Boolean) {
        val params = windowParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
    }

    /** Animates the overlay bounds with its contents to avoid a snap on Android 12+. */
    private fun resizeWindow(toWidth: Int, toHeight: Int, toX: Int, anchoredPill: View?) {
        animateWindow(toWidth, toHeight, toX, anchoredPill)
    }

    private fun configureOutsideTap(container: FrameLayout) {
        container.setOnTouchListener { _, event ->
            if (!isExpanded) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_OUTSIDE -> {
                    collapse()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val panel = expandedCard
                    val insideCard = panel != null &&
                        event.x >= panel.left && event.x <= panel.right &&
                        event.y >= panel.top && event.y <= panel.bottom
                    if (!insideCard) collapse()
                    true
                }
                else -> true
            }
        }
    }

    private fun animateWindow(
        toWidth: Int,
        toHeight: Int,
        toX: Int,
        anchoredPill: View?
    ) {
        val container = root ?: return
        val params = windowParams ?: return
        windowAnimator?.cancel()
        val fromWidth = params.width
        val fromHeight = params.height
        val fromX = params.x
        if (fromWidth == toWidth && fromHeight == toHeight && fromX == toX) return
        windowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = transitionInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                params.width = (fromWidth + (toWidth - fromWidth) * fraction).toInt()
                params.height =
                    (fromHeight + (toHeight - fromHeight) * fraction).toInt()
                params.x = (fromX + (toX - fromX) * fraction).toInt()
                (anchoredPill?.takeIf { it.parent === container }?.layoutParams
                    as? FrameLayout.LayoutParams)?.let {
                    it.leftMargin = (pillTouchLeft() - params.x).coerceAtLeast(0)
                    anchoredPill?.layoutParams = it
                }
                updateWindowLayout(container, params)
            }
            start()
        }
    }

    private fun updateWindowLayout(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        if (!view.isAttachedToWindow) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked while updating the player.", e)
            hide()
        }
    }

    private fun updatePlaybackState() {
        val metadata = player.mediaMetadata
        setTextIfChanged(
            titleView,
            metadata.title?.toString()?.takeIf(String::isNotBlank) ?: "Nothing playing"
        )
        setTextIfChanged(artistView, metadata.artist?.toString().orEmpty())
        playPauseView?.apply {
            isPlaying = player.isPlaying
            contentDescription = if (player.isPlaying) "Pause" else "Play"
        }
        waveformViews.forEach { it.isPlaying = player.isPlaying }
        updateArtworkRotation()
        updateFavoriteButton()

        val isLive = metadata.extras?.getBoolean(Song.EXTRA_IS_LIVE_STREAM)
            ?: player.currentMediaItem?.mediaId?.startsWith("radio:")
            ?: false
        seekBar?.visibility = if (isLive) View.GONE else View.VISIBLE
        timeRowView?.visibility = if (isLive) View.GONE else View.VISIBLE
        liveBadgeView?.visibility = if (isLive) View.VISIBLE else View.GONE
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceIn(0L, duration)
        if (!userIsSeeking) {
            val target = if (duration > 0L) {
                (position * 1000L / duration).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            seekBar?.setProgress(target, true)
            setTextIfChanged(elapsedView, formatTime(position))
        }
        setTextIfChanged(durationView, formatTime(duration))
        updateArtwork(metadata.artworkUri)
    }

    private fun updateArtwork(uri: Uri?) {
        if (uri == loadedArtworkUri) {
            loadedArtworkBitmap?.let { bitmap ->
                // Only touch the view when it doesn't already show this bitmap.
                // Re-setting it every tick caused needless redraws while rotating.
                artworkViews.forEach {
                    if (it.tag !== bitmap) {
                        it.setImageBitmap(bitmap)
                        it.tag = bitmap
                    }
                }
            }
            return
        }
        loadedArtworkUri = uri
        loadedArtworkBitmap = null
        val requestId = ++artworkRequestId
        artworkViews.forEach { it.setImageResource(R.drawable.ic_launcher_foreground) }
        if (uri == null) return

        artworkExecutor.execute {
            val bitmap = decodeArtwork(uri)
            handler.post {
                if (requestId != artworkRequestId || loadedArtworkUri != uri) return@post
                if (bitmap == null) {
                    Log.w(TAG, "No readable album artwork was available for $uri.")
                } else {
                    loadedArtworkBitmap = bitmap
                    artworkViews.forEach { it.setImageBitmap(bitmap) }
                }
            }
        }
    }

    private fun decodeArtwork(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null

            var sampleSize = 1
            val maxDimension = max(bounds.outWidth, bounds.outHeight)
            while (maxDimension / sampleSize > dp(128)) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to read album artwork at $uri.", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission to read album artwork at $uri was denied.", e)
            null
        }
    }

    private fun applyWindowCutout(rectangles: List<Rect>) {
        val displayHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            context.resources.displayMetrics.heightPixels
        }
        val candidate = rectangles
            .filter { it.width() > 0 && it.height() > 0 && it.top < dp(140) }
            .maxByOrNull { it.width().toLong() * it.height() }
            ?: return
        val bounds = Rect(candidate)

        if (bounds.centerX() !in 0..displayWidth() || bounds.bottom > displayHeight) return
        if (cutoutBounds == bounds) return
        cutoutBounds = bounds

        if (!isExpanded) {
            renderCollapsed()
        } else collapse()
    }

    private fun findDisplayCutout(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.displayCutout
        } else {
            root?.rootWindowInsets?.displayCutout
        }
        return cutout?.boundingRects
            ?.filter { it.width() > 0 && it.height() > 0 && it.top < dp(140) }
            ?.maxByOrNull { it.width().toLong() * it.height() }
            ?.let { Rect(it) }
    }

    private fun displayWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            context.resources.displayMetrics.widthPixels
        }

    private fun pillWidth(): Int {
        val cameraWidth = cutoutBounds?.width()?.coerceAtLeast(dp(12)) ?: dp(12)
        return min(displayWidth() - dp(16), cameraWidth + dp(64))
    }

    private fun pillHeight(): Int =
        max(dp(26), (cutoutBounds?.height() ?: 0) + dp(4))

    private fun pillTouchWidth(): Int =
        min(displayWidth(), pillWidth() + dp(16))

    private fun pillTouchHeight(): Int = pillHeight() + dp(12)

    private fun pillTouchLeft(): Int =
        (pillLeft() - dp(8)).coerceIn(0, max(0, displayWidth() - pillTouchWidth()))

    private fun pillTouchTop(): Int =
        cutoutBounds?.top?.minus(dp(6))?.coerceAtLeast(0)
            ?: max(0, pillTop() - dp(6))

    /**
     * Height of the black capsule that is actually drawn. Restored to the original slim height.
     */
    private fun capsuleVisibleHeight(): Int {
        val cutoutHeight = cutoutBounds?.height() ?: 0
        val target = (max(dp(26), cutoutHeight) * CAPSULE_HEIGHT_SCALE).toInt()
        return target.coerceIn(dp(MIN_CAPSULE_HEIGHT_DP), pillHeight())
    }

    /** Circular ripple sized to the visible capsule so it never spills outside it. */
    private fun capsuleTargetRipple(targetWidth: Int): Drawable {
        val diameter = (capsuleVisibleHeight() - dp(2)).coerceAtLeast(dp(12))
        val insetX = ((targetWidth - diameter) / 2).coerceAtLeast(0)
        val insetY = ((pillTouchHeight() - diameter) / 2).coerceAtLeast(0)
        return InsetDrawable(
            rippleOnly(circleBackground(0xFFFFFFFF.toInt()), 0x45FFFFFF),
            insetX,
            insetY,
            insetX,
            insetY
        )
    }

    private fun pillLeft(): Int =
        (cutoutBounds?.centerX() ?: displayWidth() / 2)
            .minus(pillWidth() / 2)
            .coerceIn(0, max(0, displayWidth() - pillWidth()))

    private fun pillTop(): Int =
        max(0, (cutoutBounds?.top ?: dp(8)) - dp(5))

    private fun expandedWidth(): Int = displayWidth() - dp(24)

    private fun expandedCardTop(): Int =
        (pillTouchHeight() - capsuleVisibleHeight()) / 2 +
            capsuleVisibleHeight() + dp(8)

    private fun expandedHeight(): Int = expandedCardTop() + dp(196)

    private fun openMusicPlayer() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        }
        context.startActivity(intent)
    }

    private fun updateArtworkRotation() {
        val artwork = collapsedArtworkView ?: return
        if (!player.isPlaying) {
            artworkRotationAnimator?.pause()
            return
        }
        val currentAnimator = artworkRotationAnimator
        if (currentAnimator?.target === artwork && currentAnimator.isStarted) {
            if (currentAnimator.isPaused) currentAnimator.resume()
            return
        }
        currentAnimator?.cancel()
        artworkRotationAnimator = ObjectAnimator.ofFloat(
            artwork,
            View.ROTATION,
            artwork.rotation,
            artwork.rotation + 360f
        ).apply {
            duration = 8_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun updateFavoriteButton() {
        val button = favoriteButtonView ?: return
        val id = player.currentMediaItem?.mediaId
        favoriteSongId = id
        val isFavorite = id != null && id in favoriteSongIds
        button.text = if (isFavorite) "♥" else "♡"
        button.contentDescription = if (isFavorite) {
            "Remove from favorites"
        } else {
            "Add to favorites"
        }
        button.setTextColor(if (isFavorite) 0xFFFF5B75.toInt() else 0xFFFFFFFF.toInt())
        button.isEnabled = id != null
    }

    private fun toggleFavorite() {
        val id = favoriteSongId ?: return
        serviceScope.launch {
            try {
                val isFavorite = preferences.toggleFavoriteSong(id)
                favoriteSongIds = if (isFavorite) favoriteSongIds + id else favoriteSongIds - id
                updateFavoriteButton()
            } catch (e: IOException) {
                Log.e(TAG, "Unable to update the favorite state for track $id.", e)
            }
        }
    }

    private fun skipToPreviousTrack() {
        if (player.mediaItemCount == 0) return
        val previousIndex = player.previousMediaItemIndex
        if (previousIndex >= 0 && previousIndex != player.currentMediaItemIndex) {
            player.seekToDefaultPosition(previousIndex)
        } else {
            player.seekToDefaultPosition()
        }
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(90L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
            }
            .start()
    }

    private enum class PlaybackButtonIcon {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT
    }

    private inner class PlaybackControlButton(
        context: Context,
        private val icon: PlaybackButtonIcon,
        private val primary: Boolean
    ) : View(context) {
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        var isPlaying: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
                animate().cancel()
                scaleX = 0.9f
                scaleY = 0.9f
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .start()
            }

        init {
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width, height) / 48f
            val centerX = width / 2f
            val centerY = height / 2f
            iconPaint.color = if (primary) 0xFF102824.toInt() else 0xFFF0F5F4.toInt()

            when (icon) {
                PlaybackButtonIcon.PREVIOUS -> {
                    canvas.drawRoundRect(
                        centerX - 9f * scale,
                        centerY - 7f * scale,
                        centerX - 6f * scale,
                        centerY + 7f * scale,
                        1.5f * scale,
                        1.5f * scale,
                        iconPaint
                    )
                    drawTriangle(
                        canvas,
                        centerX - 5f * scale,
                        centerY,
                        centerX + 6f * scale,
                        centerY - 7f * scale,
                        centerX + 6f * scale,
                        centerY + 7f * scale
                    )
                }
                PlaybackButtonIcon.NEXT -> {
                    drawTriangle(
                        canvas,
                        centerX + 5f * scale,
                        centerY,
                        centerX - 6f * scale,
                        centerY - 7f * scale,
                        centerX - 6f * scale,
                        centerY + 7f * scale
                    )
                    canvas.drawRoundRect(
                        centerX + 6f * scale,
                        centerY - 7f * scale,
                        centerX + 9f * scale,
                        centerY + 7f * scale,
                        1.5f * scale,
                        1.5f * scale,
                        iconPaint
                    )
                }
                PlaybackButtonIcon.PLAY_PAUSE -> {
                    if (isPlaying) {
                        canvas.drawRoundRect(
                            centerX - 6f * scale,
                            centerY - 8f * scale,
                            centerX - 2f * scale,
                            centerY + 8f * scale,
                            2f * scale,
                            2f * scale,
                            iconPaint
                        )
                        canvas.drawRoundRect(
                            centerX + 2f * scale,
                            centerY - 8f * scale,
                            centerX + 6f * scale,
                            centerY + 8f * scale,
                            2f * scale,
                            2f * scale,
                            iconPaint
                        )
                    } else {
                        drawTriangle(
                            canvas,
                            centerX + 7f * scale,
                            centerY,
                            centerX - 6f * scale,
                            centerY - 9f * scale,
                            centerX - 6f * scale,
                            centerY + 9f * scale
                        )
                    }
                }
            }
        }

        private fun drawTriangle(
            canvas: Canvas,
            tipX: Float,
            tipY: Float,
            baseTopX: Float,
            baseTopY: Float,
            baseBottomX: Float,
            baseBottomY: Float
        ) {
            val path = android.graphics.Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseTopX, baseTopY)
                lineTo(baseBottomX, baseBottomY)
                close()
            }
            canvas.drawPath(path, iconPaint)
        }
    }

    private inner class CapsulePill(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private val swipeThreshold = max(touchSlop * 2, dp(30))

        // Show pressed state / ripple instantly instead of after the tap timeout.
        override fun shouldDelayChildPressedState(): Boolean = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (isTransitioning) return true
            if (!isExpanded) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> animatePressed(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> animatePressed(false)
                }
            }
            return super.dispatchTouchEvent(event)
        }

        private fun animatePressed(pressed: Boolean) {
            val target = if (pressed) 0.94f else 1f
            animate().cancel()
            animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(if (pressed) 80L else 160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        private fun isHorizontalSwipe(event: MotionEvent): Boolean {
            val dx = event.x - downX
            val dy = event.y - downY
            return abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.35f
        }

        // Fire as soon as the swipe is recognised instead of waiting for release.
        private fun commitSwipe(event: MotionEvent) {
            if (event.x < downX) player.seekToNextMediaItem()
            else skipToPreviousTrack()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animatePressed(false)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (isExpanded) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    swiping = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isHorizontalSwipe(event)) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        commitSwipe(event)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isExpanded) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return super.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Swipes that start over the camera gap never reach the intercept
                    // callback, so detect them here too.
                    if (!swiping && isHorizontalSwipe(event)) {
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        swiping = true
                        commitSwipe(event)
                    }
                    if (swiping) return true
                }
                MotionEvent.ACTION_UP -> {
                    if (swiping) {
                        swiping = false
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun controlButton(
        label: String,
        description: String,
        textSize: Float,
        action: () -> Unit
    ) = TextView(context).apply {
        text = label
        this.textSize = textSize
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        contentDescription = description
        background = rippleBackground(circleBackground(0xFF263238.toInt()), 0x45FFFFFF)
        isClickable = true
        isFocusable = true
        attachPressFeedback(this)
        setOnClickListener {
            action()
        }
    }

    private fun setTextIfChanged(view: TextView?, text: String) {
        if (view != null && view.text.toString() != text) view.text = text
    }

    /** Quick scale-down on press, scale-up on release. Does not consume the touch. */
    private fun attachPressFeedback(view: View, pressedScale: Float = 0.9f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .setDuration(70L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            false
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(40).toFloat()
        setColor(0xFF000000.toInt())
        setStroke(dp(1), 0xFF242424.toInt())
    }

    private fun capsuleBackground(): Drawable {
        val visibleHeight = capsuleVisibleHeight()
        val verticalInset = ((pillTouchHeight() - visibleHeight) / 2).coerceAtLeast(0)
        val pill = rippleBackground(pillBackground(), 0x35FFFFFF)
        return InsetDrawable(pill, dp(8), verticalInset, dp(8), verticalInset)
    }

    private fun cardBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(26).toFloat()
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(0xF0263433.toInt(), 0xF012171B.toInt())
        setStroke(dp(1), 0x664A8C80.toInt())
    }

    private fun rippleBackground(content: Drawable, rippleColor: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    private fun rippleOnly(mask: Drawable, rippleColor: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)

    private fun circleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedArtworkBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(0xFF282828.toInt())
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun clearRenderedViews() {
        waveformViews.forEach { it.isPlaying = false }
        artworkRotationAnimator?.cancel()
        artworkRotationAnimator = null
        collapsedArtworkView = null
        artworkViews.clear()
        waveformViews.clear()
    }

    private class AudioWaveformView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF75D9C6.toInt()
            style = Paint.Style.FILL
        }
        var isPlaying: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val barCount = 4
            val barWidth = width / (barCount * 2f)
            val gap = barWidth
            val centerY = height / 2f
            val phase = SystemClock.uptimeMillis() / 110f
            for (index in 0 until barCount) {
                val wave = if (isPlaying) {
                    (0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(phase + index * 0.9f)))
                } else {
                    0.25f + index * 0.04f
                }
                val barHeight = (height * wave).coerceAtLeast(dp(context, 3).toFloat())
                val left = index * (barWidth + gap)
                canvas.drawRoundRect(
                    left,
                    centerY - barHeight / 2f,
                    left + barWidth,
                    centerY + barHeight / 2f,
                    barWidth / 2f,
                    barWidth / 2f,
                    paint
                )
            }
            if (isPlaying) postInvalidateDelayed(33L)
        }

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
