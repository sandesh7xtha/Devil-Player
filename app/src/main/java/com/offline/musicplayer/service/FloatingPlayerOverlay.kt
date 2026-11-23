package com.offline.musicplayer.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
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
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
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
import com.offline.musicplayer.data.model.CapsulePosition
import com.offline.musicplayer.data.model.CapsuleSize
import com.offline.musicplayer.data.model.CapsuleSettings
import com.offline.musicplayer.data.model.Song
import com.offline.musicplayer.data.preferences.PlayerPreferences
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        private const val EXPAND_DURATION_MS = 400L
        private const val COLLAPSE_DURATION_MS = 320L
        private const val CARD_CORNER_RADIUS_DP = 26
        // Swipe feedback: capsule content slides this far out and back in from the other side.
        private const val SKIP_SLIDE_DP = 14
        private const val SKIP_SLIDE_OUT_MS = 80L
        private const val SKIP_SLIDE_IN_MS = 140L
        // The shape has elevation for its shadow; content must sit higher or it draws below it.
        private const val SHAPE_ELEVATION_DP = 10
        // Extra wait after a window's frame is committed before relying on it being on screen.
        private const val HANDOFF_MARGIN_MS = 34L
        private const val CONTENT_ELEVATION_DP = 11
        private const val UNLOCK_CHECK_INTERVAL_MS = 750L
        // ACTION_USER_PRESENT still covers later unlocks; polling is only a short fallback.
        private const val MAX_UNLOCK_CHECKS = 40
        private const val EXPANDED_UPDATE_INTERVAL_MS = 250L

        private const val CAPSULE_WAVE_SLOT_DP = 30
        private const val CAPSULE_NO_CAMERA_GAP_DP = 36
        private const val CAPSULE_CAMERA_GAP_DP = 4
        // Extra touch area around the visible capsule.
        private const val TOUCH_PAD_X_DP = 8
        private const val TOUCH_PAD_Y_DP = 6
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
    // Display metrics come from WindowManager (an IPC on Android 11-13), so they are
    // cached and only refreshed on configuration changes or when the overlay is shown.
    private var displayBounds: Rect = queryDisplayBounds()
    private var cutoutBounds: Rect? = findDisplayCutout()
    private var statusBarHeight: Int = queryStatusBarHeight()
    private var overlayAllowed = Settings.canDrawOverlays(context)
    private var capsuleSettings = CapsuleSettings()
    private var unlockChecks = 0
    private var favoritesJob: Job? = null
    // The WindowManager the current window was added with; accessibility and app overlays
    // must be updated and removed through the same one.
    private var attachedWindowManager: WindowManager? = null
    private val hostListener: () -> Unit = ::onWindowHostChanged
    private var settingsJob: Job? = null
    private var loadedArtworkUri: Uri? = null
    private var loadedArtworkBitmap: Bitmap? = null
    private var artworkRequestId = 0
    private val artworkViews = mutableListOf<ImageView>()
    private val waveformViews = mutableListOf<AudioWaveformView>()
    private var favoriteButtonView: TextView? = null
    private var favoriteSongId: String? = null
    private var favoriteSongIds: Set<String> = emptySet()

    // A tap that arrives mid-transition runs once the transition ends instead of being lost.
    private var pendingToggle = false
    // Hero transition: one shape morphs between the capsule (0) and the card (1).
    private var morphView: MorphShapeView? = null
    private var morphAnimator: ValueAnimator? = null
    private var morphProgress = 0f
    private val cardRectOnScreen = Rect()
    // Capsule content inside the card window while it morphs to or from the capsule.
    private var collapsedPill: View? = null
    // The card lives in its own window so the capsule window never moves or resizes; a
    // window that moves while visible can draw one frame at the wrong place.
    private var cardRoot: FrameLayout? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private val heroInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val skipOutInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)

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
                if (++unlockChecks < MAX_UNLOCK_CHECKS) {
                    handler.postDelayed(this, UNLOCK_CHECK_INTERVAL_MS)
                }
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
                    refreshOverlayPermission()
                    syncVisibility()
                    scheduleUnlockCheck()
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    handler.removeCallbacks(keyguardUnlockCheck)
                    refreshOverlayPermission()
                    syncVisibility()
                }
            }
        }
    }

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            refreshDisplayGeometry()
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
            scheduleUnlockCheck()
        }
        favoritesJob = serviceScope.launch {
            preferences.favoriteSongIds.collect { ids ->
                favoriteSongIds = ids
                updateFavoriteButton()
            }
        }
        settingsJob = serviceScope.launch {
            preferences.capsuleSettings.collect(::applyCapsuleSettings)
        }
        CapsuleWindowHost.addListener(hostListener)
    }

    /** Re-adds the window in the right layer when accessibility access changes. */
    private fun onWindowHostChanged() {
        val hostManager = CapsuleWindowHost.windowManager
        if (root != null && attachedWindowManager !== (hostManager ?: windowManager)) {
            removeOverlay()
        }
        syncVisibility()
    }

    private fun applyCapsuleSettings(settings: CapsuleSettings) {
        val previous = capsuleSettings
        capsuleSettings = settings
        if (previous.enabled != settings.enabled) {
            if (settings.enabled) dismissedByUser = false
            syncVisibility()
        }
        if ((previous.position != settings.position || previous.size != settings.size) &&
            root != null
        ) {
            // The window moves, so rebuild it at the new place.
            removeOverlay()
            syncVisibility()
        }
    }

    private fun scheduleUnlockCheck() {
        unlockChecks = 0
        handler.removeCallbacks(keyguardUnlockCheck)
        handler.postDelayed(keyguardUnlockCheck, UNLOCK_CHECK_INTERVAL_MS)
    }

    private fun refreshOverlayPermission() {
        overlayAllowed = Settings.canDrawOverlays(context)
    }

    private fun refreshDisplayGeometry() {
        displayBounds = queryDisplayBounds()
        cutoutBounds = findDisplayCutout()
        statusBarHeight = queryStatusBarHeight()
    }

    /** The camera to wrap around, or null when the capsule sits below the status bar. */
    private val cameraAnchor: Rect?
        get() = cutoutBounds.takeIf { capsuleSettings.position == CapsulePosition.AROUND_CAMERA }

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
        refreshOverlayPermission()
        syncVisibility()
        return overlayAllowed
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        // Leaving the app is when the user may have changed the overlay permission.
        if (!visible) refreshOverlayPermission()
        syncVisibility()
    }

    private fun syncVisibility() {
        val playbackIsActive = player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        val isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val canDraw = overlayAllowed || CapsuleWindowHost.windowManager != null
        val shouldShow = canDraw &&
            capsuleSettings.enabled &&
            !CapsuleWindowHost.shadeOpen &&
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
        val existing = root
        if (existing != null) {
            // Only interrupt a fade-out; cancelling the fade-in on every player event snaps it.
            if (isAnimatingOut) {
                existing.animate().cancel()
                existing.alpha = 1f
                existing.scaleX = 1f
                existing.scaleY = 1f
                isAnimatingOut = false
                isTransitioning = false
            }
            handler.removeCallbacks(updater)
            handler.post(updater)
            return true
        }
        // Accessibility overlays sit above the status bar; app overlays sit below it.
        val hostManager = CapsuleWindowHost.windowManager
        val targetManager = hostManager ?: windowManager
        val windowType = if (hostManager != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (hostManager == null && !overlayAllowed) return false
        refreshDisplayGeometry()

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
            windowType,
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
            targetManager.addView(view, params)
            attachedWindowManager = targetManager
            view.alpha = 0f
            view.animate()
                .alpha(1f)
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
            overlayAllowed = false
            root = null
            windowParams = null
            false
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Accessibility service went away before the capsule could be shown.", e)
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
        removeCardWindow()
        handler.removeCallbacks(updater)
        handler.removeCallbacks(prebuildPanel)
        preparedPanel = null
        activePanel = null
        pendingToggle = false
        morphAnimator?.cancel()
        morphAnimator = null
        morphProgress = 0f
        morphView = null
        collapsedPill = null
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
        // The decoded artwork is kept so the next show doesn't decode it again.
        clearRenderedViews()
        val manager = attachedWindowManager
        attachedWindowManager = null
        if (attachedView?.isAttachedToWindow == true) {
            try {
                manager?.removeView(attachedView)
            } catch (e: IllegalArgumentException) {
                // The accessibility service disconnected and took its window with it.
                Log.w(TAG, "Capsule window was already removed.", e)
            }
        }
    }

    private fun animateOut() {
        val view = root ?: return
        if (isAnimatingOut) return
        isAnimatingOut = true
        isTransitioning = true
        pendingToggle = false
        handler.removeCallbacks(updater)
        cardRoot?.animate()?.alpha(0f)?.setDuration(180L)?.start()
        view.animate()
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                if (root === view) removeOverlay()
            }
            .start()
    }

    fun release() {
        CapsuleWindowHost.removeListener(hostListener)
        handler.removeCallbacks(keyguardUnlockCheck)
        favoritesJob?.cancel()
        settingsJob?.cancel()
        removeOverlay()
        loadedArtworkUri = null
        loadedArtworkBitmap = null
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

        morphAnimator?.cancel()
        morphProgress = 0f
        isCollapsing = false
        isTransitioning = false
        pendingToggle = false
        removeCardWindow()
        windowParams?.let {
            it.width = pillTouchWidth()
            it.height = pillTouchHeight()
            it.x = pillTouchLeft()
            it.y = pillTouchTop()
            updateWindowLayout(container, it)
        }
        fillCapsuleWindow(container)
        updatePlaybackState()
    }

    private fun toggleExpanded() {
        if (isTransitioning) {
            pendingToggle = !pendingToggle
            return
        }
        if (isExpanded) collapse() else expand()
    }

    private fun requestCollapse() {
        when {
            isCollapsing -> Unit
            isTransitioning -> pendingToggle = true
            isExpanded -> collapse()
        }
    }

    private fun endTransition() {
        isTransitioning = false
        if (pendingToggle) {
            pendingToggle = false
            handler.post { if (root != null) toggleExpanded() }
        }
    }

    /**
     * Drives the hero transition. Each frame only re-draws the shared shape and fades the
     * two contents, so there is no layout or window work while it runs.
     */
    private fun animateMorph(target: Float, durationMs: Long, onEnd: () -> Unit) {
        morphAnimator?.cancel()
        val distance = abs(target - morphProgress).coerceIn(0f, 1f)
        morphAnimator = ValueAnimator.ofFloat(morphProgress, target).apply {
            duration = (durationMs * distance).toLong().coerceAtLeast(1L)
            interpolator = heroInterpolator
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                applyMorphFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    /** Capsule icons fade out as the shape starts growing; card content fades in as it lands. */
    private fun applyMorphFrame() {
        val progress = morphProgress
        morphView?.refresh()
        collapsedPill?.alpha = 1f - (progress / 0.3f).coerceIn(0f, 1f)
        expandedCard?.let { panel ->
            val contentAlpha = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
            panel.alpha = contentAlpha
            // Content never shows outside the shape while the shape is still growing.
            val shape = morphView?.currentBounds
            val params = panel.layoutParams as? FrameLayout.LayoutParams
            panel.clipBounds = if (progress >= 1f || shape == null || params == null) {
                null
            } else {
                val offsetY = params.topMargin
                Rect(
                    shape.left - params.leftMargin,
                    shape.top - offsetY,
                    shape.right - params.leftMargin,
                    shape.bottom - offsetY
                )
            }
        }
    }

    /** Capsule window content: the shape at capsule size and the pill with its icons. */
    private fun fillCapsuleWindow(container: FrameLayout) {
        clearRenderedViews()
        container.removeAllViews()
        container.setOnTouchListener(null)
        val shape = MorphShapeView(context, { windowParams }, { 0f })
        container.addView(
            shape,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            buildPill(),
            FrameLayout.LayoutParams(pillTouchWidth(), pillTouchHeight()).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        shape.refresh()
    }

    private fun setCapsuleWindowContentVisible(visible: Boolean) {
        val container = root ?: return
        for (index in 0 until container.childCount) {
            container.getChildAt(index).visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun removeCardWindow() {
        val card = cardRoot ?: return
        cardRoot = null
        cardParams = null
        morphView = null
        collapsedPill = null
        card.animate().cancel()
        if (card.isAttachedToWindow) {
            try {
                attachedWindowManager?.removeView(card)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Card window was already removed.", e)
            }
        }
    }

    /**
     * Runs [action] once [view]'s window has drawn and shown a frame, so a handoff between the
     * capsule and card windows never leaves a frame with neither on screen.
     */
    private fun afterWindowShown(view: View, action: () -> Unit) {
        view.post {
            val done = Runnable { handler.postDelayed({ action() }, HANDOFF_MARGIN_MS) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.viewTreeObserver.registerFrameCommitCallback { handler.post(done) }
                view.invalidate()
            } else {
                handler.postDelayed(done, 2 * HANDOFF_MARGIN_MS)
            }
        }
    }

    /** Keeps the card's first row clear of the camera and of a status bar drawn over it. */
    private fun cardTopPadding(cardTopOnScreen: Int): Int {
        var clearLine = cardTopOnScreen + dp(14)
        cameraAnchor?.let { clearLine = max(clearLine, it.bottom + dp(10)) }
        if (CapsuleWindowHost.windowManager == null) {
            clearLine = max(clearLine, statusBarHeight + dp(8))
        }
        return clearLine - cardTopOnScreen
    }

    private fun expand() {
        val container = root ?: return
        val manager = attachedWindowManager ?: return
        val capsuleParams = windowParams ?: return
        if (isExpanded || isCollapsing || isTransitioning || cardRoot != null) return
        isTransitioning = true

        // The card always covers the whole capsule (same top, at least as wide), so the
        // capsule window underneath can never peek out while the shape morphs.
        val nominalWidth = expandedWidth() - dp(24)
        val nominalLeft = (displayWidth() - nominalWidth) / 2
        val capsuleLeft = capsuleVisibleLeft()
        val capsuleRight = capsuleLeft + capsuleVisibleWidth()
        val cardLeftOnScreen = min(nominalLeft, capsuleLeft)
        val cardRightOnScreen = max(nominalLeft + nominalWidth, capsuleRight)
        val cardWidth = cardRightOnScreen - cardLeftOnScreen
        val cardTopOnScreen = capsuleVisibleTop()

        val windowX = max(0, min(cardLeftOnScreen - dp(12), pillTouchLeft()))
        val windowRight = min(
            displayWidth(),
            max(cardRightOnScreen + dp(12), pillTouchLeft() + pillTouchWidth())
        )
        val windowY = capsuleParams.y

        clearRenderedViews()
        val pillClone = buildPill()
        val views = preparedPanel ?: buildExpandedCard()
        preparedPanel = null
        attachExpandedViews(views)
        val panel = views.panel
        panel.setPadding(dp(16), cardTopPadding(cardTopOnScreen), dp(16), dp(12))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cardHeight = panel.measuredHeight
        cardRectOnScreen.set(
            cardLeftOnScreen,
            cardTopOnScreen,
            cardRightOnScreen,
            cardTopOnScreen + cardHeight
        )

        val card = object : FrameLayout(context) {
            override fun shouldDelayChildPressedState(): Boolean = false
        }.apply {
            clipChildren = false
            clipToPadding = false
        }
        val params = WindowManager.LayoutParams(
            windowRight - windowX,
            cardTopOnScreen + cardHeight + dp(16) - windowY,
            capsuleParams.type,
            capsuleParams.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = windowX
            y = windowY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val shape = MorphShapeView(context, { cardParams }, { morphProgress })
        card.addView(
            shape,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        card.addView(
            pillClone,
            FrameLayout.LayoutParams(pillTouchWidth(), pillTouchHeight()).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pillTouchLeft() - windowX
                topMargin = pillTouchTop() - windowY
            }
        )
        card.addView(
            panel,
            FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = cardLeftOnScreen - windowX
                topMargin = cardTopOnScreen - windowY
            }
        )

        cardRoot = card
        cardParams = params
        morphView = shape
        collapsedPill = pillClone
        expandedCard = panel
        isExpanded = true
        morphProgress = 0f
        applyMorphFrame()
        configureOutsideTap(card)
        try {
            manager.addView(card, params)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open the floating card.", e)
            cardRoot = null
            cardParams = null
            morphView = null
            collapsedPill = null
            expandedCard = null
            isExpanded = false
            preparedPanel = activePanel
            activePanel = null
            fillCapsuleWindow(container)
            updatePlaybackState()
            endTransition()
            return
        }
        updatePlaybackState()
        // The card window starts as an exact copy of the capsule; hand over once it's shown.
        afterWindowShown(card) {
            if (cardRoot !== card || !isExpanded) return@afterWindowShown
            setCapsuleWindowContentVisible(false)
            animateMorph(1f, EXPAND_DURATION_MS) {
                if (cardRoot === card && isExpanded) {
                    card.removeView(pillClone)
                    if (collapsedPill === pillClone) collapsedPill = null
                    endTransition()
                }
            }
        }
        handler.removeCallbacks(updater)
        handler.postDelayed(updater, EXPANDED_UPDATE_INTERVAL_MS)
    }

    private fun collapse() {
        val card = cardRoot ?: return
        val params = cardParams ?: return
        if (!isExpanded || isCollapsing || isTransitioning) return
        isTransitioning = true
        isCollapsing = true
        handler.removeCallbacks(updater)
        clearRenderedViews()
        val pillClone = buildPill()
        pillClone.alpha = 0f
        // Above the shape, below the card content, so the card's buttons keep their taps.
        card.addView(
            pillClone,
            1,
            FrameLayout.LayoutParams(pillTouchWidth(), pillTouchHeight()).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pillTouchLeft() - params.x
                topMargin = pillTouchTop() - params.y
            }
        )
        collapsedPill = pillClone
        updatePlaybackState()
        animateMorph(0f, COLLAPSE_DURATION_MS) {
            if (cardRoot === card) finishCollapse(card)
        }
    }

    /**
     * The card window now shows exactly the capsule. The capsule window is rebuilt underneath
     * with the same pixels, and the card window is removed only once that is on screen, so
     * nothing visible ever moves.
     */
    private fun finishCollapse(card: FrameLayout) {
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
        userIsSeeking = false
        activePanel?.panel?.let { panel ->
            card.removeView(panel)
            panel.alpha = 1f
            panel.translationY = 0f
            panel.clipBounds = null
        }
        // The detached card is reused next time instead of being rebuilt.
        preparedPanel = activePanel ?: preparedPanel
        activePanel = null
        fillCapsuleWindow(container)
        updatePlaybackState()
        afterWindowShown(container) {
            if (cardRoot === card) removeCardWindow()
            isCollapsing = false
            updatePlaybackState()
            schedulePanelPrebuild()
            endTransition()
        }
    }

    private fun buildPill(): View {
        // The shared morph shape draws the capsule; the pill only holds its content.
        val pill = CapsulePill(context).apply {
            raiseAboveShape(this)
            foreground = capsuleRipple()
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            isClickable = true
            isFocusable = true
            contentDescription = "Expand or collapse floating music controls"
            setOnClickListener { toggleExpanded() }
        }

        // Positions are relative to the collapsed window (the touch area).
        val layout = capsuleLayout()
        val touchLeft = pillTouchLeft()
        val visibleTop = layout.top - pillTouchTop()
        val visibleHeight = layout.height
        val artSlot = layout.artSlot
        val artSize = (visibleHeight * 0.72f).toInt()
        val waveSlot = layout.waveSlot
        val artLeft = layout.artLeft - touchLeft
        val waveLeft = layout.waveLeft - touchLeft

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circleBackground(0xFF282828.toInt())
            clipToOutline = true
            setImageResource(R.drawable.ic_launcher_foreground)
            isClickable = false
        }
        artworkViews.add(image)
        pill.addView(
            capsuleTarget(image, artSize, artSize, artSlot, visibleHeight),
            FrameLayout.LayoutParams(artSlot, visibleHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = artLeft
                topMargin = visibleTop
            }
        )

        val waveform = AudioWaveformView(context).apply {
            isPlaying = player.isPlaying
        }
        waveformViews.add(waveform)
        pill.addView(
            capsuleTarget(
                waveform,
                (waveSlot * 0.6f).toInt(),
                (visibleHeight * 0.56f).toInt(),
                waveSlot,
                visibleHeight
            ),
            FrameLayout.LayoutParams(waveSlot, visibleHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = waveLeft
                topMargin = visibleTop
            }
        )
        return pill
    }

    /** A circular tap target inside the capsule that expands the card. */
    private fun capsuleTarget(
        content: View,
        contentWidth: Int,
        contentHeight: Int,
        width: Int,
        height: Int
    ) = FrameLayout(context).apply {
        background = capsuleTargetRipple(width, height)
        isClickable = true
        isFocusable = true
        contentDescription = "Expand floating music controls"
        setOnClickListener { toggleExpanded() }
        addView(content, FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER))
    }

    private fun buildExpandedCard(): ExpandedViews {
        val panel = object : LinearLayout(context) {
            // Lets the seek bar and buttons react instantly (no touch-slop dead zone).
            override fun shouldDelayChildPressedState(): Boolean = false
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            raiseAboveShape(this)
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
                skipToNextTrack()
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

    private fun configureOutsideTap(container: FrameLayout) {
        container.setOnTouchListener { _, event ->
            if (!isExpanded) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_OUTSIDE -> {
                    requestCollapse()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val panel = expandedCard
                    val insideCard = panel != null &&
                        event.x >= panel.left && event.x <= panel.right &&
                        event.y >= panel.top && event.y <= panel.bottom
                    if (!insideCard) requestCollapse()
                    true
                }
                else -> true
            }
        }
    }

    private fun updateWindowLayout(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        if (!view.isAttachedToWindow) return
        try {
            attachedWindowManager?.updateViewLayout(view, params)
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission was revoked while updating the player.", e)
            overlayAllowed = false
            removeOverlay()
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
        val displayHeight = displayBounds.height()
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

    private fun queryStatusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            if (insets.top > 0) return insets.top
        }
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun queryDisplayBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(windowManager.currentWindowMetrics.bounds)
        } else {
            val metrics = context.resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

    private fun findDisplayCutout(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets.displayCutout
        } else {
            root?.rootWindowInsets?.displayCutout
        }
        val box = cutout?.boundingRects
            ?.filter { it.width() > 0 && it.height() > 0 && it.top < dp(140) }
            ?.maxByOrNull { it.width().toLong() * it.height() }
            ?: return null
        // Bounding boxes can be padded well past the lens; the outline is the real hole.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val outline = cutout.cutoutPath
            if (outline != null) {
                val hole = Region().apply { setPath(outline, Region(box)) }.bounds
                if (!hole.isEmpty) return hole
            }
        }
        return Rect(box)
    }

    private fun displayWidth(): Int = displayBounds.width()

    /** Visible capsule bounds and item positions, all in screen coordinates. */
    private class CapsuleLayout(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val artLeft: Int,
        val waveLeft: Int,
        val artSlot: Int,
        val waveSlot: Int
    )

    /**
     * Sized to its content: artwork, camera and level bars. A centred camera sits in the
     * middle like an island; a corner camera sits at the capsule's end, with both items
     * beside it, so no width is wasted.
     */
    private fun capsuleLayout(): CapsuleLayout {
        val screenWidth = displayWidth()
        val edge = dp(8)
        val gap = dp(CAPSULE_CAMERA_GAP_DP)
        val camera = cameraAnchor
        val scale = capsuleSettings.size.scale
        fun sized(valueDp: Int) = (valueDp * scale * context.resources.displayMetrics.density).toInt()
        val cameraHeight = camera?.height() ?: 0
        // Each size adds its own black rim around the camera, so sizes differ even when
        // the camera, not the minimum, decides the height.
        val (rimDp, minHeightDp) = when (capsuleSettings.size) {
            CapsuleSize.SMALL -> 2 to 28
            CapsuleSize.MEDIUM -> 4 to 34
            CapsuleSize.LARGE -> 8 to 42
        }
        val height = max(dp(minHeightDp), cameraHeight + dp(rimDp) * 2)
        val artSlot = height
        val waveSlot = sized(CAPSULE_WAVE_SLOT_DP)
        val top = camera?.let { (it.centerY() - height / 2).coerceAtLeast(dp(2)) }
            ?: (statusBarHeight + dp(6))

        fun clampLeft(left: Int, width: Int) =
            left.coerceIn(edge, max(edge, screenWidth - edge - width))

        if (camera == null) {
            val width = artSlot + sized(CAPSULE_NO_CAMERA_GAP_DP) + waveSlot
            val left = clampLeft(screenWidth / 2 - width / 2, width)
            return CapsuleLayout(
                left, top, width, height, left, left + width - waveSlot, artSlot, waveSlot
            )
        }

        // Rim beside a corner camera matches the rim above and below it.
        val sideRim = ((height - camera.height()) / 2).coerceAtLeast(dp(4))
        val wing = max(artSlot, waveSlot)
        val centredWidth = camera.width() + gap * 2 + wing * 2
        val centredLeft = camera.centerX() - centredWidth / 2
        val besideWidth = sideRim + camera.width() + gap + artSlot + waveSlot

        return when {
            centredLeft >= edge && centredLeft + centredWidth <= screenWidth - edge -> {
                val left = centredLeft
                CapsuleLayout(
                    left, top, centredWidth, height,
                    artLeft = left + (wing - artSlot) / 2,
                    waveLeft = left + centredWidth - wing + (wing - waveSlot) / 2,
                    artSlot = artSlot,
                    waveSlot = waveSlot
                )
            }
            camera.centerX() < screenWidth / 2 -> {
                val left = clampLeft(camera.left - sideRim, besideWidth)
                CapsuleLayout(
                    left, top, besideWidth, height,
                    artLeft = camera.right + gap,
                    waveLeft = camera.right + gap + artSlot,
                    artSlot = artSlot,
                    waveSlot = waveSlot
                )
            }
            else -> {
                val right = min(screenWidth - edge, camera.right + sideRim)
                val left = clampLeft(right - besideWidth, besideWidth)
                CapsuleLayout(
                    left, top, besideWidth, height,
                    artLeft = left,
                    waveLeft = left + artSlot,
                    artSlot = artSlot,
                    waveSlot = waveSlot
                )
            }
        }
    }

    private fun capsuleVisibleHeight(): Int = capsuleLayout().height

    private fun capsuleVisibleWidth(): Int = capsuleLayout().width

    private fun capsuleVisibleLeft(): Int = capsuleLayout().left

    private fun capsuleVisibleTop(): Int = capsuleLayout().top

    private fun pillTouchLeft(): Int = max(0, capsuleVisibleLeft() - dp(TOUCH_PAD_X_DP))

    private fun pillTouchTop(): Int = max(0, capsuleVisibleTop() - dp(TOUCH_PAD_Y_DP))

    private fun pillTouchWidth(): Int =
        min(
            displayWidth() - pillTouchLeft(),
            capsuleVisibleLeft() + capsuleVisibleWidth() + dp(TOUCH_PAD_X_DP) - pillTouchLeft()
        )

    private fun pillTouchHeight(): Int =
        capsuleVisibleTop() + capsuleVisibleHeight() + dp(TOUCH_PAD_Y_DP) - pillTouchTop()

    /** Circular ripple sized to the visible capsule so it never spills outside it. */
    private fun capsuleTargetRipple(targetWidth: Int, targetHeight: Int): Drawable {
        val diameter = (targetHeight - dp(4)).coerceAtLeast(dp(12))
        val insetX = ((targetWidth - diameter) / 2).coerceAtLeast(0)
        val insetY = ((targetHeight - diameter) / 2).coerceAtLeast(0)
        return InsetDrawable(
            rippleOnly(circleBackground(0xFFFFFFFF.toInt()), 0x45FFFFFF),
            insetX,
            insetY,
            insetX,
            insetY
        )
    }

    private fun expandedWidth(): Int = displayWidth() - dp(24)


    private fun openMusicPlayer() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        }
        context.startActivity(intent)
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

    private fun hasPreviousTrack(): Boolean {
        val previousIndex = player.previousMediaItemIndex
        return player.mediaItemCount > 0 &&
            previousIndex >= 0 && previousIndex != player.currentMediaItemIndex
    }

    /** Returns true when playback moved to another track (not just back to the start). */
    private fun skipToPreviousTrack(): Boolean {
        if (player.mediaItemCount == 0) return false
        return if (hasPreviousTrack()) {
            player.seekToDefaultPosition(player.previousMediaItemIndex)
            true
        } else {
            player.seekToDefaultPosition()
            false
        }
    }

    /** Returns true when there was a next track to move to. */
    private fun skipToNextTrack(): Boolean {
        if (!player.hasNextMediaItem()) return false
        player.seekToNextMediaItem()
        return true
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
        private val trianglePath = android.graphics.Path()

        var isPlaying: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
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
            trianglePath.rewind()
            trianglePath.moveTo(tipX, tipY)
            trianglePath.lineTo(baseTopX, baseTopY)
            trianglePath.lineTo(baseBottomX, baseBottomY)
            trianglePath.close()
            canvas.drawPath(trianglePath, iconPaint)
        }
    }

    private inner class CapsulePill(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var swiping = false
        private val swipeThreshold = max(touchSlop * 2, dp(30))

        // Show pressed state / ripple instantly instead of after the tap timeout.
        override fun shouldDelayChildPressedState(): Boolean = false

        private fun isHorizontalSwipe(event: MotionEvent): Boolean {
            if (!capsuleSettings.swipeGestures) return false
            val dx = event.x - downX
            val dy = event.y - downY
            return abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.35f
        }

        // Fire as soon as the swipe is recognised instead of waiting for release.
        private fun commitSwipe(event: MotionEvent) {
            val toNext = event.x < downX
            val canSkip = if (toNext) player.hasNextMediaItem() else hasPreviousTrack()
            if (!canSkip) {
                // Nothing to skip to: a lighter tick and no slide, so the slide always means
                // the track changed. Previous still restarts the current track.
                if (!toNext) skipToPreviousTrack()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return
            }
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            )
            playSkipSlide(toNext) {
                if (toNext) skipToNextTrack() else skipToPreviousTrack()
            }
        }

        private var skipAnimator: ValueAnimator? = null
        private var pendingSkip: (() -> Unit)? = null

        /**
         * Slides the artwork and bars out in the swipe direction, changes track while they
         * are hidden, then slides them in from the other side. Changing track blocks the main
         * thread briefly, so it happens in the hidden gap and never stalls a visible frame.
         * Only the content moves; the capsule shape stays put.
         */
        private fun playSkipSlide(toNext: Boolean, skip: () -> Unit) {
            runPendingSkip()
            skipAnimator?.cancel()
            val distance = dp(SKIP_SLIDE_DP).toFloat() * if (toNext) -1f else 1f
            pendingSkip = skip
            skipAnimator = slideAnimator(SKIP_SLIDE_OUT_MS) { fraction ->
                val eased = skipOutInterpolator.getInterpolation(fraction)
                setContent(distance * eased, 1f - eased)
            }.apply {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        runPendingSkip()
                        if (cancelled) {
                            setContent(0f, 1f)
                            return
                        }
                        // Start sliding in only after the track change has finished.
                        post { slideIn(distance) }
                    }
                })
                start()
            }
        }

        private fun slideIn(distance: Float) {
            skipAnimator = slideAnimator(SKIP_SLIDE_IN_MS) { fraction ->
                val eased = heroInterpolator.getInterpolation(fraction)
                setContent(-distance * (1f - eased), eased)
            }.apply {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = setContent(0f, 1f)
                    override fun onAnimationCancel(animation: Animator) = setContent(0f, 1f)
                })
                start()
            }
        }

        private fun slideAnimator(durationMs: Long, onFrame: (Float) -> Unit) =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = null
                addUpdateListener { onFrame(it.animatedFraction) }
            }

        private fun runPendingSkip() {
            val skip = pendingSkip ?: return
            pendingSkip = null
            skip()
        }

        private fun setContent(offset: Float, opacity: Float) {
            for (index in 0 until childCount) {
                getChildAt(index).apply {
                    translationX = offset
                    alpha = opacity
                }
            }
        }

        override fun onDetachedFromWindow() {
            // A swipe that was already accepted still changes track.
            skipAnimator?.cancel()
            runPendingSkip()
            super.onDetachedFromWindow()
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
        setOnClickListener {
            action()
        }
    }

    private fun setTextIfChanged(view: TextView?, text: String) {
        if (view != null && view.text.toString() != text) view.text = text
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = capsuleVisibleHeight() / 2f
        setColor(0xFF000000.toInt())
    }

    /** Draws above the morph shape without casting a second shadow. */
    private fun raiseAboveShape(view: View) {
        view.outlineProvider = null
        view.elevation = dp(CONTENT_ELEVATION_DP).toFloat()
    }

    /** Pressed ripple shaped like the visible capsule, inside the larger touch area. */
    private fun capsuleRipple(): Drawable {
        val left = capsuleVisibleLeft() - pillTouchLeft()
        val top = capsuleVisibleTop() - pillTouchTop()
        val right = pillTouchWidth() - left - capsuleVisibleWidth()
        val bottom = pillTouchHeight() - top - capsuleVisibleHeight()
        return InsetDrawable(
            rippleOnly(pillBackground(), 0x35FFFFFF),
            left,
            top,
            max(0, right),
            max(0, bottom)
        )
    }

    /**
     * The one surface shared by capsule and card. Its bounds, corner radius and colour follow
     * [morphProgress]; the outline follows too, so the shadow morphs with it.
     */
    private inner class MorphShapeView(
        context: Context,
        private val windowParamsOf: () -> WindowManager.LayoutParams?,
        private val progressOf: () -> Float
    ) : View(context) {
        private val capsuleShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFF000000.toInt())
        }
        private val cardShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(0xFF263433.toInt(), 0xFF12171B.toInt())
            setStroke(dp(1), 0x664A8C80)
        }
        private val bounds = Rect()
        private var radius = 0f

        /** Current shape bounds in window coordinates. */
        val currentBounds: Rect get() = bounds

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(bounds, radius)
                }
            }
        }

        fun refresh() {
            val progress = progressOf()
            // No shadow at capsule size, so the card window's copy and the capsule window
            // are pixel-identical at the handoff; the shadow grows in with the card.
            elevation = dp(SHAPE_ELEVATION_DP) * (progress / 0.2f).coerceIn(0f, 1f)
            val params = windowParamsOf()
            val originX = params?.x ?: 0
            val originY = params?.y ?: 0
            val left = capsuleVisibleLeft() - originX
            val top = capsuleVisibleTop() - originY
            val right = left + capsuleVisibleWidth()
            val bottom = top + capsuleVisibleHeight()
            if (progress <= 0f || cardRectOnScreen.isEmpty) {
                bounds.set(left, top, right, bottom)
            } else {
                fun lerp(from: Int, to: Int) = (from + (to - from) * progress).toInt()
                bounds.set(
                    lerp(left, cardRectOnScreen.left - originX),
                    lerp(top, cardRectOnScreen.top - originY),
                    lerp(right, cardRectOnScreen.right - originX),
                    lerp(bottom, cardRectOnScreen.bottom - originY)
                )
            }
            val capsuleRadius = capsuleVisibleHeight() / 2f
            radius = capsuleRadius + (dp(CARD_CORNER_RADIUS_DP) - capsuleRadius) * progress
            invalidate()
            invalidateOutline()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            refresh()
        }

        override fun onDraw(canvas: Canvas) {
            // Black capsule turns into the card colour over the first part of the growth.
            val cardAlpha = (progressOf() / 0.6f).coerceIn(0f, 1f)
            if (cardAlpha < 1f) {
                capsuleShape.bounds = bounds
                capsuleShape.cornerRadius = radius
                capsuleShape.draw(canvas)
            }
            if (cardAlpha > 0f) {
                cardShape.bounds = bounds
                cardShape.cornerRadius = radius
                cardShape.alpha = (cardAlpha * 255).toInt()
                cardShape.draw(canvas)
            }
        }
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
        artworkViews.clear()
        waveformViews.clear()
    }

    /**
     * Still "now playing" bars: tall and uneven while playing, short and even when paused.
     * It only redraws when the playing state changes, so nothing moves on its own.
     */
    private class AudioWaveformView(context: Context) : View(context) {
        private companion object {
            val PLAYING_HEIGHTS = floatArrayOf(0.5f, 0.95f, 0.65f, 0.85f)
            val PAUSED_HEIGHTS = floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f)
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF75D9C6.toInt()
            style = Paint.Style.FILL
        }
        private val minBarHeight = 3 * context.resources.displayMetrics.density

        var isPlaying: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val heights = if (isPlaying) PLAYING_HEIGHTS else PAUSED_HEIGHTS
            val barWidth = width / (heights.size * 2f)
            val centerY = height / 2f
            heights.forEachIndexed { index, fraction ->
                val barHeight = (height * fraction).coerceAtLeast(minBarHeight)
                val left = index * barWidth * 2f
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
        }
    }
}
