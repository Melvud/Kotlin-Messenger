package com.example.messenger_app.ui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.messenger_app.ui.auth.AuthScreen
import com.example.messenger_app.ui.theme.AppTheme

/**
 * UI PREVIEW ДЛЯ ANDROID STUDIO
 * Позволяет просматривать экраны без запуска приложения
 */

// ==================== AUTH SCREEN ====================

@Preview(name = "Auth Screen Light", showBackground = true)
@Composable
fun PreviewAuthScreenLight() {
    AppTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AuthScreen(onAuthed = {})
        }
    }
}

@Preview(name = "Auth Screen Dark", showBackground = true)
@Composable
fun PreviewAuthScreenDark() {
    AppTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AuthScreen(onAuthed = {})
        }
    }
}

// ==================== CALL SCREEN ====================

@Preview(name = "Audio Call Light", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewAudioCallLight() {
    AppTheme(darkTheme = false) {
        // Примечание: CallScreen требует Context и WebRTC инициализацию
        // Полный preview требует моков, но можно показать базовую структуру
        Surface(modifier = Modifier.fillMaxSize()) {
            // CallScreen preview с моками
        }
    }
}

@Preview(name = "Video Call Dark", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PreviewVideoCallDark() {
    AppTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // CallScreen preview с моками
        }
    }
}

// ==================== CALL UI COMPONENTS ====================

@Preview(name = "Connection Indicator Connecting", showBackground = true)
@Composable
fun PreviewConnectionIndicatorConnecting() {
    AppTheme {
        // ConnectionIndicator(state = ConnectionState.CONNECTING)
    }
}

@Preview(name = "Connection Indicator Reconnecting", showBackground = true)
@Composable
fun PreviewConnectionIndicatorReconnecting() {
    AppTheme {
        // ConnectionIndicator(state = ConnectionState.RECONNECTING)
    }
}

@Preview(name = "Video Upgrade Dialog", showBackground = true)
@Composable
fun PreviewVideoUpgradeDialog() {
    AppTheme {
        // VideoUpgradeDialog(
        //     fromUsername = "Джон",
        //     onAccept = {},
        //     onDecline = {}
        // )
    }
}

// ==================== QUALITY INDICATORS ====================

@Preview(name = "Quality Indicator Good", showBackground = true)
@Composable
fun PreviewQualityGood() {
    AppTheme {
        // QualityIndicator(quality = Quality.Good)
    }
}

@Preview(name = "Quality Indicator Poor", showBackground = true)
@Composable
fun PreviewQualityPoor() {
    AppTheme {
        // QualityIndicator(quality = Quality.Poor)
    }
}