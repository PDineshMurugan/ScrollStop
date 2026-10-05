package com.scrollcounter.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class LimitMode {
    SCROLLS,
    TIME_MINUTES
}

data class DailyRecord(
    val date: String,
    val dayLabel: String,
    val isToday: Boolean,
    val instagramCount: Int,
    val youtubeCount: Int,
    val instagramSeconds: Int,
    val youtubeSeconds: Int
) {
    val totalCount: Int get() = instagramCount + youtubeCount
    val totalMinutes: Int get() = (instagramSeconds + youtubeSeconds) / 60
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
    val showBlockingOverlay: Boolean = true,
    val weeklyRecords: List<DailyRecord> = emptyList()
)

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private var cachedTodayDate: String = getTodayDateString()
    private var lastDailyCheckMillis: Long = System.currentTimeMillis()

    init {
        checkDailyReset(force = true)
    }

    private fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    @Synchronized
    fun checkDailyReset(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDailyCheckMillis < 60_000L) {
            return
        }
        lastDailyCheckMillis = now
        val today = getTodayDateString()
        cachedTodayDate = today

        val lastDate = prefs.getString(KEY_LAST_RESET_DATE, "")
        if (!lastDate.isNullOrEmpty() && lastDate != today) {
            // Persist the previous day's final stats
            val oldInsta = prefs.getInt(KEY_INSTA_COUNT, 0)
            val oldYt = prefs.getInt(KEY_YOUTUBE_COUNT, 0)
            val oldInstaSecs = prefs.getInt(KEY_INSTA_SECONDS, 0)
            val oldYtSecs = prefs.getInt(KEY_YOUTUBE_SECONDS, 0)
            prefs.edit().putString(KEY_DAILY_PREFIX + lastDate, "$oldInsta,$oldYt,$oldInstaSecs,$oldYtSecs").apply()

            prefs.edit()
                .putString(KEY_LAST_RESET_DATE, today)
                .putInt(KEY_INSTA_COUNT, 0)
                .putInt(KEY_YOUTUBE_COUNT, 0)
                .putInt(KEY_INSTA_SECONDS, 0)
                .putInt(KEY_YOUTUBE_SECONDS, 0)
                .apply()
            saveTodayRecord(0, 0, 0, 0)
            _settings.value = loadSettings()
        } else if (lastDate.isNullOrEmpty()) {
            prefs.edit().putString(KEY_LAST_RESET_DATE, today).apply()
            saveTodayRecord(
                prefs.getInt(KEY_INSTA_COUNT, 0),
                prefs.getInt(KEY_YOUTUBE_COUNT, 0),
                prefs.getInt(KEY_INSTA_SECONDS, 0),
                prefs.getInt(KEY_YOUTUBE_SECONDS, 0)
            )
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
            showBlockingOverlay = prefs.getBoolean(KEY_SHOW_BLOCKING_OVERLAY, true),
            weeklyRecords = getLast7DaysRecords()
        )
    }

    fun getLast7DaysRecords(): List<DailyRecord> {
        val list = mutableListOf<DailyRecord>()
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfDay = SimpleDateFormat("EEE", Locale.getDefault())
        val todayKey = getTodayDateString()

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)

        for (i in 0..6) {
            val dateKey = sdfKey.format(cal.time)
            val dayLabel = sdfDay.format(cal.time)
            val isToday = dateKey == todayKey

            val record = if (isToday) {
                DailyRecord(
                    date = dateKey,
                    dayLabel = dayLabel,
                    isToday = true,
                    instagramCount = prefs.getInt(KEY_INSTA_COUNT, 0),
                    youtubeCount = prefs.getInt(KEY_YOUTUBE_COUNT, 0),
                    instagramSeconds = prefs.getInt(KEY_INSTA_SECONDS, 0),
                    youtubeSeconds = prefs.getInt(KEY_YOUTUBE_SECONDS, 0)
                )
            } else {
                getSavedDailyRecord(dateKey, dayLabel)
            }
            list.add(record)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return list
    }

    private fun getSavedDailyRecord(dateKey: String, dayLabel: String): DailyRecord {
        val raw = prefs.getString(KEY_DAILY_PREFIX + dateKey, null) ?: return DailyRecord(
            date = dateKey,
            dayLabel = dayLabel,
            isToday = false,
            instagramCount = 0,
            youtubeCount = 0,
            instagramSeconds = 0,
            youtubeSeconds = 0
        )
        val parts = raw.split(",")
        return try {
            DailyRecord(
                date = dateKey,
                dayLabel = dayLabel,
                isToday = false,
                instagramCount = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                youtubeCount = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                instagramSeconds = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                youtubeSeconds = parts.getOrNull(3)?.toIntOrNull() ?: 0
            )
        } catch (_: Exception) {
            DailyRecord(dateKey, dayLabel, false, 0, 0, 0, 0)
        }
    }

    private fun saveTodayRecord(instaCount: Int, ytCount: Int, instaSecs: Int, ytSecs: Int) {
        val today = getTodayDateString()
        prefs.edit().putString(KEY_DAILY_PREFIX + today, "$instaCount,$ytCount,$instaSecs,$ytSecs").apply()
    }

    private fun updateTodayInWeeklyRecords(
        instaCount: Int,
        ytCount: Int,
        instaSecs: Int,
        ytSecs: Int
    ): List<DailyRecord> {
        val current = _settings.value.weeklyRecords
        if (current.isEmpty()) return getLast7DaysRecords()
        return current.map { record ->
            if (record.isToday) {
                record.copy(
                    instagramCount = instaCount,
                    youtubeCount = ytCount,
                    instagramSeconds = instaSecs,
                    youtubeSeconds = ytSecs
                )
            } else {
                record
            }
        }
    }

    @Synchronized
    fun incrementInstagramCount(): Int {
        checkDailyReset()
        val newCount = _settings.value.instagramTodayCount + 1
        prefs.edit().putInt(KEY_INSTA_COUNT, newCount).apply()
        saveTodayRecord(newCount, _settings.value.youtubeTodayCount, _settings.value.instagramSecondsToday, _settings.value.youtubeSecondsToday)
        _settings.value = _settings.value.copy(
            instagramTodayCount = newCount,
            weeklyRecords = updateTodayInWeeklyRecords(
                instaCount = newCount,
                ytCount = _settings.value.youtubeTodayCount,
                instaSecs = _settings.value.instagramSecondsToday,
                ytSecs = _settings.value.youtubeSecondsToday
            )
        )
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        return newCount
    }

    @Synchronized
    fun addInstagramTime(seconds: Int): Int {
        checkDailyReset()
        val total = _settings.value.instagramSecondsToday + seconds
        // Save to preferences on periodic intervals (every 5 seconds) to reduce flash I/O churn
        if (total % 5 == 0) {
            prefs.edit().putInt(KEY_INSTA_SECONDS, total).apply()
            saveTodayRecord(_settings.value.instagramTodayCount, _settings.value.youtubeTodayCount, total, _settings.value.youtubeSecondsToday)
        }
        _settings.value = _settings.value.copy(
            instagramSecondsToday = total,
            weeklyRecords = updateTodayInWeeklyRecords(
                instaCount = _settings.value.instagramTodayCount,
                ytCount = _settings.value.youtubeTodayCount,
                instaSecs = total,
                ytSecs = _settings.value.youtubeSecondsToday
            )
        )
        if (total % 10 == 0) {
            com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        }
        return total
    }

    @Synchronized
    fun incrementYouTubeCount(): Int {
        checkDailyReset()
        val newCount = _settings.value.youtubeTodayCount + 1
        prefs.edit().putInt(KEY_YOUTUBE_COUNT, newCount).apply()
        saveTodayRecord(_settings.value.instagramTodayCount, newCount, _settings.value.instagramSecondsToday, _settings.value.youtubeSecondsToday)
        _settings.value = _settings.value.copy(
            youtubeTodayCount = newCount,
            weeklyRecords = updateTodayInWeeklyRecords(
                instaCount = _settings.value.instagramTodayCount,
                ytCount = newCount,
                instaSecs = _settings.value.instagramSecondsToday,
                ytSecs = _settings.value.youtubeSecondsToday
            )
        )
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
        return newCount
    }

    @Synchronized
    fun addYouTubeTime(seconds: Int): Int {
        checkDailyReset()
        val total = _settings.value.youtubeSecondsToday + seconds
        if (total % 5 == 0) {
            prefs.edit().putInt(KEY_YOUTUBE_SECONDS, total).apply()
            saveTodayRecord(_settings.value.instagramTodayCount, _settings.value.youtubeTodayCount, _settings.value.instagramSecondsToday, total)
        }
        _settings.value = _settings.value.copy(
            youtubeSecondsToday = total,
            weeklyRecords = updateTodayInWeeklyRecords(
                instaCount = _settings.value.instagramTodayCount,
                ytCount = _settings.value.youtubeTodayCount,
                instaSecs = _settings.value.instagramSecondsToday,
                ytSecs = total
            )
        )
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
        saveTodayRecord(0, 0, 0, 0)
        _settings.value = _settings.value.copy(
            instagramTodayCount = 0,
            youtubeTodayCount = 0,
            instagramSecondsToday = 0,
            youtubeSecondsToday = 0,
            weeklyRecords = getLast7DaysRecords()
        )
        com.scrollcounter.app.widget.ScrollCounterWidget.updateAllWidgets(context)
    }

    companion object {
        private const val PREFS_NAME = "scroll_counter_preferences"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"
        private const val KEY_DAILY_PREFIX = "daily_record_"

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
