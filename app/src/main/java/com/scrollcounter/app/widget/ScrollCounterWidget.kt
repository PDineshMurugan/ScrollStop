package com.scrollcounter.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.scrollcounter.app.MainActivity
import com.scrollcounter.app.R
import com.scrollcounter.app.ScrollCounterApp
import com.scrollcounter.app.data.UserSettings

class ScrollCounterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            try {
                val prefs = try {
                    ScrollCounterApp.instance.preferencesManager.settings.value
                } catch (_: Exception) {
                    null
                }

                val instaCount = prefs?.instagramTodayCount ?: 0
                val instaSecs = prefs?.instagramSecondsToday ?: 0
                val ytCount = prefs?.youtubeTodayCount ?: 0
                val ytSecs = prefs?.youtubeSecondsToday ?: 0

                val remoteViews = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Android 12+ Responsive RemoteViews (fluidly scales as user resizes widget)
                        val compactViews = buildViews(context, R.layout.widget_scroll_counter_compact, instaCount, instaSecs, ytCount, ytSecs, isCompact = true)
                        val standardViews = buildViews(context, R.layout.widget_scroll_counter, instaCount, instaSecs, ytCount, ytSecs, isCompact = false)
                        val expandedViews = buildExpandedViews(context, prefs, instaCount, instaSecs, ytCount, ytSecs)
                        RemoteViews(
                            mapOf(
                                SizeF(100f, 30f) to compactViews,      // Slim 1-row
                                SizeF(100f, 65f) to standardViews,     // Standard (2x1, 3x1, 4x1, 2x2, 3x2, 4x2, 2x3: Reels & Shorts count + time alone)
                                SizeF(200f, 185f) to expandedViews     // Both min width (>= 200dp: 3+ cols) AND min height (>= 185dp: 3+ rows) needed for 7-day bars (3x3, 4x3)
                            )
                        )
                    } else {
                        // Android 8-11: Adaptive layout based on widget dimensions
                        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)

                        // ONLY show 7-day bars when BOTH minWidth >= 200dp AND minHeight >= 185dp
                        val isExpanded = minWidth >= 200 && minHeight >= 185
                        val isCompact = !isExpanded && minHeight in 1..65

                        if (isExpanded) {
                            buildExpandedViews(context, prefs, instaCount, instaSecs, ytCount, ytSecs)
                        } else {
                            val layoutId = if (isCompact) R.layout.widget_scroll_counter_compact else R.layout.widget_scroll_counter
                            buildViews(context, layoutId, instaCount, instaSecs, ytCount, ytSecs, isCompact = isCompact)
                        }
                    }
                } catch (_: Throwable) {
                    // Fail-safe fallback to standard view to prevent "Can't load widget"
                    buildViews(context, R.layout.widget_scroll_counter, instaCount, instaSecs, ytCount, ytSecs, isCompact = false)
                }

                appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
            } catch (_: Throwable) {}
        }

        private fun buildViews(
            context: Context,
            layoutId: Int,
            instaCount: Int,
            instaSecs: Int,
            ytCount: Int,
            ytSecs: Int,
            isCompact: Boolean
        ): RemoteViews {
            val instaTimeStr = formatSeconds(instaSecs, compact = isCompact)
            val ytTimeStr = formatSeconds(ytSecs, compact = isCompact)

            return RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.tv_widget_insta_count, "$instaCount")
                setTextViewText(R.id.tv_widget_insta_time, "⏱ $instaTimeStr")
                setTextViewText(R.id.tv_widget_yt_count, "$ytCount")
                setTextViewText(R.id.tv_widget_yt_time, "⏱ $ytTimeStr")

                // Click on widget opens main app
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
        }

        private fun buildExpandedViews(
            context: Context,
            prefs: UserSettings?,
            instaCount: Int,
            instaSecs: Int,
            ytCount: Int,
            ytSecs: Int
        ): RemoteViews {
            val instaTimeStr = formatSeconds(instaSecs, compact = true)
            val ytTimeStr = formatSeconds(ytSecs, compact = true)

            // Weekly Analytics Calculations
            val weekly = prefs?.weeklyRecords ?: emptyList()
            val weeklyTotalScrolls = weekly.sumOf { it.totalCount }
            val weeklyTotalMinutes = weekly.sumOf { it.totalMinutes }

            val weeklySummaryText = if (weeklyTotalMinutes >= 60) {
                "${weeklyTotalMinutes / 60}h ${weeklyTotalMinutes % 60}m • $weeklyTotalScrolls scrolls"
            } else {
                "${weeklyTotalMinutes}m • $weeklyTotalScrolls scrolls"
            }

            val valIds = intArrayOf(
                R.id.tv_bar_val_0, R.id.tv_bar_val_1, R.id.tv_bar_val_2,
                R.id.tv_bar_val_3, R.id.tv_bar_val_4, R.id.tv_bar_val_5, R.id.tv_bar_val_6
            )
            val barIds = intArrayOf(
                R.id.iv_bar_0, R.id.iv_bar_1, R.id.iv_bar_2,
                R.id.iv_bar_3, R.id.iv_bar_4, R.id.iv_bar_5, R.id.iv_bar_6
            )
            val dayIds = intArrayOf(
                R.id.tv_bar_day_0, R.id.tv_bar_day_1, R.id.tv_bar_day_2,
                R.id.tv_bar_day_3, R.id.tv_bar_day_4, R.id.tv_bar_day_5, R.id.tv_bar_day_6
            )

            val maxMinutes = weekly.maxOfOrNull { it.totalMinutes }?.coerceAtLeast(1) ?: 1

            return RemoteViews(context.packageName, R.layout.widget_scroll_counter_expanded).apply {
                setTextViewText(R.id.tv_widget_insta_count, "$instaCount")
                setTextViewText(R.id.tv_widget_insta_time, "⏱ $instaTimeStr")
                setTextViewText(R.id.tv_widget_yt_count, "$ytCount")
                setTextViewText(R.id.tv_widget_yt_time, "⏱ $ytTimeStr")
                setTextViewText(R.id.tv_widget_weekly_summary, weeklySummaryText)

                // Populate 7-day time bars
                for (i in 0..6) {
                    val record = weekly.getOrNull(i)
                    val minutes = record?.totalMinutes ?: 0
                    val fillRatio = (minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
                    val level = if (minutes > 0) (fillRatio * 8200 + 1800).toInt() else 0
                    val isToday = record?.isToday == true || i == 6

                    setTextViewText(valIds[i], if (minutes > 0) "${minutes}m" else "")
                    setTextViewText(dayIds[i], if (isToday) "Today" else (record?.dayLabel ?: ""))
                    setImageViewResource(barIds[i], if (isToday) R.drawable.widget_bar_today else R.drawable.widget_bar_past)
                    setInt(barIds[i], "setImageLevel", level)
                }

                // Click on widget opens main app
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val providers = listOf(
                    ScrollCounterWidget::class.java,
                    ScrollAnalyticsWidget::class.java
                )
                for (providerClass in providers) {
                    val component = ComponentName(context, providerClass)
                    val ids = appWidgetManager.getAppWidgetIds(component)
                    if (ids.isNotEmpty()) {
                        for (id in ids) {
                            updateWidget(context, appWidgetManager, id)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        private fun formatSeconds(seconds: Int, compact: Boolean): String {
            if (seconds < 60) {
                return "${seconds}s"
            }
            val totalMinutes = seconds / 60
            val hours = totalMinutes / 60
            val remainderMins = totalMinutes % 60

            return if (hours > 0) {
                if (remainderMins > 0) "${hours}h ${remainderMins}m" else "${hours}h"
            } else {
                "${totalMinutes}m"
            }
        }
    }
}
