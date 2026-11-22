package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R

class AuraQuickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateAppWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val EXTRA_WIDGET_ACTION = "extra_widget_action"
        const val ACTION_ASK = "ask"
        const val ACTION_LIVE_VOICE = "live_voice"
        const val ACTION_SCANNER = "scanner"

        private fun createActionIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = "com.example.aura.WIDGET_ACTION_$action"
                putExtra(EXTRA_WIDGET_ACTION, action)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_aura_quick).apply {
                setOnClickPendingIntent(R.id.widget_btn_ask, createActionIntent(context, ACTION_ASK, 101))
                setOnClickPendingIntent(R.id.widget_btn_voice, createActionIntent(context, ACTION_LIVE_VOICE, 102))
                setOnClickPendingIntent(R.id.widget_btn_scan, createActionIntent(context, ACTION_SCANNER, 103))
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
