package com.example.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.MessageEntity
import com.example.data.repository.parseSources
import com.example.ui.StreamingMessageState
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun UserMessageBubble(
    message: MessageEntity,
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (message.attachmentUri != null && message.attachmentName != null) {
            if (message.isImageAttachment) {
                AsyncImage(
                    model = Uri.parse(message.attachmentUri),
                    contentDescription = message.attachmentName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .height(168.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(18.dp)
                        )
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = message.attachmentName,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Surface(
            shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
            color = auraColors.userBubble,
            modifier = Modifier
                .widthIn(max = 316.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
                )
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun AssistantMessageBlock(
    message: MessageEntity,
    isSpeaking: Boolean,
    isLatestAssistantMessage: Boolean = false,
    onToggleSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    onToggleBookmark: () -> Unit,
    onEditGeneratedImage: (String) -> Unit,
    onFollowUpPrompt: (String) -> Unit = {},
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val sources = remember(message.sourcesJson) { parseSources(message.sourcesJson) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AssistantHeaderRow(modelName = message.modelDisplayName, isStreaming = false)

        if (message.isError) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                        RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Aura AI Request Failed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val safeErrorText = remember(message.content) {
                        message.content
                            .replace(Regex("https?://\\S+"), "")
                            .replace(Regex("gemini[-a-z0-9.]*", RegexOption.IGNORE_CASE), "Aura AI")
                            .replace(Regex("generativelanguage\\.googleapis\\.com\\S*", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("google", RegexOption.IGNORE_CASE), "Abhynex")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .ifBlank { "Aura AI request could not complete. Please tap Retry." }
                    }
                    Text(
                        text = safeErrorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedButton(
                        onClick = onRegenerate,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(16.dp)
                        )
                        Text("Retry")
                    }
                }
            }
            return@Column
        }

        if (!message.thinkingContent.isNullOrBlank()) {
            ThinkingAccordion(
                thinkingText = message.thinkingContent,
                durationMs = message.thinkingDurationMs,
                isActiveThinking = false
            )
        }

        if (!message.generatedImagePath.isNullOrBlank()) {
            val imageFile = remember(message.generatedImagePath) { File(message.generatedImagePath) }
            if (imageFile.exists()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = imageFile,
                        contentDescription = message.content,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(20.dp)
                            )
                    )
                    OutlinedButton(
                        onClick = { onEditGeneratedImage(message.generatedImagePath) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("edit_generated_image_${message.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(16.dp)
                        )
                        Text("Edit with Aura Imagine")
                    }
                }
            }
        }

        if (message.content.isNotBlank()) {
            MarkdownRenderer(markdown = message.content)
        }

        if (sources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SOURCES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEach { source ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    runCatching { uriHandler.openUri(source.uri) }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = source.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = "Open web source",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message.content))
                    copied = true
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("copy_message_${message.id}")
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_text),
                    tint = if (copied) auraColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onToggleSpeak,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("speak_message_${message.id}")
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (isSpeaking) {
                        stringResource(R.string.cd_stop_speaking)
                    } else {
                        stringResource(R.string.cd_speak_text)
                    },
                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onRegenerate,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("regenerate_message_${message.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.cd_regenerate),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("bookmark_message_${message.id}")
            ) {
                Icon(
                    imageVector = if (message.isBookmarked) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = stringResource(R.string.cd_bookmark),
                    tint = if (message.isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onShare,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("share_message_${message.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.cd_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (isLatestAssistantMessage && message.content.isNotBlank() && message.generatedImagePath.isNullOrBlank()) {
            val followUps = remember(message.id, message.content) {
                buildSmartFollowUps(message.content)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                followUps.forEach { (label, prompt) ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(18.dp)
                            )
                            .clickable { onFollowUpPrompt(prompt) }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun buildSmartFollowUps(content: String): List<Pair<String, String>> {
    return when {
        content.contains("```") -> listOf(
            "Find Bugs & Fix" to "Critically audit the code above for any bugs, edge cases, race conditions, or crashes, and provide the hardened drop-in fix.",
            "Optimize" to "Optimize the code above for cleaner architecture, performance, and lower memory overhead.",
            "Write Unit Tests" to "Write comprehensive unit tests covering normal flows, edge cases, and error paths for the code above."
        )
        content.contains("$$") || content.contains("\\boxed") || content.contains("\\frac") || content.contains("\\int") -> listOf(
            "Verify Steps" to "Double-check every derivation step above and verify the final answer with an independent sanity check.",
            "Simpler Way" to "Explain an alternative or quicker intuition to solve this problem.",
            "Harder Problem" to "Give me a harder Olympiad/JEE Advanced problem on this same concept and solve it step-by-step."
        )
        else -> listOf(
            "Go Deeper" to "Go deeper with concrete technical details, trade-offs, and practical examples.",
            "Summarize" to "Summarize the core answer into crisp, actionable bullet points.",
            "Practical Example" to "Give a clear real-world example applying what you explained."
        )
    }
}

@Composable
fun StreamingAssistantBlock(
    streaming: StreamingMessageState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stream_shimmer")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AssistantHeaderRow(modelName = streaming.modelDisplayName, isStreaming = true)

        if (streaming.isGeneratingImage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        RoundedCornerShape(20.dp)
                    )
                    .alpha(pulseAlpha),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Creating image with Aura Imagine…\n(may take 30–60s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        if (streaming.thinkingText.isNotBlank() || streaming.isThinkingPhase) {
            ThinkingAccordion(
                thinkingText = streaming.thinkingText.ifBlank { "Thinking step by step..." },
                durationMs = streaming.thinkingDurationMs,
                isActiveThinking = streaming.isThinkingPhase
            )
        }

        AnimatedVisibility(visible = streaming.contentText.isNotBlank()) {
            MarkdownRenderer(markdown = streaming.contentText)
        }

        if (streaming.contentText.isBlank() && !streaming.isThinkingPhase) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.alpha(pulseAlpha)
            ) {
                Text(
                    text = "Aura AI is thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AssistantHeaderRow(
    modelName: String,
    isStreaming: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GeminiSparkleIcon(size = 22.dp, animate = isStreaming)
        Text(
            text = "Aura AI",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Abhynex",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
