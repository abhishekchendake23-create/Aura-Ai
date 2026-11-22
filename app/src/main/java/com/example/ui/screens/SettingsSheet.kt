package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.AppLanguage
import com.example.data.model.ThemeMode
import com.example.ui.theme.LocalAuraColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    themeMode: ThemeMode,
    selectedLanguage: AppLanguage,
    savedApiKey: String,
    hasEffectiveApiKey: Boolean,
    savedGroqApiKey: String,
    hasEffectiveGroqApiKey: Boolean,
    preferFastGroq: Boolean,
    savedExaApiKey: String = "",
    hasEffectiveExaApiKey: Boolean = false,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveGroqApiKey: (String) -> Unit,
    onSaveExaApiKey: (String) -> Unit = {},
    onSetPreferFastGroq: (Boolean) -> Unit,
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onClearAllHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val auraColors = LocalAuraColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showClearConfirm by remember { mutableStateOf(false) }
    var apiKeyInput by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var revealKey by remember { mutableStateOf(false) }
    var groqKeyInput by remember(savedGroqApiKey) { mutableStateOf(savedGroqApiKey) }
    var revealGroqKey by remember { mutableStateOf(false) }
    var exaKeyInput by remember(savedExaApiKey) { mutableStateOf(savedExaApiKey) }
    var revealExaKey by remember { mutableStateOf(false) }

    LaunchedEffect(savedApiKey) {
        if (apiKeyInput.isBlank() && savedApiKey.isNotBlank()) {
            apiKeyInput = savedApiKey
        }
    }
    LaunchedEffect(savedGroqApiKey) {
        if (groqKeyInput.isBlank() && savedGroqApiKey.isNotBlank()) {
            groqKeyInput = savedGroqApiKey
        }
    }
    LaunchedEffect(savedExaApiKey) {
        if (exaKeyInput.isBlank() && savedExaApiKey.isNotBlank()) {
            exaKeyInput = savedExaApiKey
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // In-App API Key Configuration & Persistence
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = if (hasEffectiveApiKey) auraColors.emerald.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = if (hasEffectiveApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "API Key",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (hasEffectiveApiKey) auraColors.emeraldContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when {
                                savedApiKey.isNotBlank() -> "Saved on Device"
                                hasEffectiveApiKey -> "Built-in Ready"
                                else -> "Not Set"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasEffectiveApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }

                Text(
                    text = "Paste your API key below and tap Save. It is stored locally on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    singleLine = true,
                    placeholder = { Text("Paste API key...") },
                    visualTransformation = if (revealKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            if (apiKeyInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { apiKeyInput = "" },
                                    modifier = Modifier.testTag("clear_api_key_input")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear input",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { revealKey = !revealKey },
                                modifier = Modifier.testTag("toggle_reveal_api_key")
                            ) {
                                Icon(
                                    imageVector = if (revealKey) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (revealKey) "Hide API key" else "Show API key",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveApiKey(apiKeyInput)
                            onDismiss()
                        },
                        enabled = apiKeyInput.isNotBlank() && apiKeyInput.trim() != savedApiKey,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_api_key_button")
                    ) {
                        Text(
                            if (savedApiKey.isNotBlank() && apiKeyInput.trim() == savedApiKey) "Saved"
                            else "Save Key"
                        )
                    }

                    if (savedApiKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                apiKeyInput = ""
                                onSaveApiKey("")
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("remove_saved_api_key_button")
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }

            // Fast Engine Key (Groq) for ultra-fast responses & automatic fallback
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = if (hasEffectiveGroqApiKey) auraColors.emerald.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = if (hasEffectiveGroqApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Fast Engine Key (Groq)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (hasEffectiveGroqApiKey) auraColors.emeraldContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when {
                                savedGroqApiKey.isNotBlank() -> "Fast + Fallback ON"
                                hasEffectiveGroqApiKey -> "Built-in Ready"
                                else -> "Optional"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasEffectiveGroqApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Paste your Groq key (gsk_...) for ultra-fast answers and automatic fallback if primary servers are busy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = groqKeyInput,
                    onValueChange = { groqKeyInput = it },
                    singleLine = true,
                    placeholder = { Text("Paste gsk_... Groq key") },
                    visualTransformation = if (revealGroqKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            if (groqKeyInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { groqKeyInput = "" },
                                    modifier = Modifier.testTag("clear_groq_api_key_input")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear Groq input",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { revealGroqKey = !revealGroqKey },
                                modifier = Modifier.testTag("toggle_reveal_groq_api_key")
                            ) {
                                Icon(
                                    imageVector = if (revealGroqKey) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (revealGroqKey) "Hide Groq key" else "Show Groq key",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("groq_api_key_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveGroqApiKey(groqKeyInput)
                            onDismiss()
                        },
                        enabled = groqKeyInput.isNotBlank() && groqKeyInput.trim() != savedGroqApiKey,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_groq_api_key_button")
                    ) {
                        Text(
                            if (savedGroqApiKey.isNotBlank() && groqKeyInput.trim() == savedGroqApiKey) "Saved"
                            else "Save Groq Key"
                        )
                    }

                    if (savedGroqApiKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                groqKeyInput = ""
                                onSaveGroqApiKey("")
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("remove_saved_groq_api_key_button")
                        ) {
                            Text("Remove")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { onSetPreferFastGroq(!preferFastGroq) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Prioritize Fast Speed (Groq First)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Uses Groq's fastest models first; fallback is always active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = preferFastGroq,
                        onCheckedChange = onSetPreferFastGroq,
                        modifier = Modifier.testTag("prefer_fast_groq_switch")
                    )
                }
            }

            // Live Web Search Engine (Exa API)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = if (hasEffectiveExaApiKey) auraColors.emerald.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TravelExplore,
                            contentDescription = null,
                            tint = if (hasEffectiveExaApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Live Web Search (Exa API)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (hasEffectiveExaApiKey) auraColors.emeraldContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when {
                                savedExaApiKey.isNotBlank() -> "Exa Active"
                                hasEffectiveExaApiKey -> "Built-in Ready"
                                else -> "Optional"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasEffectiveExaApiKey) auraColors.emerald
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "Paste your Exa API key (or set EXA_API_KEY in Secrets) for high-accuracy live web search, news, and page content grounding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = exaKeyInput,
                    onValueChange = { exaKeyInput = it },
                    singleLine = true,
                    placeholder = { Text("Paste Exa API key...") },
                    visualTransformation = if (revealExaKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            if (exaKeyInput.isNotEmpty()) {
                                IconButton(
                                    onClick = { exaKeyInput = "" },
                                    modifier = Modifier.testTag("clear_exa_api_key_input")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear Exa input",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { revealExaKey = !revealExaKey },
                                modifier = Modifier.testTag("toggle_reveal_exa_api_key")
                            ) {
                                Icon(
                                    imageVector = if (revealExaKey) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (revealExaKey) "Hide Exa key" else "Show Exa key",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("exa_api_key_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveExaApiKey(exaKeyInput)
                            onDismiss()
                        },
                        enabled = exaKeyInput.isNotBlank() && exaKeyInput.trim() != savedExaApiKey,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_exa_api_key_button")
                    ) {
                        Text(
                            if (savedExaApiKey.isNotBlank() && exaKeyInput.trim() == savedExaApiKey) "Saved"
                            else "Save Exa Key"
                        )
                    }

                    if (savedExaApiKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                exaKeyInput = ""
                                onSaveExaApiKey("")
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("remove_saved_exa_api_key_button")
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "APPEARANCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = mode == themeMode
                        val icon = when (mode) {
                            ThemeMode.DARK -> Icons.Rounded.DarkMode
                            ThemeMode.LIGHT -> Icons.Rounded.LightMode
                            ThemeMode.SYSTEM -> Icons.Rounded.PhoneAndroid
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectTheme(mode) }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                                .testTag("theme_mode_${mode.name.lowercase()}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "RESPONSE LANGUAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val selected = lang == selectedLanguage
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectLanguage(lang) }
                                .padding(vertical = 11.dp, horizontal = 6.dp)
                                .testTag("settings_lang_${lang.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lang.nativeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(auraColors.emeraldContainer)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = auraColors.emerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Aura AI · Abhynex Active",
                        style = MaterialTheme.typography.titleSmall,
                        color = auraColors.emerald
                    )
                }
                Text(
                    text = "Powered by Abhynex intelligence modes, Aura Imagine visual synthesis, and 30 real-time voice characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Portable Offline Full Backup & Restore (.json)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Portable Backup & Restore (.json)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Export or restore all chats, memories, custom personas, and Knowledge Vault documents locally without any cloud account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExportBackup,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp).size(16.dp)
                        )
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_backup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp).size(16.dp)
                        )
                        Text("Restore")
                    }
                }
            }

            OutlinedButton(
                onClick = { showClearConfirm = true },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clear_all_history_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                )
                Text("Clear All Conversations")
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all conversations?") },
            text = { Text("This deletes all saved chat threads and bookmarks on this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearAllHistory()
                        onDismiss()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
