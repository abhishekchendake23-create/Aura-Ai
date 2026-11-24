package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppLanguage
import com.example.data.model.PromptStarter
import com.example.ui.theme.LocalAuraColors

@Composable
fun WelcomeEmptyState(
    selectedLanguage: AppLanguage,
    hasEffectiveApiKey: Boolean = true,
    appBuilderEnabled: Boolean = false,
    compareArenaEnabled: Boolean = false,
    deepResearchEnabled: Boolean = false,
    isImageModeActive: Boolean = false,
    onSelectLanguage: (AppLanguage) -> Unit = {},
    onOpenApiKeySettings: () -> Unit = {},
    onStarterSelected: (PromptStarter) -> Unit = {},
    onOpenPersonaStudio: () -> Unit = {},
    onOpenLiveVoice: () -> Unit = {},
    onOpenToolsSheet: () -> Unit = {},
    onActivateAppBuilder: () -> Unit = {},
    onActivateManusAgent: () -> Unit = {},
    onOpenWorkbench: () -> Unit = {},
    onActivateCompareArena: () -> Unit = {},
    onActivateDeepResearch: () -> Unit = {},
    onActivateSlides: () -> Unit = {},
    onActivateImages: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Vertically centered crisp greeting with clean negative space
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = "Hi, how can I help\nyou today?",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 27.sp,
                        lineHeight = 35.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = (-0.3).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                if (!hasEffectiveApiKey) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable(onClick = onOpenApiKeySettings)
                            .testTag("welcome_add_api_key_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Add API key to start",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // Clean 2-row action pill grid directly above the composer
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalActionPill(
                    icon = Icons.Rounded.Tune,
                    label = "Compare",
                    selected = compareArenaEnabled,
                    accent = auraColors.sparkleStart,
                    onClick = onActivateCompareArena,
                    modifier = Modifier.testTag("welcome_compare_pill")
                )
                MinimalActionPill(
                    icon = Icons.Rounded.TravelExplore,
                    label = "Deep Research",
                    selected = deepResearchEnabled,
                    accent = auraColors.emerald,
                    onClick = onActivateDeepResearch,
                    modifier = Modifier.testTag("welcome_deep_research_pill")
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MinimalActionPill(
                    icon = Icons.Rounded.Build,
                    label = "App Builder",
                    selected = appBuilderEnabled,
                    accent = auraColors.amber,
                    onClick = onActivateAppBuilder,
                    modifier = Modifier.testTag("welcome_build_app_card")
                )
                MinimalActionPill(
                    icon = Icons.Rounded.Description,
                    label = "Slides",
                    selected = false,
                    accent = MaterialTheme.colorScheme.onSurface,
                    onClick = onActivateSlides,
                    modifier = Modifier.testTag("welcome_slides_pill")
                )
                MinimalActionPill(
                    icon = Icons.Rounded.Image,
                    label = "Images",
                    selected = isImageModeActive,
                    accent = auraColors.sparkleMid,
                    onClick = onActivateImages,
                    modifier = Modifier.testTag("welcome_images_pill")
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
    }
}

@Composable
private fun MinimalActionPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(22.dp))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

fun personaIconFor(iconKey: String) = when (iconKey) {
    "code" -> Icons.Rounded.Code
    "bug" -> Icons.Rounded.BugReport
    "math" -> Icons.Rounded.Functions
    "search" -> Icons.Rounded.TravelExplore
    "analytics" -> Icons.Rounded.Psychology
    "edit" -> Icons.Rounded.Edit
    "school" -> Icons.Rounded.School
    else -> Icons.Rounded.AutoAwesome
}
