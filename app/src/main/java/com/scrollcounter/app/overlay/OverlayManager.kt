package com.scrollcounter.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.scrollcounter.app.data.LimitMode
import com.scrollcounter.app.detector.TargetPlatform
import kotlin.math.hypot

class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("scroll_counter_preferences", Context.MODE_PRIVATE)

    private var counterView: View? = null
    private var counterParams: WindowManager.LayoutParams? = null
    private var tvCountText: TextView? = null
    private var tvPlatformBadge: TextView? = null
    private var pillContainer: LinearLayout? = null
    private var btnCloseIcon: TextView? = null

    private var blockerView: View? = null
    private var currentCloseAction: (() -> Unit)? = null

    private val autoHideCloseRunnable = Runnable {
        btnCloseIcon?.visibility = View.GONE
    }

    // Touch dragging & persistence state
    private var savedX = prefs.getInt("overlay_saved_x", dpToPx(20))
    private var savedY = prefs.getInt("overlay_saved_y", dpToPx(120))
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

    var isDragging: Boolean = false
        private set

    private var isFrameScheduled = false
    private var pendingTargetX = 0
    private var pendingTargetY = 0

    private val frameCallback = Choreographer.FrameCallback {
        isFrameScheduled = false
        val view = counterView ?: return@FrameCallback
        val params = counterParams ?: return@FrameCallback
        if (params.x != pendingTargetX || params.y != pendingTargetY) {
            params.x = pendingTargetX
            params.y = pendingTargetY
            savedX = pendingTargetX
            savedY = pendingTargetY
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private var lastPillColorState = -1
    private var lastPlatform: TargetPlatform? = null

    companion object {
        const val TAG = "ScrollCounter"
    }

    fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showCounter(
        platform: TargetPlatform,
        count: Int,
        scrollLimit: Int,
        activeSeconds: Int,
        timeLimitMinutes: Int,
        enforceLimits: Boolean,
        limitMode: LimitMode,
        onCloseAppClicked: () -> Unit
    ) {
        if (!canDrawOverlays()) return
        currentCloseAction = onCloseAppClicked

        val platformName = when (platform) {
            TargetPlatform.INSTAGRAM_REELS -> "Reels"
            TargetPlatform.YOUTUBE_SHORTS -> "Shorts"
            TargetPlatform.NONE -> return
        }

        val minutes = activeSeconds / 60
        val timeDisplay = if (minutes > 0) "${minutes}m" else "${activeSeconds}s"

        val labelText: String = if (!enforceLimits) {
            "$count • $timeDisplay"
        } else {
            when (limitMode) {
                LimitMode.SCROLLS -> "$count / $scrollLimit • $timeDisplay"
                LimitMode.TIME_MINUTES -> "${minutes}m / ${timeLimitMinutes}m • $count"
            }
        }

        val ratio: Float = if (enforceLimits) {
            when (limitMode) {
                LimitMode.SCROLLS -> if (scrollLimit > 0) count.toFloat() / scrollLimit else 0f
                LimitMode.TIME_MINUTES -> if (timeLimitMinutes > 0) (activeSeconds / 60f) / timeLimitMinutes else 0f
            }
        } else {
            0f
        }

        val colorState = when {
            ratio >= 1.0f -> 2 // Red
            ratio >= 0.8f -> 1 // Amber
            else -> 0 // Pitch-dark frosted black
        }

        if (counterView == null) {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            counterParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }

            val rootFrameLayout = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }

            val linearLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
                gravity = Gravity.CENTER_VERTICAL
                elevation = dpToPx(4).toFloat()
                background = createPillBackground(colorState)
            }
            pillContainer = linearLayout
            lastPillColorState = colorState

            // Platform badge with respective branding
            val badge = TextView(context).apply {
                text = platformName
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                val bg = GradientDrawable().apply {
                    cornerRadius = dpToPx(6).toFloat()
                    if (platform == TargetPlatform.INSTAGRAM_REELS) {
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(
                            Color.parseColor("#833AB4"),
                            Color.parseColor("#FD1D1D"),
                            Color.parseColor("#FCB045")
                        )
                    } else {
                        setColor(Color.parseColor("#FF0000"))
                    }
                }
                background = bg
            }
            tvPlatformBadge = badge
            lastPlatform = platform

            // Live count & time text
            val countView = TextView(context).apply {
                text = labelText
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dpToPx(6), 0, dpToPx(6), 0)
            }
            tvCountText = countView

            linearLayout.addView(badge)
            linearLayout.addView(countView)

            val pillParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(4), dpToPx(4), 0)
            }
            rootFrameLayout.addView(linearLayout, pillParams)

            // Small on top-right close icon: HIDDEN by default until pill is tapped
            val btnClose = TextView(context).apply {
                text = "✕"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                visibility = View.GONE
                val size = dpToPx(20)
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444"))
                    setStroke(dpToPx(1f), Color.WHITE)
                }
                elevation = dpToPx(10).toFloat()
                setOnClickListener {
                    Log.i(TAG, "Small top-right close icon clicked -> exiting")
                    hideCounter()
                    currentCloseAction?.invoke()
                }
            }
            btnCloseIcon = btnClose
            rootFrameLayout.addView(btnClose)

            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            // Butter-smooth touch drag and tap-to-show-close handler
            rootFrameLayout.setOnTouchListener { _, motionEvent ->
                val params = counterParams ?: return@setOnTouchListener false

                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = motionEvent.rawX
                        initialTouchY = motionEvent.rawY
                        touchStartTime = System.currentTimeMillis()
                        isDragging = false
                        pendingTargetX = initialX
                        pendingTargetY = initialY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = motionEvent.rawX - initialTouchX
                        val dy = motionEvent.rawY - initialTouchY

                        if (!isDragging && hypot(dx, dy) > touchSlop) {
                            isDragging = true
                        }

                        if (isDragging) {
                            val displayMetrics = context.resources.displayMetrics
                            val viewWidth = rootFrameLayout.width.takeIf { it > 0 } ?: dpToPx(130)
                            val viewHeight = rootFrameLayout.height.takeIf { it > 0 } ?: dpToPx(44)
                            val maxX = (displayMetrics.widthPixels - viewWidth).coerceAtLeast(0)
                            val maxY = (displayMetrics.heightPixels - viewHeight).coerceAtLeast(0)

                            val targetX = (initialX + dx.toInt()).coerceIn(0, maxX)
                            val targetY = (initialY + dy.toInt()).coerceIn(dpToPx(25), maxY)

                            pendingTargetX = targetX
                            pendingTargetY = targetY

                            if (!isFrameScheduled) {
                                isFrameScheduled = true
                                Choreographer.getInstance().postFrameCallback(frameCallback)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchStartTime
                        val distance = hypot(motionEvent.rawX - initialTouchX, motionEvent.rawY - initialTouchY)

                        if (isDragging) {
                            if (isFrameScheduled) {
                                Choreographer.getInstance().removeFrameCallback(frameCallback)
                                isFrameScheduled = false
                            }
                            params.x = pendingTargetX
                            params.y = pendingTargetY
                            savedX = pendingTargetX
                            savedY = pendingTargetY
                            prefs.edit().putInt("overlay_saved_x", savedX).putInt("overlay_saved_y", savedY).apply()
                            try {
                                windowManager.updateViewLayout(rootFrameLayout, params)
                            } catch (_: Exception) {}
                        } else if (distance < touchSlop && duration < 350L) {
                            // Tap to reveal or hide the close 'X' button
                            if (btnClose.visibility == View.VISIBLE) {
                                btnClose.visibility = View.GONE
                                mainHandler.removeCallbacks(autoHideCloseRunnable)
                            } else {
                                btnClose.visibility = View.VISIBLE
                                mainHandler.removeCallbacks(autoHideCloseRunnable)
                                mainHandler.postDelayed(autoHideCloseRunnable, 6000L)
                            }
                        }
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (isDragging && isFrameScheduled) {
                            Choreographer.getInstance().removeFrameCallback(frameCallback)
                            isFrameScheduled = false
                        }
                        isDragging = false
                        true
                    }
                    else -> false
                }
            }

            counterView = rootFrameLayout
            try {
                windowManager.addView(counterView, counterParams)
            } catch (e: Exception) {
                counterView = null
            }
        }

        // Fast in-place text updates
        tvCountText?.text = labelText

        // In-place platform badge update (only if platform changed)
        if (platform != lastPlatform) {
            lastPlatform = platform
            tvPlatformBadge?.text = platformName
            val badgeBg = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                if (platform == TargetPlatform.INSTAGRAM_REELS) {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(
                        Color.parseColor("#833AB4"),
                        Color.parseColor("#FD1D1D"),
                        Color.parseColor("#FCB045")
                    )
                } else {
                    setColor(Color.parseColor("#FF0000"))
                }
            }
            tvPlatformBadge?.background = badgeBg
        }

        // Only re-apply background when the limit state actually shifts (avoid layout thrash while dragging)
        if (colorState != lastPillColorState && !isDragging) {
            lastPillColorState = colorState
            pillContainer?.background = createPillBackground(colorState)
        }
    }

    private fun createPillBackground(colorState: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dpToPx(20).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"))
            when (colorState) {
                2 -> setColor(Color.parseColor("#E6EF4444"))
                1 -> setColor(Color.parseColor("#E6F59E0B"))
                else -> setColor(Color.parseColor("#F209090C")) // Always opaque frosted black (never transparent!)
            }
        }
    }

    fun hideCounter() {
        if (isFrameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            isFrameScheduled = false
        }
        isDragging = false
        lastPillColorState = -1
        lastPlatform = null
        mainHandler.removeCallbacks(autoHideCloseRunnable)
        btnCloseIcon?.visibility = View.GONE
        counterView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            counterView = null
        }
    }

    fun showLimitReachedOverlay(
        platform: TargetPlatform,
        infoText: String,
        onExitClicked: () -> Unit
    ) {
        if (!canDrawOverlays()) return
        if (blockerView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FA000000"))
            setPadding(dpToPx(36), dpToPx(36), dpToPx(36), dpToPx(36))
        }

        val platformName = if (platform == TargetPlatform.INSTAGRAM_REELS) "Instagram Reels" else "YouTube Shorts"

        val tvTitle = TextView(context).apply {
            text = "Daily Limit Reached"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val tvSubtitle = TextView(context).apply {
            text = "You have hit your daily limit for $platformName ($infoText).\nTime to take a mindful pause."
            textSize = 14f
            setTextColor(Color.parseColor("#A1A1AA"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(14), 0, dpToPx(28))
        }

        val btnExit = Button(context).apply {
            text = "Close & Exit"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            val btnBg = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.WHITE)
            }
            background = btnBg
            setPadding(dpToPx(28), dpToPx(12), dpToPx(28), dpToPx(12))
            setOnClickListener {
                hideLimitReachedOverlay()
                onExitClicked()
            }
        }

        root.addView(tvTitle)
        root.addView(tvSubtitle)
        root.addView(btnExit)

        blockerView = root
        try {
            windowManager.addView(blockerView, params)
        } catch (_: Exception) {
            blockerView = null
        }
    }

    fun hideLimitReachedOverlay() {
        blockerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            blockerView = null
        }
    }

    fun cleanup() {
        hideCounter()
        hideLimitReachedOverlay()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
