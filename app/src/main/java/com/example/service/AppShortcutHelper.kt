package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import java.io.File

object AppShortcutHelper {

    const val ACTION_LAUNCH_BUILT_APP = "com.example.action.LAUNCH_BUILT_APP"
    const val EXTRA_PINNED_APP_TITLE = "extra_pinned_app_title"
    const val EXTRA_PINNED_APP_PATH = "extra_pinned_app_path"
    const val EXTRA_LAUNCH_ARTIFACT_ID = "extra_launch_artifact_id"

    fun pinBuiltAppToHomeScreen(
        context: Context,
        appTitle: String,
        htmlCode: String,
        artifactId: String? = null
    ): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return false
        }
        val cleanTitle = appTitle.trim().ifBlank { "Aura App" }.take(24)
        val pinnedDir = File(context.filesDir, "pinned_apps").apply {
            if (!exists()) mkdirs()
        }
        val slug = cleanTitle
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
            .ifBlank { "aura_app" }
        val targetFile = File(pinnedDir, "${slug}_${System.currentTimeMillis() % 100000}.html")
        targetFile.writeText(htmlCode, Charsets.UTF_8)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_LAUNCH_BUILT_APP
            putExtra(EXTRA_PINNED_APP_TITLE, cleanTitle)
            putExtra(EXTRA_PINNED_APP_PATH, targetFile.absolutePath)
            if (!artifactId.isNullOrBlank()) {
                putExtra(EXTRA_LAUNCH_ARTIFACT_ID, artifactId)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val iconBitmap = createAppBadgeBitmap(cleanTitle)
        val shortcutId = "aura_app_${slug}_${System.currentTimeMillis() % 100000}"
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(cleanTitle)
            .setLongLabel(cleanTitle)
            .setIcon(IconCompat.createWithAdaptiveBitmap(iconBitmap))
            .setIntent(launchIntent)
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    private fun createAppBadgeBitmap(title: String): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(Color.parseColor("#181C24"), Color.parseColor("#0D1117")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D97757")
            alpha = 46
        }
        canvas.drawRoundRect(
            RectF(28f, 28f, size - 28f, size - 28f),
            44f,
            44f,
            pillPaint
        )

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#D97757")
        }
        canvas.drawRoundRect(
            RectF(30f, 30f, size - 30f, size - 30f),
            42f,
            42f,
            borderPaint
        )

        val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "A"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5F6F8")
            textSize = 72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = textPaint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(initial, size / 2f, baseline, textPaint)

        return bmp
    }
}
