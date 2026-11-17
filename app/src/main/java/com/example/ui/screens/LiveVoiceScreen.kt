package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AuraVoiceCharacter
import com.example.data.model.VoiceTone
import com.example.data.repository.LiveVoiceStatus
import com.example.ui.components.GeminiSparkleIcon
import com.example.ui.theme.LocalAuraColors

@Composable
fun LiveVoiceScreen(
    selectedVoice: AuraVoiceCharacter,
    status: LiveVoiceStatus,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onSelectVoice: (AuraVoiceCharacter) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val auraColors = LocalAuraColors.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        permissionDenied = !granted
        if (granted) {
            onStartVoice()
        }
    }

    LaunchedEffect(Unit) {
        if (hasAudioPermission) {
            onStartVoice()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onStopVoice()
        }
    }

    BackHandler {
        onStopVoice()
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Aura AI · Abhynex + Voice Character Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GeminiSparkleIcon(
                        size = 24.dp,
                        animate = status is LiveVoiceStatus.Connecting || status is LiveVoiceStatus.Active
                    )
                    Column {
                        Text(
                            text = "Aura Live Voice",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            text = "by Abhynex",
                            style = MaterialTheme.typography.labelSmall,
                            color = auraColors.sparkleMid
                        )
                    }
                }

                IconButton(
                    onClick = {
                        onStopVoice()
                        onBack()
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("live_voice_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close Aura Live Voice",
                        tint = Color.White
                    )
                }
            }

            // Active Voice Character Selector Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF12141C))
                    .border(
                        width = 1.dp,
                        color = auraColors.sparkleStart.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable { showVoicePicker = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("voice_character_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    tint = auraColors.sparkleStart,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${selectedVoice.displayName} · ${selectedVoice.trait}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Choose voice character",
                    tint = Color(0xFFB4BCD0),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Centerpiece Animated Live Orb & Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                LiveVoiceOrb(
                    isActive = status is LiveVoiceStatus.Active,
                    isConnecting = status is LiveVoiceStatus.Connecting,
                    isError = status is LiveVoiceStatus.Error || permissionDenied
                )

                when {
                    !hasAudioPermission -> {
                        Text(
                            text = "Microphone Access Needed",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Allow microphone access so Aura AI can listen and reply naturally in real time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA0A8B8),
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.testTag("grant_audio_permission_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp)
                            )
                            Text("Enable Microphone")
                        }
                    }

                    status is LiveVoiceStatus.Connecting -> {
                        Text(
                            text = "Connecting to ${selectedVoice.displayName}…",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Preparing real-time voice channel with Aura AI · Abhynex",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA0A8B8),
                            textAlign = TextAlign.Center
                        )
                    }

                    status is LiveVoiceStatus.Active -> {
                        Text(
                            text = "Listening · ${status.voice.displayName}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "${status.voice.description}. Speak freely or tap to switch among 30 characters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA0A8B8),
                            textAlign = TextAlign.Center
                        )
                    }

                    status is LiveVoiceStatus.Error -> {
                        Text(
                            text = "Live Voice Unavailable",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA0A8B8),
                            textAlign = TextAlign.Center
                        )
                        OutlinedButton(
                            onClick = onStartVoice,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.testTag("retry_live_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp)
                            )
                            Text("Retry Connection")
                        }
                    }

                    else -> {
                        Text(
                            text = "Ready to Talk",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = onStartVoice,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.testTag("start_live_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp)
                            )
                            Text("Start Conversation")
                        }
                    }
                }
            }

            // Bottom Action Dock: 30 Voices Button + End Session Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF141722),
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .border(1.dp, Color(0xFF282E42), RoundedCornerShape(26.dp))
                        .clickable { showVoicePicker = true }
                        .testTag("open_30_voices_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RecordVoiceOver,
                            contentDescription = null,
                            tint = auraColors.sparkleStart,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "30 Voices",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable {
                            onStopVoice()
                            onBack()
                        }
                        .testTag("end_live_voice_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End live voice session",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF141722),
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .border(1.dp, Color(0xFF282E42), RoundedCornerShape(26.dp))
                        .clickable {
                            if (hasAudioPermission) {
                                if (status is LiveVoiceStatus.Active || status is LiveVoiceStatus.Connecting) {
                                    onStopVoice()
                                } else {
                                    onStartVoice()
                                }
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        .testTag("toggle_live_mic_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = if (status is LiveVoiceStatus.Active) auraColors.emerald else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (status is LiveVoiceStatus.Active) "Live" else "Speak",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showVoicePicker) {
        VoiceCharacterPickerSheet(
            selectedVoice = selectedVoice,
            onSelectVoice = { voice ->
                onSelectVoice(voice)
                showVoicePicker = false
            },
            onDismiss = { showVoicePicker = false }
        )
    }
}

@Composable
private fun LiveVoiceOrb(
    isActive: Boolean,
    isConnecting: Boolean,
    isError: Boolean
) {
    val auraColors = LocalAuraColors.current
    val transition = rememberInfiniteTransition(label = "live_orb_pulse")

    val scalePulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = if (isActive) 1.14f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActive) 1100 else 1700,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    val ringAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (isActive || isConnecting) 0.85f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_alpha"
    )

    val primaryGlow = when {
        isError -> MaterialTheme.colorScheme.error
        isActive -> auraColors.sparkleStart
        else -> auraColors.sparkleMid
    }

    Box(
        modifier = Modifier.size(230.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val baseRadius = size.minDimension * 0.28f * scalePulse

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.50f * ringAlpha),
                        auraColors.sparkleEnd.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 2.1f
                ),
                radius = baseRadius * 2.1f,
                center = center
            )

            drawCircle(
                color = primaryGlow.copy(alpha = 0.38f * ringAlpha),
                radius = baseRadius * 1.45f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(primaryGlow, auraColors.sparkleMid, auraColors.sparkleEnd)
                ),
                radius = baseRadius,
                center = center
            )
        }

        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(44.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCharacterPickerSheet(
    selectedVoice: AuraVoiceCharacter,
    onSelectVoice: (AuraVoiceCharacter) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTone by remember { mutableStateOf(VoiceTone.ALL) }
    var search by remember { mutableStateOf("") }

    val filteredVoices = remember(selectedTone, search) {
        ALL_30_VOICE_CHARACTERS.filter { voice ->
            val toneMatch = selectedTone == VoiceTone.ALL || voice.tone == selectedTone
            val query = search.trim()
            val textMatch = query.isEmpty() ||
                voice.displayName.contains(query, ignoreCase = true) ||
                voice.trait.contains(query, ignoreCase = true) ||
                voice.description.contains(query, ignoreCase = true)
            toneMatch && textMatch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "Aura Voice Characters (30)",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Crafted by Abhynex · Tap any voice to switch live",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search 30 voice characters...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VoiceTone.entries.forEach { tone ->
                    val active = tone == selectedTone
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { selectedTone = tone }
                            .testTag("voice_tone_${tone.name.lowercase()}")
                    ) {
                        Text(
                            text = tone.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredVoices, key = { it.apiName }) { voice ->
                    val isSelected = voice.apiName == selectedVoice.apiName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectVoice(voice) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("voice_item_${voice.apiName}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = voice.displayName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = voice.trait,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = voice.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Selected voice",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
