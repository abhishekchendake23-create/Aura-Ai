package com.example.ui.components

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.AiModelTier
import com.example.data.model.ModelCategory
import com.example.ui.theme.LocalAuraColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    selectedModel: AiModelTier,
    onSelectModel: (AiModelTier) -> Unit,
    onDismiss: () -> Unit
) {
    val auraColors = LocalAuraColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GeminiSparkleIcon(size = 24.dp)
                Column {
                    Text(
                        text = "Free AI Models · Streaming",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Gemini Free Tier, DeepSeek Free & Auto-Failover",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = auraColors.emeraldContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "All models stream live with silent backend failover: if any engine hits a rate limit or fails, Aura automatically switches in the background without interrupting your chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = auraColors.emerald,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }

            ModelCategory.entries.forEach { category ->
                Text(
                    text = category.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                AiModelTier.entries.filter { it.category == category }.forEach { tier ->
                    val isSelected = tier == selectedModel
                    val icon = when (tier) {
                        AiModelTier.AUTO_ROUTER -> Icons.Rounded.AutoFixHigh
                        AiModelTier.GEMINI_4_ARGON,
                        AiModelTier.DEEPSEEK_R1_FREE -> Icons.Rounded.Psychology
                        AiModelTier.GEMINI_3_8_FLASH,
                        AiModelTier.GEMINI_3_7_FLASH,
                        AiModelTier.GEMINI_3_6_FLASH,
                        AiModelTier.GEMINI_3_5_FLASH,
                        AiModelTier.GEMINI_2_5_FLASH,
                        AiModelTier.GEMINI_2_0_FLASH,
                        AiModelTier.DEEPSEEK_V3_FREE,
                        AiModelTier.DEEPSEEK_CHIMERA_FREE -> Icons.Rounded.AutoAwesome
                        AiModelTier.GEMINI_3_5_FLASH_LITE,
                        AiModelTier.GEMINI_2_5_FLASH_LITE,
                        AiModelTier.GEMMA_3_27B,
                        AiModelTier.GEMMA_3_12B,
                        AiModelTier.GEMINI_3_1_FLASH_LITE_IMAGE -> Icons.Rounded.Bolt
                        AiModelTier.NANO_BANANA_2_1 -> Icons.Rounded.Palette
                        AiModelTier.GEMINI_3_PRO_IMAGE -> Icons.Rounded.Brush
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable {
                                onSelectModel(tier)
                                onDismiss()
                            }
                            .padding(14.dp)
                            .testTag("model_option_${tier.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = tier.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                tier.isImageModel -> MaterialTheme.colorScheme.secondaryContainer
                                                tier.isDeepSeekTier -> auraColors.emeraldContainer
                                                else -> MaterialTheme.colorScheme.primaryContainer
                                            }
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${tier.badge} · ${tier.contextLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            tier.isImageModel -> MaterialTheme.colorScheme.onSecondaryContainer
                                            tier.isDeepSeekTier -> auraColors.emerald
                                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tier.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
