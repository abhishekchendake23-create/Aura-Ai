package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ThemeMode
import com.example.service.AppShortcutHelper
import com.example.service.AutopilotScheduler
import com.example.ui.ChatViewModel
import com.example.ui.screens.AuraAppRoot
import com.example.ui.theme.AuraTheme
import com.example.widget.AuraQuickWidgetProvider

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        if (savedInstanceState == null) {
            processIncomingIntent(intent)
        }

        setContent {
            val themeMode by chatViewModel.themeMode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }

            LaunchedEffect(isDark) {
                val barStyle = if (isDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
            }

            AuraTheme(themeMode = themeMode) {
                AuraAppRoot(viewModel = chatViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(incoming: Intent?) {
        if (incoming == null) return

        if (incoming.action == AppShortcutHelper.ACTION_LAUNCH_BUILT_APP ||
            incoming.hasExtra(AppShortcutHelper.EXTRA_PINNED_APP_PATH) ||
            incoming.hasExtra(AppShortcutHelper.EXTRA_LAUNCH_ARTIFACT_ID)
        ) {
            val artifactId = incoming.getStringExtra(AppShortcutHelper.EXTRA_LAUNCH_ARTIFACT_ID)
            val title = incoming.getStringExtra(AppShortcutHelper.EXTRA_PINNED_APP_TITLE)
            val filePath = incoming.getStringExtra(AppShortcutHelper.EXTRA_PINNED_APP_PATH)
            chatViewModel.openPinnedAppShortcut(
                artifactId = artifactId,
                fallbackTitle = title,
                fallbackFilePath = filePath
            )
            incoming.action = null
            incoming.removeExtra(AppShortcutHelper.EXTRA_LAUNCH_ARTIFACT_ID)
            incoming.removeExtra(AppShortcutHelper.EXTRA_PINNED_APP_TITLE)
            incoming.removeExtra(AppShortcutHelper.EXTRA_PINNED_APP_PATH)
            return
        }

        val autopilotConvoId = incoming.getStringExtra(AutopilotScheduler.EXTRA_CONVERSATION_ID)
        if (!autopilotConvoId.isNullOrBlank()) {
            chatViewModel.openConversationById(autopilotConvoId)
            incoming.removeExtra(AutopilotScheduler.EXTRA_CONVERSATION_ID)
            return
        }

        val widgetAction = incoming.getStringExtra(AuraQuickWidgetProvider.EXTRA_WIDGET_ACTION)
        if (!widgetAction.isNullOrBlank()) {
            chatViewModel.handleWidgetAction(widgetAction)
            incoming.removeExtra(AuraQuickWidgetProvider.EXTRA_WIDGET_ACTION)
            return
        }

        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)
                val streamUri = IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                val uris = listOfNotNull(streamUri)
                if (!sharedText.isNullOrBlank() || uris.isNotEmpty()) {
                    chatViewModel.handleIncomingShare(sharedText, uris, incoming.type)
                    incoming.action = null
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)
                val streamUris = IntentCompat.getParcelableArrayListExtra(
                    incoming,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                ).orEmpty()
                if (!sharedText.isNullOrBlank() || streamUris.isNotEmpty()) {
                    chatViewModel.handleIncomingShare(sharedText, streamUris, incoming.type)
                    incoming.action = null
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val selectedText = incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!selectedText.isNullOrBlank()) {
                    chatViewModel.handleProcessText(selectedText)
                    incoming.action = null
                }
            }
        }
    }
}
