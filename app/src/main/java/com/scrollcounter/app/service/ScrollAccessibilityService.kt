package com.scrollcounter.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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
        var instance: ScrollAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appDetector = AppDetector()
        overlayManager = OverlayManager(this)
        Log.i(TAG, "ScrollAccessibilityService created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString().orEmpty()

        // 1. Ignore system UI, keyboard, and our own overlay
        if (packageName == "com.android.systemui" ||
            packageName == "com.scrollcounter.app" ||
            packageName == "android" ||
            packageName.contains("inputmethod")
        ) {
            return
        }

        // 2. User switched to another app (Launcher, Browser, WhatsApp, etc.)
        if (packageName != AppDetector.PACKAGE_INSTAGRAM && packageName != AppDetector.PACKAGE_YOUTUBE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (currentActivePlatform != TargetPlatform.NONE) {
                    currentActivePlatform = TargetPlatform.NONE
                    isOverlayDismissedByUser = false
                    stopTimer()
                    mainHandler.post { overlayManager.hideCounter() }
                }
            }
            return
        }

        // 3. User is inside Instagram or YouTube
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }

        val result = appDetector.analyzeEvent(event, rootNode)
        val prefs = ScrollCounterApp.instance.preferencesManager
        val settings = prefs.settings.value

        when (result.platform) {
            TargetPlatform.INSTAGRAM_REELS -> {
                if (!settings.instagramEnabled) {
                    if (currentActivePlatform != TargetPlatform.NONE) {
                        currentActivePlatform = TargetPlatform.NONE
                        isOverlayDismissedByUser = false
                        appDetector.resetState()
                        stopTimer()
                        mainHandler.post { overlayManager.hideCounter() }
                    }
                    return
                }

                val isFirstEntry = currentActivePlatform != TargetPlatform.INSTAGRAM_REELS
                currentActivePlatform = TargetPlatform.INSTAGRAM_REELS
                startTimerIfNeeded()

                if (isFirstEntry) {
                    updateOverlayUI()
                }

                if (result.isNewContentScrolled) {
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
            }

            TargetPlatform.YOUTUBE_SHORTS -> {
                if (!settings.youtubeEnabled) {
                    if (currentActivePlatform != TargetPlatform.NONE) {
                        currentActivePlatform = TargetPlatform.NONE
                        isOverlayDismissedByUser = false
                        appDetector.resetState()
                        stopTimer()
                        mainHandler.post { overlayManager.hideCounter() }
                    }
                    return
                }

                val isFirstEntry = currentActivePlatform != TargetPlatform.YOUTUBE_SHORTS
                currentActivePlatform = TargetPlatform.YOUTUBE_SHORTS
                startTimerIfNeeded()

                if (isFirstEntry) {
                    updateOverlayUI()
                }

                if (result.isNewContentScrolled) {
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
            }

            TargetPlatform.NONE -> {
                // User is in normal Instagram Feed, search, profile, or DMs
                if (currentActivePlatform != TargetPlatform.NONE) {
                    currentActivePlatform = TargetPlatform.NONE
                    isOverlayDismissedByUser = false
                    appDetector.resetState()
                    stopTimer()
                    mainHandler.post { overlayManager.hideCounter() }
                }
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
        stopTimer()
        mainHandler.post { overlayManager.cleanup() }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopTimer()
        mainHandler.post { overlayManager.cleanup() }
    }
}
