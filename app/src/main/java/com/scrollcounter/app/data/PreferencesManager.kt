package com.scrollcounter.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LimitMode {
    SCROLLS,
    TIME_MINUTES
}

data class UserSettings(
    val instagramEnabled: Boolean = true,
    val instagramScrollLimit: Int = 30,
    val instagramTodayCount: Int = 0,
    val instagramTimeLimitMinutes: Int = 30,
    val instagramSecondsToday: Int = 0,

    val youtubeEnabled: Boolean = true,
    val youtubeScrollLimit: Int = 30,
    val youtubeTodayCount: Int = 0,
    val youtubeTimeLimitMinutes: Int = 30,
    val youtubeSecondsToday: Int = 0,

    val enforceLimits: Boolean = false, // false = "Just Show Count (No Limits)", true = enforce
    val limitMode: LimitMode = LimitMode.SCROLLS,
    val closeAppOnLimit: Boolean = true, // Sends GLOBAL_ACTION_HOME
    val showBlockingOverlay: Boolean = true
)

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    init {
        checkDailyReset()
    }

    private fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    @Synchronized
    fun checkDailyReset() {
        val today = getTodayDateString()
        val lastDate = prefs.getString(KEY_LAST_RESET_DATE, "")
        if (lastDate != today) {
            prefs.edit()
                .putString(KEY_LAST_RESET_DATE, today)
                .putInt(KEY_INSTA_COUNT, 0)
                .putInt(KEY_YOUTUBE_COUNT, 0)
                .putInt(KEY_INSTA_SECONDS, 0)
                .putInt(KEY_YOUTUBE_SECONDS, 0)
                .apply()
            _settings.value = loadSettings()
        }
    }

    private fun loadSettings(): UserSettings {
        val modeStr = prefs.getString(KEY_LIMIT_MODE, LimitMode.SCROLLS.name)
        val mode = try {
            LimitMode.valueOf(modeStr ?: LimitMode.SCROLLS.name)
        } catch (_: Exception) {
            LimitMode.SCROLLS
        }

        return UserSettings(
            instagramEnabled = prefs.getBoolean(KEY_INSTA_ENABLED, true),
            instagramScrollLimit = prefs.getInt(KEY_INSTA_SCROLL_LIMIT, 30),
            instagramTodayCount = prefs.getInt(KEY_INSTA_COUNT, 0),
            instagramTimeLimitMinutes = prefs.getInt(KEY_INSTA_TIME_LIMIT, 30),
            instagramSecondsToday = prefs.getInt(KEY_INSTA_SECONDS, 0),

            youtubeEnabled = prefs.getBoolean(KEY_YOUTUBE_ENABLED, true),
            youtubeScrollLimit = prefs.getInt(KEY_YOUTUBE_SCROLL_LIMIT, 30),
            youtubeTodayCount = prefs.getInt(KEY_YOUTUBE_COUNT, 0),
            youtubeTimeLimitMinutes = prefs.getInt(KEY_YOUTUBE_TIME_LIMIT, 30),
            youtubeSecondsToday = prefs.getInt(KEY_YOUTUBE_SECONDS, 0),

            enforceLimits = prefs.getBoolean(KEY_ENFORCE_LIMITS, false),
            limitMode = mode,
            closeAppOnLimit = prefs.getBoolean(KEY_CLOSE_APP_ON_LIMIT, true),
            showBlockingOverlay = prefs.getBoolean(KEY_SHOW_BLOCKING_OVERLAY, true)
        )
    }

    @Synchronized
    fun incrementInstagramCount(): Int {
        checkDailyReset()
        val newCount = _settings.value.instagramTodayCount + 1
        prefs.edit().putInt(KEY_INSTA_COUNT, newCount).apply()
        _settings.value = _settings.value.copy(instagramTodayCount = newCount)
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        return newCount
    }

    @Synchronized
    fun addInstagramTime(seconds: Int): Int {
        checkDailyReset()
        val total = _settings.value.instagramSecondsToday + seconds
        prefs.edit().putInt(KEY_INSTA_SECONDS, total).apply()
        _settings.value = _settings.value.copy(instagramSecondsToday = total)
        if (total % 10 == 0) { // Update widget every 10 seconds to avoid excessive broadcast spam
            com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        }
        return total
    }

    @Synchronized
    fun incrementYouTubeCount(): Int {
        checkDailyReset()
        val newCount = _settings.value.youtubeTodayCount + 1
        prefs.edit().putInt(KEY_YOUTUBE_COUNT, newCount).apply()
        _settings.value = _settings.value.copy(youtubeTodayCount = newCount)
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        return newCount
    }

    @Synchronized
    fun addYouTubeTime(seconds: Int): Int {
        checkDailyReset()
        val total = _settings.value.youtubeSecondsToday + seconds
        prefs.edit().putInt(KEY_YOUTUBE_SECONDS, total).apply()
        _settings.value = _settings.value.copy(youtubeSecondsToday = total)
        if (total % 10 == 0) {
            com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        }
        return total
    }

    fun updateInstagramEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INSTA_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(instagramEnabled = enabled)
    }

    fun updateInstagramScrollLimit(limit: Int) {
        prefs.edit().putInt(KEY_INSTA_SCROLL_LIMIT, limit).apply()
        _settings.value = _settings.value.copy(instagramScrollLimit = limit)
    }

    fun updateInstagramTimeLimit(minutes: Int) {
        prefs.edit().putInt(KEY_INSTA_TIME_LIMIT, minutes).apply()
        _settings.value = _settings.value.copy(instagramTimeLimitMinutes = minutes)
    }

    fun updateYouTubeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_YOUTUBE_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(youtubeEnabled = enabled)
    }

    fun updateYouTubeScrollLimit(limit: Int) {
        prefs.edit().putInt(KEY_YOUTUBE_SCROLL_LIMIT, limit).apply()
        _settings.value = _settings.value.copy(youtubeScrollLimit = limit)
    }

    fun updateYouTubeTimeLimit(minutes: Int) {
        prefs.edit().putInt(KEY_YOUTUBE_TIME_LIMIT, minutes).apply()
        _settings.value = _settings.value.copy(youtubeTimeLimitMinutes = minutes)
    }

    fun updateEnforceLimits(enforce: Boolean) {
        prefs.edit().putBoolean(KEY_ENFORCE_LIMITS, enforce).apply()
        _settings.value = _settings.value.copy(enforceLimits = enforce)
    }

    fun updateLimitMode(mode: LimitMode) {
        prefs.edit().putString(KEY_LIMIT_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(limitMode = mode)
    }

    fun updateCloseAppOnLimit(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOSE_APP_ON_LIMIT, enabled).apply()
        _settings.value = _settings.value.copy(closeAppOnLimit = enabled)
    }

    fun updateShowBlockingOverlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_BLOCKING_OVERLAY, enabled).apply()
        _settings.value = _settings.value.copy(showBlockingOverlay = enabled)
    }

    fun resetTodayCounts() {
        prefs.edit()
            .putInt(KEY_INSTA_COUNT, 0)
            .putInt(KEY_YOUTUBE_COUNT, 0)
            .putInt(KEY_INSTA_SECONDS, 0)
            .putInt(KEY_YOUTUBE_SECONDS, 0)
            .apply()
        _settings.value = _settings.value.copy(
            instagramTodayCount = 0,
            youtubeTodayCount = 0,
            instagramSecondsToday = 0,
            youtubeSecondsToday = 0
        )
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
    }

    companion object {
        private const val PREFS_NAME = "scroll_counter_preferences"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

        private const val KEY_INSTA_ENABLED = "insta_enabled"
        private const val KEY_INSTA_SCROLL_LIMIT = "insta_scroll_limit"
        private const val KEY_INSTA_TIME_LIMIT = "insta_time_limit"
        private const val KEY_INSTA_COUNT = "insta_count"
        private const val KEY_INSTA_SECONDS = "insta_seconds"

        private const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
        private const val KEY_YOUTUBE_SCROLL_LIMIT = "youtube_scroll_limit"
        private const val KEY_YOUTUBE_TIME_LIMIT = "youtube_time_limit"
        private const val KEY_YOUTUBE_COUNT = "youtube_count"
        private const val KEY_YOUTUBE_SECONDS = "youtube_seconds"

        private const val KEY_ENFORCE_LIMITS = "enforce_limits"
        private const val KEY_LIMIT_MODE = "limit_mode"
        private const val KEY_CLOSE_APP_ON_LIMIT = "close_app_on_limit"
        private const val KEY_SHOW_BLOCKING_OVERLAY = "show_blocking_overlay"
    }
}
