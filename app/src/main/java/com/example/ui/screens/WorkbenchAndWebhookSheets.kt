package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CustomWebhookEntity
import com.example.data.local.PromptTemplateEntity
import com.example.data.model.AURA_JSON_SCHEMA_PRESETS
import com.example.data.model.ThinkingBudgetMode
import com.example.data.model.WorkbenchConfig
import com.example.data.repository.ChatRepository
import com.example.data.repository.WebhookExecutionResult
import com.example.data.repository.WebhookToolEngine
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchSheet(
    config: WorkbenchConfig,
    templates: List<PromptTemplateEntity>,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateTopP: (Float) -> Unit,
    onUpdateMaxTokens: (Int) -> Unit,
    onUpdateThinkingBudget: (ThinkingBudgetMode) -> Unit,
    onUpdateJsonSchema: (enabled: Boolean, schema: String) -> Unit,
    onUpdateSystemOverride: (String) -> Unit,
    onResetTuning: () -> Unit,
    onApplyTemplate: (PromptTemplateEntity, Map<String, String>, Boolean) -> Unit,
    onCreateTemplate: (title: String, category: String, systemPrompt: String, userTemplate: String) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auraColors = LocalAuraColors.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Tuning, 1 = Prompt Library

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "AI Workbench & Tuning Studio",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Aura Workbench Studio",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Fine-tune sampling, JSON schemas & reusable prompt variables",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectedTab == 0) {
                    OutlinedButton(
                        onClick = onResetTuning,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("workbench_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Reset workbench settings",
                            modifier = Modifier.size(16.dp).padding(end = 4.dp)
                        )
                        Text("Reset")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Run Parameters" to Icons.Rounded.Tune, "Prompt Library (${templates.size})" to Icons.Rounded.AutoAwesome)
                    .forEachIndexed { idx, (label, icon) ->
                        val active = selectedTab == idx
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedTab = idx }
                                .testTag("workbench_tab_$idx")
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
            }

            if (selectedTab == 0) {
                val roundedTemp = (config.temperature * 100f).roundToInt() / 100f
                val roundedTopP = (config.topP * 100f).roundToInt() / 100f

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Temperature (Creativity)", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = String.format("%.2f", roundedTemp),
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = config.temperature,
                            onValueChange = onUpdateTemperature,
                            valueRange = 0f..1.5f,
                            modifier = Modifier.testTag("workbench_temp_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Top-P Nucleus Sampling", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = String.format("%.2f", roundedTopP),
                                fontFamily = JetBrainsMonoFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = config.topP,
                            onValueChange = onUpdateTopP,
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.testTag("workbench_top_p_slider")
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Max Output Tokens", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2048 to "2K", 4096 to "4K", 8192 to "8K", 16384 to "16K", 32768 to "32K")
                            .forEach { (tok, label) ->
                                val active = config.maxOutputTokens == tok
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .clickable { onUpdateMaxTokens(tok) }
                                        .testTag("workbench_tokens_$tok")
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                    )
                                }
                            }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Thinking Budget Depth", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThinkingBudgetMode.entries.forEach { mode ->
                            val active = config.thinkingBudgetMode == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onUpdateThinkingBudget(mode) }
                                    .testTag("workbench_thinking_${mode.id.lowercase()}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
                                    tint = auraColors.emerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text("Structured JSON Mode", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Enforce strict machine-readable JSON responses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = config.jsonSchemaMode,
                                onCheckedChange = { onUpdateJsonSchema(it, config.jsonSchemaText) },
                                modifier = Modifier.testTag("workbench_json_switch")
                            )
                        }

                        if (config.jsonSchemaMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AURA_JSON_SCHEMA_PRESETS.forEach { (presetName, schemaSample) ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.clickable {
                                            onUpdateJsonSchema(true, schemaSample)
                                        }
                                    ) {
                                        Text(
                                            text = presetName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = config.jsonSchemaText,
                                onValueChange = { onUpdateJsonSchema(true, it) },
                                label = { Text("JSON Schema / Shape Example") },
                                minLines = 2,
                                maxLines = 5,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("workbench_json_schema_input")
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = config.systemInstructionOverride,
                    onValueChange = onUpdateSystemOverride,
                    label = { Text("Session System Instruction Override") },
                    placeholder = { Text("Optional global rules injected into every turn...") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("workbench_system_override_input")
                )
            } else {
                var selectedTemplate by remember(templates) { mutableStateOf(templates.firstOrNull()) }
                val varValues = remember(selectedTemplate?.id) { mutableStateMapOf<String, String>() }
                var showCreateForm by remember { mutableStateOf(false) }
                var newTitle by remember { mutableStateOf("") }
                var newCategory by remember { mutableStateOf("Coding") }
                var newSystem by remember { mutableStateOf("") }
                var newBody by remember { mutableStateOf("Analyze {{topic}} with focus on {{goal}}") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select a Prompt Blueprint",
                        style = MaterialTheme.typography.titleSmall
                    )
                    OutlinedButton(
                        onClick = { showCreateForm = !showCreateForm },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                        Text(if (showCreateForm) "Cancel" else "New Template")
                    }
                }

                if (showCreateForm) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text("Template Title") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newCategory,
                                onValueChange = { newCategory = it },
                                label = { Text("Category") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newSystem,
                                onValueChange = { newSystem = it },
                                label = { Text("Optional System Instruction") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newBody,
                                onValueChange = { newBody = it },
                                label = { Text("Prompt Template (use {{variable}} placeholders)") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (newTitle.isNotBlank() && newBody.isNotBlank()) {
                                        onCreateTemplate(newTitle, newCategory, newSystem, newBody)
                                        newTitle = ""
                                        newSystem = ""
                                        showCreateForm = false
                                    }
                                },
                                enabled = newTitle.isNotBlank() && newBody.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Save Prompt Blueprint")
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    templates.forEach { tpl ->
                        val active = tpl.id == selectedTemplate?.id
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { selectedTemplate = tpl }
                                .testTag("template_item_${tpl.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tpl.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = tpl.category,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = auraColors.emerald,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = tpl.userTemplate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!tpl.isBuiltIn) {
                                    IconButton(onClick = { onDeleteTemplate(tpl.id) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete template",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                selectedTemplate?.let { tpl ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val variables = remember(tpl.id, tpl.userTemplate) {
                        ChatRepository.extractTemplateVariables(tpl.userTemplate)
                    }
                    Text("Fill Blueprint Variables (${tpl.title})", style = MaterialTheme.typography.titleSmall)
                    variables.forEach { vName ->
                        OutlinedTextField(
                            value = varValues[vName].orEmpty(),
                            onValueChange = { varValues[vName] = it },
                            label = { Text(vName) },
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onApplyTemplate(tpl, varValues.toMap(), false)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("template_insert_button")
                        ) {
                            Text("Insert Draft")
                        }
                        Button(
                            onClick = {
                                onApplyTemplate(tpl, varValues.toMap(), true)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).testTag("template_run_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Run Now")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhookToolsSheet(
    webhooks: List<CustomWebhookEntity>,
    onCreateWebhook: (name: String, desc: String, url: String, method: String, headersJson: String, bodyTemplate: String) -> Unit,
    onToggleWebhook: (id: String, enabled: Boolean) -> Unit,
    onDeleteWebhook: (id: String) -> Unit,
    onTestWebhook: suspend (webhook: CustomWebhookEntity, query: String) -> WebhookExecutionResult,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auraColors = LocalAuraColors.current
    val scope = rememberCoroutineScope()

    var showAddForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("https://api.github.com/search/repositories?q={{query}}") }
    var httpMethod by remember { mutableStateOf("GET") }
    var headersJson by remember { mutableStateOf("") }
    var bodyTemplate by remember { mutableStateOf("""{"query": "{{query}}"}""") }
    var urlError by remember { mutableStateOf<String?>(null) }

    var testingId by remember { mutableStateOf<String?>(null) }
    var lastTestResult by remember { mutableStateOf<WebhookExecutionResult?>(null) }

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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = "Custom Webhook REST Tools",
                        tint = auraColors.emerald,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Custom REST Tool Connectors",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "MCP-Lite live GET/POST webhooks injected automatically into prompts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showAddForm = !showAddForm },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("webhook_add_toggle")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    Text(if (showAddForm) "Close" else "Add Tool")
                }
            }

            if (showAddForm) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Tool Name") },
                            placeholder = { Text("e.g. GitHub Repo Finder") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("webhook_name_input")
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("GET", "POST").forEach { m ->
                                val active = httpMethod == m
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { httpMethod = m }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(m, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = endpointUrl,
                            onValueChange = {
                                endpointUrl = it
                                urlError = null
                            },
                            label = { Text("Public HTTPS Endpoint ({{query}} supported)") },
                            isError = urlError != null,
                            supportingText = urlError?.let { err -> { Text(err) } },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("webhook_url_input")
                        )
                        OutlinedTextField(
                            value = headersJson,
                            onValueChange = { headersJson = it },
                            label = { Text("Optional Headers JSON (e.g. {\"Accept\": \"application/json\"})") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (httpMethod == "POST") {
                            OutlinedTextField(
                                value = bodyTemplate,
                                onValueChange = { bodyTemplate = it },
                                label = { Text("JSON Body Template ({{query}})") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Button(
                            onClick = {
                                if (!WebhookToolEngine.isSafePublicWebhookUrl(endpointUrl)) {
                                    urlError = "Must be a valid public http/https URL (localhost & private IPs blocked)."
                                } else if (name.isNotBlank()) {
                                    onCreateWebhook(name, description, endpointUrl, httpMethod, headersJson, bodyTemplate)
                                    name = ""
                                    description = ""
                                    headersJson = ""
                                    showAddForm = false
                                }
                            },
                            enabled = name.isNotBlank() && endpointUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("webhook_save_button")
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Save Webhook Connector")
                        }
                    }
                }
            }

            if (webhooks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No custom REST tools configured yet. Add any public API (e.g., Docs search, Weather, Status endpoint, Currency API) so Aura fetches live JSON before answering.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                webhooks.forEach { hook ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .testTag("webhook_card_${hook.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${hook.httpMethod} · ${hook.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = hook.endpointUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = JetBrainsMonoFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = hook.isEnabled,
                                    onCheckedChange = { onToggleWebhook(hook.id, it) }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        testingId = hook.id
                                        scope.launch {
                                            lastTestResult = onTestWebhook(hook, "aura")
                                            testingId = null
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    enabled = testingId == null
                                ) {
                                    if (testingId == hook.id) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp).padding(end = 4.dp))
                                        Text("Test Call")
                                    }
                                }
                                IconButton(onClick = { onDeleteWebhook(hook.id) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete webhook",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            lastTestResult?.let { res ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (res.success) auraColors.emeraldContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${res.webhookName} · HTTP ${res.statusCode} (${if (res.success) "OK" else "FAILED"})",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (res.success) auraColors.emerald else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = res.responseText.take(450),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
