package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.ThemeMode

@Immutable
data class AuraSemanticColors(
    val codeBackground: Color,
    val codeHeader: Color,
    val codeText: Color,
    val userBubble: Color,
    val emerald: Color,
    val emeraldContainer: Color,
    val thinkingSurface: Color,
    val sparkleStart: Color,
    val sparkleMid: Color,
    val sparkleEnd: Color
)

val LocalAuraColors = staticCompositionLocalOf {
    AuraSemanticColors(
        codeBackground = CodeSurfaceAmoled,
        codeHeader = CodeHeaderAmoled,
        codeText = Color(0xFFE6EDF3),
        userBubble = UserBubbleAmoled,
        emerald = EmeraldAccent,
        emeraldContainer = EmeraldContainerDark,
        thinkingSurface = AmoledSurfaceLow,
        sparkleStart = GeminiBlue,
        sparkleMid = GeminiSparkleViolet,
        sparkleEnd = GeminiSparkleCyan
    )
}

private val AmoledDarkColorScheme = darkColorScheme(
    primary = GeminiBlue,
    onPrimary = GeminiOnBlue,
    primaryContainer = GeminiBlueContainer,
    onPrimaryContainer = GeminiOnBlueContainer,
    secondary = GeminiSparkleViolet,
    onSecondary = Color(0xFF1B0736),
    secondaryContainer = GeminiVioletContainer,
    onSecondaryContainer = GeminiOnVioletContainer,
    tertiary = GeminiSparkleCyan,
    onTertiary = Color(0xFF00201C),
    tertiaryContainer = EmeraldContainerDark,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = AmoledBlack,
    onBackground = AmoledOnSurface,
    surface = AmoledBlack,
    onSurface = AmoledOnSurface,
    surfaceVariant = AmoledSurfaceContainer,
    onSurfaceVariant = AmoledOnSurfaceVariant,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest,
    outline = AmoledOutline,
    outlineVariant = AmoledOutlineVariant,
    error = ErrorDark,
    errorContainer = ErrorContainerDark
)

private val CleanLightColorScheme = lightColorScheme(
    primary = GeminiBlueDeep,
    onPrimary = Color.White,
    primaryContainer = GeminiBlueContainerLight,
    onPrimaryContainer = GeminiOnBlueContainerLight,
    secondary = GeminiVioletDeep,
    onSecondary = Color.White,
    secondaryContainer = GeminiVioletContainerLight,
    onSecondaryContainer = GeminiOnVioletContainerLight,
    tertiary = EmeraldDeep,
    onTertiary = Color.White,
    tertiaryContainer = EmeraldContainerLight,
    onTertiaryContainer = Color(0xFF064E3B),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFD0D7E2),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorLight,
    errorContainer = ErrorContainerLight
)

private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AuraTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) AmoledDarkColorScheme else CleanLightColorScheme
    val semanticColors = if (isDark) {
        AuraSemanticColors(
            codeBackground = CodeSurfaceAmoled,
            codeHeader = CodeHeaderAmoled,
            codeText = Color(0xFFE6EDF3),
            userBubble = UserBubbleAmoled,
            emerald = EmeraldAccent,
            emeraldContainer = EmeraldContainerDark,
            thinkingSurface = AmoledSurfaceLow,
            sparkleStart = GeminiBlue,
            sparkleMid = GeminiSparkleViolet,
            sparkleEnd = GeminiSparkleCyan
        )
    } else {
        AuraSemanticColors(
            codeBackground = CodeSurfaceLight,
            codeHeader = CodeHeaderLight,
            codeText = Color(0xFFF3F4F6),
            userBubble = UserBubbleLight,
            emerald = EmeraldDeep,
            emeraldContainer = EmeraldContainerLight,
            thinkingSurface = LightSurfaceLow,
            sparkleStart = GeminiBlueDeep,
            sparkleMid = GeminiVioletDeep,
            sparkleEnd = EmeraldDeep
        )
    }

    CompositionLocalProvider(LocalAuraColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AuraTypography,
            shapes = AuraShapes,
            content = content
        )
    }
}
