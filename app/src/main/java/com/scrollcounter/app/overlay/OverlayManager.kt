package com.scrollcounter.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

    // Touch dragging state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L

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
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(20)
                y = dpToPx(100)
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
            }
            pillContainer = linearLayout

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
                        // Instagram gradient
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(
                            Color.parseColor("#833AB4"),
                            Color.parseColor("#FD1D1D"),
                            Color.parseColor("#FCB045")
                        )
                    } else {
                        // YouTube Red
                        setColor(Color.parseColor("#FF0000"))
                    }
                }
                background = bg
            }
            tvPlatformBadge = badge

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

            // Pill layout params with small offset for top-right close icon
            val pillParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(4), dpToPx(4), 0)
            }
            rootFrameLayout.addView(linearLayout, pillParams)

            // Small on top-right close icon: HIDDEN by default!
            val btnClose = TextView(context).apply {
                text = "✕"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                visibility = View.GONE // Hidden initially until tag is clicked!
                val size = dpToPx(18)
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#EF4444")) // Vibrant red badge
                    setStroke(dpToPx(1f), Color.WHITE)
                }
                elevation = dpToPx(10).toFloat()
                setOnClickListener {
                    Log.i(TAG, "Small top-right close icon clicked -> exiting app")
                    hideCounter()
                    currentCloseAction?.invoke()
                }
            }
            btnCloseIcon = btnClose
            rootFrameLayout.addView(btnClose)

            var touchDownOnClose = false

            // Touch drag or tap-to-show-close behavior
            rootFrameLayout.setOnTouchListener { _, motionEvent ->
                val params = counterParams ?: return@setOnTouchListener false

                if (btnClose.visibility == View.VISIBLE) {
                    val hitRect = Rect()
                    btnClose.getHitRect(hitRect)
                    hitRect.inset(-dpToPx(16), -dpToPx(16)) // Generous 16dp touch target
                    if (motionEvent.action == MotionEvent.ACTION_DOWN && hitRect.contains(motionEvent.x.toInt(), motionEvent.y.toInt())) {
                        touchDownOnClose = true
                        return@setOnTouchListener true
                    }
                    if (touchDownOnClose && motionEvent.action == MotionEvent.ACTION_UP) {
                        touchDownOnClose = false
                        Log.i(TAG, "Close icon tapped -> exiting to home")
                        hideCounter()
                        currentCloseAction?.invoke()
                        return@setOnTouchListener true
                    }
                }

                when (motionEvent.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownOnClose = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = motionEvent.rawX
                        initialTouchY = motionEvent.rawY
                        touchStartTime = System.currentTimeMillis()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val displayMetrics = context.resources.displayMetrics
                        val maxX = displayMetrics.widthPixels - dpToPx(80)
                        val maxY = displayMetrics.heightPixels - dpToPx(60)

                        params.x = (initialX + (motionEvent.rawX - initialTouchX).toInt()).coerceIn(0, maxX)
                        params.y = (initialY + (motionEvent.rawY - initialTouchY).toInt()).coerceIn(dpToPx(30), maxY)

                        try {
                            windowManager.updateViewLayout(rootFrameLayout, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val distance = hypot(motionEvent.rawX - initialTouchX, motionEvent.rawY - initialTouchY)
                        val duration = System.currentTimeMillis() - touchStartTime

                        // Single tap (< 300ms, < 20px moved):
                        // Reveals or hides the small top-right close icon!
                        if (distance < 20f && duration < 300) {
                            if (btnClose.visibility == View.VISIBLE) {
                                btnClose.visibility = View.GONE
                                mainHandler.removeCallbacks(autoHideCloseRunnable)
                            } else {
                                btnClose.visibility = View.VISIBLE
                                mainHandler.removeCallbacks(autoHideCloseRunnable)
                                mainHandler.postDelayed(autoHideCloseRunnable, 8000L) // auto-hide after 8s
                            }
                        }
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

        // Fast in-place updates
        tvPlatformBadge?.text = platformName
        tvCountText?.text = labelText

        // Dynamic badge background styling
        val badgeBg = GradientDrawable().apply {
            cornerRadius = dpToPx(8).toFloat()
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

        val ratio: Float = if (enforceLimits) {
            when (limitMode) {
                LimitMode.SCROLLS -> if (scrollLimit > 0) count.toFloat() / scrollLimit else 0f
                LimitMode.TIME_MINUTES -> if (timeLimitMinutes > 0) (activeSeconds / 60f) / timeLimitMinutes else 0f
            }
        } else {
            0f
        }

        val pillBg = GradientDrawable().apply {
            cornerRadius = dpToPx(20).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"))
            when {
                ratio >= 1.0f -> setColor(Color.parseColor("#E6EF4444")) // Red
                ratio >= 0.8f -> setColor(Color.parseColor("#E6F59E0B")) // Amber
                else -> setColor(Color.parseColor("#E609090C")) // Pitch-dark frosted black
            }
        }
        pillContainer?.background = pillBg
    }

    fun hideCounter() {
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
