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
            val prefs = try {
                ScrollCounterApp.instance.preferencesManager.settings.value
            } catch (_: Exception) {
                null
            }

            val instaCount = prefs?.instagramTodayCount ?: 0
            val instaSecs = prefs?.instagramSecondsToday ?: 0
            val ytCount = prefs?.youtubeTodayCount ?: 0
            val ytSecs = prefs?.youtubeSecondsToday ?: 0

            val remoteViews = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ Responsive RemoteViews (fluidly scales as user resizes widget)
                val standardViews = buildViews(context, R.layout.widget_scroll_counter, instaCount, instaSecs, ytCount, ytSecs, isCompact = false)
                val compactViews = buildViews(context, R.layout.widget_scroll_counter_compact, instaCount, instaSecs, ytCount, ytSecs, isCompact = true)
                RemoteViews(
                    mapOf(
                        SizeF(100f, 30f) to compactViews,
                        SizeF(100f, 65f) to standardViews
                    )
                )
            } else {
                // Android 8-11: Adaptive layout based on widget minHeight
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                val isCompact = minHeight in 1..65
                val layoutId = if (isCompact) R.layout.widget_scroll_counter_compact else R.layout.widget_scroll_counter
                buildViews(context, layoutId, instaCount, instaSecs, ytCount, ytSecs, isCompact = isCompact)
            }

            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
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

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, ScrollCounterWidget::class.java)
                val ids = appWidgetManager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    for (id in ids) {
                        updateWidget(context, appWidgetManager, id)
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
            val remainderSecs = seconds % 60

            return if (hours > 0) {
                "${hours}h ${remainderMins}m"
            } else if (compact) {
                "${totalMinutes}m"
            } else if (remainderSecs > 0) {
                "${totalMinutes}m ${remainderSecs}s"
            } else {
                "${totalMinutes}m"
            }
        }
    }
}
