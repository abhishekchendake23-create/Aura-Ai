package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.AutopilotTaskEntity
import com.example.ui.theme.LocalAuraColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AutopilotPreset(
    val label: String,
    val title: String,
    val prompt: String,
    val useWebSearch: Boolean,
    val intervalHours: Int
)

private val AUTOPILOT_PRESETS = listOf(
    AutopilotPreset(
        label = "Tech & AI Digest",
        title = "Daily AI & Android Digest",
        prompt = "Search live web for the top 5 breakthroughs in Android, Kotlin, Jetpack Compose, and open AI releases today. Summarize each in crisp bullets with practical developer impact.",
        useWebSearch = true,
        intervalHours = 24
    ),
    AutopilotPreset(
        label = "Gold & Market Brief",
        title = "Daily Market & Gold Brief",
        prompt = "Search live web for today's 24K/22K gold rates in India (Pune/Mumbai), Nifty 50 / Sensex movement, and USD/INR rate. Present a clean Markdown table.",
        useWebSearch = true,
        intervalHours = 24
    ),
    AutopilotPreset(
        label = "Daily DSA Challenge",
        title = "Kotlin DSA Interview Prep",
        prompt = "Give me 1 high-yield Staff-engineer Kotlin/Graph/DP coding problem, optimal solution with time/space complexity analysis, and edge cases to test.",
        useWebSearch = false,
        intervalHours = 24
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutopilotSheet(
    tasks: List<AutopilotTaskEntity>,
    runningTaskId: String?,
    onCreateTask: (title: String, prompt: String, intervalHours: Int, useWebSearch: Boolean) -> Unit,
    onToggleTask: (AutopilotTaskEntity) -> Unit,
    onRunTaskNow: (AutopilotTaskEntity) -> Unit,
    onOpenConversation: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val auraColors = LocalAuraColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var intervalHours by remember { mutableIntStateOf(24) }
    var useWebSearch by remember { mutableStateOf(true) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = auraColors.sparkleStart,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Aura Autopilot Agents",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Autonomous scheduled briefings delivered directly to your inbox",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Presets
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ONE-TAP TEMPLATES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AUTOPILOT_PRESETS.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .clickable {
                                    title = preset.title
                                    prompt = preset.prompt
                                    intervalHours = preset.intervalHours
                                    useWebSearch = preset.useWebSearch
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Agent Title") },
                placeholder = { Text("e.g. Morning Tech & Gold Brief") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("autopilot_title_input")
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Autonomous Instruction") },
                placeholder = { Text("What should Aura research or prepare on schedule?") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("autopilot_prompt_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(6 to "6h", 12 to "12h", 24 to "Daily", 168 to "Weekly").forEach { (hrs, lbl) ->
                    val active = intervalHours == hrs
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { intervalHours = hrs }
                    ) {
                        Text(
                            text = lbl,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { useWebSearch = !useWebSearch }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Include Live Web Search",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Fetches fresh real-time news & rates each run",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useWebSearch, onCheckedChange = { useWebSearch = it })
            }

            Button(
                onClick = {
                    if (title.isNotBlank() && prompt.isNotBlank()) {
                        ensureNotificationPermissionIfNeeded()
                        onCreateTask(title.trim(), prompt.trim(), intervalHours, useWebSearch)
                        title = ""
                        prompt = ""
                    }
                },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().testTag("create_autopilot_button")
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Schedule Autopilot Agent")
            }

            if (tasks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No scheduled agents yet. Pick a template above or create a custom daily briefing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                val formatter = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tasks.forEach { task ->
                        val isRunning = runningTaskId == task.id
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .testTag("autopilot_task_${task.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = task.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val freqLabel = when (task.intervalHours) {
                                            168 -> "Weekly"
                                            24 -> "Daily"
                                            else -> "Every ${task.intervalHours}h"
                                        }
                                        val webTag = if (task.useWebSearch) " · Live Web" else ""
                                        Text(
                                            text = "$freqLabel$webTag",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = auraColors.emerald
                                        )
                                    }
                                    Switch(
                                        checked = task.isEnabled,
                                        onCheckedChange = {
                                            if (!task.isEnabled) ensureNotificationPermissionIfNeeded()
                                            onToggleTask(task)
                                        }
                                    )
                                    IconButton(onClick = { onDeleteTask(task.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete autopilot agent",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Text(
                                    text = task.prompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (task.lastRunAt > 0L) {
                                    Text(
                                        text = "Last run ${formatter.format(Date(task.lastRunAt))}: ${task.lastResultSummary}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            ensureNotificationPermissionIfNeeded()
                                            onRunTaskNow(task)
                                        },
                                        enabled = !isRunning,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("run_autopilot_now_${task.id}")
                                    ) {
                                        if (isRunning) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Running…")
                                        } else {
                                            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Run Now")
                                        }
                                    }

                                    if (!task.lastConversationId.isNullOrBlank()) {
                                        TextButton(
                                            onClick = {
                                                onDismiss()
                                                onOpenConversation(task.lastConversationId)
                                            }
                                        ) {
                                            Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Open Brief")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
