package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.model.ALL_30_VOICE_CHARACTERS
import com.example.data.model.AppLanguage
import com.example.data.model.AuraVoiceCharacter
import com.example.data.model.VoiceTone
import com.example.data.repository.LiveVoiceStatus
import com.example.ui.components.GeminiSparkleIcon
import com.example.ui.theme.LocalAuraColors
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@Composable
fun LiveVoiceScreen(
    selectedVoice: AuraVoiceCharacter,
    selectedLanguage: AppLanguage,
    status: LiveVoiceStatus,
    isMuted: Boolean,
    liveTranscript: String,
    audioLevel: Float,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectVoice: (AuraVoiceCharacter) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSendCameraFrame: (ByteArray) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var isCameraEnabled by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isCameraEnabled = true
        }
    }

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
            // Top Header
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isCameraEnabled) {
                                isCameraEnabled = false
                            } else {
                                val camGranted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (camGranted) {
                                    isCameraEnabled = true
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("live_camera_eye_toggle")
                    ) {
                        Icon(
                            imageVector = if (isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                            contentDescription = stringResource(R.string.cd_camera_eye),
                            tint = if (isCameraEnabled) auraColors.emerald else Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            isCameraEnabled = false
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
            }

            // Voice Character & Language Selector Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val active = lang == selectedLanguage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (active) auraColors.sparkleStart.copy(alpha = 0.22f)
                                    else Color(0xFF10131C)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (active) auraColors.sparkleStart else Color(0xFF23283A),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectLanguage(lang) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("live_lang_${lang.id}")
                        ) {
                            Text(
                                text = lang.nativeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) Color.White else Color(0xFFA0A8B8)
                            )
                        }
                    }
                }
            }

            // Centerpiece Reactive Orb & Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                LiveVoiceOrb(
                    isActive = status is LiveVoiceStatus.Active,
                    isConnecting = status is LiveVoiceStatus.Connecting,
                    isMuted = isMuted,
                    isError = status is LiveVoiceStatus.Error || permissionDenied,
                    audioLevel = audioLevel
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
                            text = "Preparing real-time voice channel with Aura AI",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA0A8B8),
                            textAlign = TextAlign.Center
                        )
                    }

                    status is LiveVoiceStatus.Active -> {
                        Text(
                            text = if (isMuted) "Mic Muted · ${status.voice.displayName}"
                            else "Listening · ${status.voice.displayName}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        AnimatedVisibility(visible = liveTranscript.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF10131D),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFF23283A), RoundedCornerShape(16.dp))
                            ) {
                                Text(
                                    text = liveTranscript,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFE6ECFF),
                                    textAlign = TextAlign.Center,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                        if (liveTranscript.isBlank()) {
                            Text(
                                text = "${status.voice.description}. Speak naturally in Marathi, Hindi or English.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFA0A8B8),
                                textAlign = TextAlign.Center
                            )
                        }
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

            // Bottom Action Dock: 30 Voices · End Call · Mute / Start
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
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
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
                    color = if (isMuted) Color(0xFF281820) else Color(0xFF141722),
                    modifier = Modifier
                        .clip(RoundedCornerShape(26.dp))
                        .border(
                            1.dp,
                            if (isMuted) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                            else Color(0xFF282E42),
                            RoundedCornerShape(26.dp)
                        )
                        .clickable {
                            if (!hasAudioPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (status is LiveVoiceStatus.Active) {
                                onToggleMute()
                            } else if (status !is LiveVoiceStatus.Connecting) {
                                onStartVoice()
                            }
                        }
                        .testTag("toggle_live_mic_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = when {
                                isMuted -> MaterialTheme.colorScheme.error
                                status is LiveVoiceStatus.Active -> auraColors.emerald
                                else -> Color.White
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = when {
                                isMuted -> "Muted"
                                status is LiveVoiceStatus.Active -> "Mute"
                                status is LiveVoiceStatus.Connecting -> "Wait…"
                                else -> "Speak"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
        if (isCameraEnabled) {
            LiveCameraEyeOverlay(
                lifecycleOwner = lifecycleOwner,
                isSessionActive = status is LiveVoiceStatus.Active,
                useFrontCamera = useFrontCamera,
                onToggleFacing = { useFrontCamera = !useFrontCamera },
                onSendCameraFrame = onSendCameraFrame,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 68.dp, end = 16.dp)
            )
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
private fun LiveCameraEyeOverlay(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    isSessionActive: Boolean,
    useFrontCamera: Boolean,
    onToggleFacing: () -> Unit,
    onSendCameraFrame: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val auraColors = LocalAuraColors.current
    val latestOnFrame by rememberUpdatedState(onSendCameraFrame)
    val latestSessionActive by rememberUpdatedState(isSessionActive)
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisExecutor.shutdownNow() }
        }
    }

    DisposableEffect(lifecycleOwner, useFrontCamera) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var lastSentAtMs = 0L

        val listener = Runnable {
            runCatching {
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (latestSessionActive && now - lastSentAtMs >= 1500L) {
                            lastSentAtMs = now
                            val rawBitmap = imageProxy.toBitmap()
                            val maxDim = 512
                            val scale = minOf(
                                maxDim.toFloat() / rawBitmap.width.coerceAtLeast(1),
                                maxDim.toFloat() / rawBitmap.height.coerceAtLeast(1),
                                1f
                            )
                            val scaled = if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    rawBitmap,
                                    (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                                    (rawBitmap.height * scale).toInt().coerceAtLeast(1),
                                    true
                                )
                            } else {
                                rawBitmap
                            }
                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                            if (scaled !== rawBitmap) scaled.recycle()
                            rawBitmap.recycle()
                            latestOnFrame(baos.toByteArray())
                        }
                    } catch (_: Throwable) {
                        // Ignore occasional frame drop
                    } finally {
                        imageProxy.close()
                    }
                }

                val selector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }
        }
        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                providerFuture.get().unbindAll()
            }
        }
    }

    Box(
        modifier = modifier
            .size(width = 114.dp, height = 156.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E121D))
            .border(1.5.dp, auraColors.emerald.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
            .clickable(onClick = onToggleFacing)
            .testTag("live_camera_preview_card")
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (useFrontCamera) "EYE · FRONT" else "EYE · BACK",
                style = MaterialTheme.typography.labelSmall,
                color = auraColors.emerald
            )
        }
    }
}

@Composable
private fun LiveVoiceOrb(
    isActive: Boolean,
    isConnecting: Boolean,
    isMuted: Boolean,
    isError: Boolean,
    audioLevel: Float
) {
    val auraColors = LocalAuraColors.current
    val transition = rememberInfiniteTransition(label = "live_orb_pulse")

    val basePulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = if (isActive && !isMuted) 1.08f else 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActive) 1100 else 1700,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    val reactiveBoost by animateFloatAsState(
        targetValue = if (isActive && !isMuted) audioLevel * 0.22f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "orb_audio_level"
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
        isMuted -> Color(0xFF7B8194)
        isActive -> auraColors.sparkleStart
        else -> auraColors.sparkleMid
    }

    val combinedPulse = basePulse + reactiveBoost

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val baseRadius = size.minDimension * 0.28f * combinedPulse

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
            imageVector = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(42.dp)
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
