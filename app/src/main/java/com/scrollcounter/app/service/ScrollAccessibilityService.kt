package com.scrollcounter.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scrollcounter.app.ScrollCounterApp
import com.scrollcounter.app.data.LimitMode
import com.scrollcounter.app.detector.AppDetector
import com.scrollcounter.app.detector.TargetPlatform
import com.scrollcounter.app.overlay.OverlayManager

class ScrollAccessibilityService : AccessibilityService() {

    private lateinit var appDetector: AppDetector
    private lateinit var overlayManager: OverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentActivePlatform: TargetPlatform = TargetPlatform.NONE
    private var isTimerRunning = false
    private var isOverlayDismissedByUser = false

    // Debounced validation candidate runnables
    private var pendingValidationRunnable: Runnable? = null
    private var pendingRetryRunnable: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (currentActivePlatform != TargetPlatform.NONE) {
                    onLeaveTargetPlatform()
                }
            }
        }
    }

    private val secondTickerRunnable = object : Runnable {
        override fun run() {
            if (currentActivePlatform != TargetPlatform.NONE) {
                val prefs = ScrollCounterApp.instance.preferencesManager
                val settings = prefs.settings.value

                when (currentActivePlatform) {
                    TargetPlatform.INSTAGRAM_REELS -> {
                        val totalSecs = prefs.addInstagramTime(1)
                        if (settings.enforceLimits && settings.limitMode == LimitMode.TIME_MINUTES) {
                            if (totalSecs / 60 >= settings.instagramTimeLimitMinutes) {
                                enforceLimit(
                                    TargetPlatform.INSTAGRAM_REELS,
                                    "${totalSecs / 60}m / ${settings.instagramTimeLimitMinutes}m",
                                    settings.closeAppOnLimit,
                                    settings.showBlockingOverlay
                                )
                            }
                        }
                        updateOverlayUI()
                    }
                    TargetPlatform.YOUTUBE_SHORTS -> {
                        val totalSecs = prefs.addYouTubeTime(1)
                        if (settings.enforceLimits && settings.limitMode == LimitMode.TIME_MINUTES) {
                            if (totalSecs / 60 >= settings.youtubeTimeLimitMinutes) {
                                enforceLimit(
                                    TargetPlatform.YOUTUBE_SHORTS,
                                    "${totalSecs / 60}m / ${settings.youtubeTimeLimitMinutes}m",
                                    settings.closeAppOnLimit,
                                    settings.showBlockingOverlay
                                )
                            }
                        }
                        updateOverlayUI()
                    }
                    TargetPlatform.NONE -> {}
                }
                mainHandler.postDelayed(this, 1000L)
            } else {
                isTimerRunning = false
            }
        }
    }

    private fun startTimerIfNeeded() {
        if (!isTimerRunning && currentActivePlatform != TargetPlatform.NONE) {
            isTimerRunning = true
            mainHandler.postDelayed(secondTickerRunnable, 1000L)
        }
    }

    private fun stopTimer() {
        isTimerRunning = false
        mainHandler.removeCallbacks(secondTickerRunnable)
    }

    companion object {
        const val TAG = "ScrollCounter"
        const val DEBOUNCE_SETTLE_DELAY_MS = 300L
        const val METADATA_RETRY_DELAY_MS = 200L

        var instance: ScrollAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appDetector = AppDetector()
        overlayManager = OverlayManager(this)
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenReceiver", e)
        }
        Log.i(TAG, "ScrollAccessibilityService created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString().orEmpty()

        // 1. Ignore our own overlay, input methods, and internal system framework
        if (packageName == "com.scrollcounter.app" ||
            packageName == "android" ||
            packageName.contains("inputmethod")
        ) {
            return
        }

        // 2. System UI (status bar, notifications, lockscreen, app switcher)
        if (packageName == "com.android.systemui") {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (currentActivePlatform != TargetPlatform.NONE) {
                    onLeaveTargetPlatform()
                }
            }
            return
        }

        // 3. User switched to another app or Launcher
        if (packageName != AppDetector.PACKAGE_INSTAGRAM && packageName != AppDetector.PACKAGE_YOUTUBE) {
            if (currentActivePlatform != TargetPlatform.NONE) {
                onLeaveTargetPlatform()
            }
            return
        }

        // 4. If the user is actively dragging the overlay bar, prioritize UI thread
        if (overlayManager.isDragging) {
            return
        }

        // 5. CRITICAL: Passively ignore TYPE_WINDOW_CONTENT_CHANGED!
        // Video playback progress bars, audio animations, and comment counts
        // fire constantly. We perform ZERO tree scans while watching a video!
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // 6. Handle Window State Changes (Entering/leaving Reels or switching tabs)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            handleWindowStateChanged(packageName, event)
            return
        }

        // 7. Handle Physical Scroll Events (Candidate scroll trigger)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleViewScrolled(packageName, event)
            return
        }
    }

    private fun handleWindowStateChanged(packageName: String, event: AccessibilityEvent) {
        val rootNode = resolveRootNode(event) ?: return
        val detected = appDetector.detectPlatform(packageName, rootNode)

        val prefs = ScrollCounterApp.instance.preferencesManager
        val settings = prefs.settings.value

        when (detected) {
            TargetPlatform.INSTAGRAM_REELS -> {
                if (!settings.instagramEnabled) {
                    if (currentActivePlatform != TargetPlatform.NONE) onLeaveTargetPlatform()
                    return
                }
                val isFirstEntry = currentActivePlatform != TargetPlatform.INSTAGRAM_REELS
                currentActivePlatform = TargetPlatform.INSTAGRAM_REELS
                startTimerIfNeeded()
                if (isFirstEntry) {
                    updateOverlayUI()
                    // Establish baseline on the initial reel so opening the screen does not count as a scroll
                    appDetector.establishBaseline(TargetPlatform.INSTAGRAM_REELS, rootNode)
                }
            }

            TargetPlatform.YOUTUBE_SHORTS -> {
                if (!settings.youtubeEnabled) {
                    if (currentActivePlatform != TargetPlatform.NONE) onLeaveTargetPlatform()
                    return
                }
                val isFirstEntry = currentActivePlatform != TargetPlatform.YOUTUBE_SHORTS
                currentActivePlatform = TargetPlatform.YOUTUBE_SHORTS
                startTimerIfNeeded()
                if (isFirstEntry) {
                    updateOverlayUI()
                    // Establish baseline on the initial short
                    appDetector.establishBaseline(TargetPlatform.YOUTUBE_SHORTS, rootNode)
                }
            }

            TargetPlatform.NONE -> {
                if (currentActivePlatform != TargetPlatform.NONE) {
                    onLeaveTargetPlatform()
                }
            }
        }
    }

    private fun handleViewScrolled(packageName: String, event: AccessibilityEvent) {
        // If not currently in a target platform, check if this scroll brought user into Reels/Shorts
        if (currentActivePlatform == TargetPlatform.NONE) {
            val rootNode = resolveRootNode(event) ?: return
            val detected = appDetector.detectPlatform(packageName, rootNode)
            if (detected == TargetPlatform.NONE) return
            handleWindowStateChanged(packageName, event)
        }

        // At most ONE pending validation scan per settled transition window:
        // Cancel any pending validation from an unsettled rapid multi-swipe
        cancelPendingValidation()

        val validationRunnable = Runnable {
            performValidationScan(isRetry = false)
        }
        pendingValidationRunnable = validationRunnable
        mainHandler.postDelayed(validationRunnable, DEBOUNCE_SETTLE_DELAY_MS)
    }

    private fun performValidationScan(isRetry: Boolean) {
        if (currentActivePlatform == TargetPlatform.NONE) return

        val rootNode = resolveRootNode() ?: return
        val result = appDetector.validateTransition(currentActivePlatform, rootNode, isRetry)

        if (!result.isStillInTarget) {
            onLeaveTargetPlatform()
            return
        }

        if (result.shouldRetry && !isRetry) {
            // Identity metadata hasn't loaded into the accessibility tree yet.
            // Schedule a single fast retry scan in 200ms instead of creating a phantom count!
            cancelPendingValidation()
            val retryRunnable = Runnable {
                performValidationScan(isRetry = true)
            }
            pendingRetryRunnable = retryRunnable
            mainHandler.postDelayed(retryRunnable, METADATA_RETRY_DELAY_MS)
            return
        }

        if (result.isNewContentScrolled) {
            val prefs = ScrollCounterApp.instance.preferencesManager
            val settings = prefs.settings.value

            when (currentActivePlatform) {
                TargetPlatform.INSTAGRAM_REELS -> {
                    val count = prefs.incrementInstagramCount()
                    updateOverlayUI()
                    if (settings.enforceLimits && settings.limitMode == LimitMode.SCROLLS) {
                        if (count >= settings.instagramScrollLimit) {
                            enforceLimit(
                                TargetPlatform.INSTAGRAM_REELS,
                                "$count / ${settings.instagramScrollLimit} reels",
                                settings.closeAppOnLimit,
                                settings.showBlockingOverlay
                            )
                        }
                    }
                }

                TargetPlatform.YOUTUBE_SHORTS -> {
                    val count = prefs.incrementYouTubeCount()
                    updateOverlayUI()
                    if (settings.enforceLimits && settings.limitMode == LimitMode.SCROLLS) {
                        if (count >= settings.youtubeScrollLimit) {
                            enforceLimit(
                                TargetPlatform.YOUTUBE_SHORTS,
                                "$count / ${settings.youtubeScrollLimit} shorts",
                                settings.closeAppOnLimit,
                                settings.showBlockingOverlay
                            )
                        }
                    }
                }

                TargetPlatform.NONE -> {}
            }
        }
    }

    private fun cancelPendingValidation() {
        pendingValidationRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingValidationRunnable = null
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun onLeaveTargetPlatform() {
        currentActivePlatform = TargetPlatform.NONE
        isOverlayDismissedByUser = false
        cancelPendingValidation()
        appDetector.resetState()
        stopTimer()
        mainHandler.post { overlayManager.hideCounter() }
    }

    private fun resolveRootNode(event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow ?: run {
                var eventRoot: AccessibilityNodeInfo? = event?.source
                while (eventRoot?.parent != null) {
                    eventRoot = eventRoot.parent
                }
                eventRoot
            }
        } catch (_: Exception) {
            try {
                rootInActiveWindow ?: event?.source
            } catch (_: Exception) {
                event?.source
            }
        }
    }

    private fun updateOverlayUI() {
        if (isOverlayDismissedByUser) return
        val settings = ScrollCounterApp.instance.preferencesManager.settings.value
        mainHandler.post {
            if (isOverlayDismissedByUser) return@post
            when (currentActivePlatform) {
                TargetPlatform.INSTAGRAM_REELS -> {
                    overlayManager.showCounter(
                        platform = TargetPlatform.INSTAGRAM_REELS,
                        count = settings.instagramTodayCount,
                        scrollLimit = settings.instagramScrollLimit,
                        activeSeconds = settings.instagramSecondsToday,
                        timeLimitMinutes = settings.instagramTimeLimitMinutes,
                        enforceLimits = settings.enforceLimits,
                        limitMode = settings.limitMode,
                        onCloseAppClicked = {
                            Log.i(TAG, "User clicked floating tag close -> dismissing counter tag (Instagram remains open)")
                            isOverlayDismissedByUser = true
                            overlayManager.hideCounter()
                        }
                    )
                }
                TargetPlatform.YOUTUBE_SHORTS -> {
                    overlayManager.showCounter(
                        platform = TargetPlatform.YOUTUBE_SHORTS,
                        count = settings.youtubeTodayCount,
                        scrollLimit = settings.youtubeScrollLimit,
                        activeSeconds = settings.youtubeSecondsToday,
                        timeLimitMinutes = settings.youtubeTimeLimitMinutes,
                        enforceLimits = settings.enforceLimits,
                        limitMode = settings.limitMode,
                        onCloseAppClicked = {
                            Log.i(TAG, "User clicked floating tag close -> dismissing counter tag (YouTube remains open)")
                            isOverlayDismissedByUser = true
                            overlayManager.hideCounter()
                        }
                    )
                }
                TargetPlatform.NONE -> {}
            }
        }
    }

    private var lastLimitEnforcedTime = 0L

    private fun enforceLimit(
        platform: TargetPlatform,
        infoText: String,
        closeApp: Boolean,
        showBlocker: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (now - lastLimitEnforcedTime < 3000L) return
        lastLimitEnforcedTime = now

        if (showBlocker) {
            overlayManager.showLimitReachedOverlay(platform, infoText) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        if (closeApp) {
            mainHandler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 600L)
        }
    }

    override fun onInterrupt() {
        cancelPendingValidation()
        stopTimer()
        mainHandler.post { overlayManager.cleanup() }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        cancelPendingValidation()
        stopTimer()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        mainHandler.post { overlayManager.cleanup() }
    }
}
