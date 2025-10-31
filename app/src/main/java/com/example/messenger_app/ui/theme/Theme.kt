package com.example.messenger_app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ЦВЕТОВАЯ СХЕМА TELEGRAM
 */

// Цвета Telegram
private val TelegramBlue = Color(0xFF2AABEE)
private val TelegramLightBlue = Color(0xFF54B9F5)
private val TelegramDarkBlue = Color(0xFF0088CC)
private val TelegramGreen = Color(0xFFDCFFD6)
private val TelegramLightGreen = Color(0xFFE8FFE0)
private val TelegramGray = Color(0xFFF0F0F0)
private val TelegramDarkGray = Color(0xFF181818)
private val TelegramLightGray = Color(0xFFFAFAFA)

// Светлая тема (Telegram Style)
private val LightColorScheme = lightColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = TelegramLightBlue,
    onPrimaryContainer = Color.White,

    secondary = TelegramGreen,
    onSecondary = Color.Black,
    secondaryContainer = TelegramLightGreen,
    onSecondaryContainer = Color.Black,

    tertiary = TelegramLightBlue,
    onTertiary = Color.White,

    background = TelegramLightGray,
    onBackground = Color.Black,

    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = TelegramGray,
    onSurfaceVariant = Color(0xFF5A5A5A),

    error = Color(0xFFE53935),
    onError = Color.White,

    outline = Color(0xFFDDDDDD),
    outlineVariant = Color(0xFFEEEEEE)
)

// Темная тема (Telegram Style)
private val DarkColorScheme = darkColorScheme(
    primary = TelegramBlue,
    onPrimary = Color.White,
    primaryContainer = TelegramDarkBlue,
    onPrimaryContainer = Color.White,

    secondary = Color(0xFF5FB85A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A9445),
    onSecondaryContainer = Color.White,

    tertiary = TelegramLightBlue,
    onTertiary = Color.White,

    background = TelegramDarkGray,
    onBackground = Color.White,

    surface = Color(0xFF212121),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFAAAAAA),

    error = Color(0xFFEF5350),
    onError = Color.White,

    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A)
)

/**
 * Тема приложения в стиле Telegram
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Специальные цвета для сообщений
 */
object MessageColors {
    // Светлая тема
    val lightIncoming = Color.White
    val lightOutgoing = Color(0xFFDCFFD6)
    val lightIncomingText = Color.Black
    val lightOutgoingText = Color.Black
    val lightTimestamp = Color(0xFF999999)
    val lightReplyBackground = Color(0x20000000)

    // Темная тема
    val darkIncoming = Color(0xFF2B2B2B)
    val darkOutgoing = Color(0xFF4A6741)
    val darkIncomingText = Color.White
    val darkOutgoingText = Color.White
    val darkTimestamp = Color(0xFFAAAAAA)
    val darkReplyBackground = Color(0x30FFFFFF)

    @Composable
    fun incomingBubble(): Color {
        return if (isSystemInDarkTheme()) darkIncoming else lightIncoming
    }

    @Composable
    fun outgoingBubble(): Color {
        return if (isSystemInDarkTheme()) darkOutgoing else lightOutgoing
    }

    @Composable
    fun messageText(): Color {
        return if (isSystemInDarkTheme()) darkIncomingText else lightIncomingText
    }

    @Composable
    fun timestamp(): Color {
        return if (isSystemInDarkTheme()) darkTimestamp else lightTimestamp
    }

    @Composable
    fun replyBackground(): Color {
        return if (isSystemInDarkTheme()) darkReplyBackground else lightReplyBackground
    }
}

/**
 * Цвета для статусов звонков
 */
object CallStatusColors {
    val missed = Color(0xFFE53935)
    val incoming = Color(0xFF4CAF50)
    val outgoing = Color(0xFF2196F3)
}

/**
 * Дополнительные цвета Telegram
 */
object TelegramColors {
    val online = Color(0xFF4DCD5E)
    val typing = TelegramBlue
    val verified = TelegramBlue
    val pinned = Color(0xFFFFD54F)
    val mention = TelegramBlue
    val unread = TelegramBlue
}
