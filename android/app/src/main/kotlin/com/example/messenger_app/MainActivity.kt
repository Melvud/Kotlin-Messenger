package com.example.messenger_app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.messenger_app/call_actions"
    private val AUDIO_MODE_CHANNEL = "com.example.messenger_app/audio_mode"
    private var methodChannel: MethodChannel? = null
    private var pendingCallAction: Map<String, String?>? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        pendingCallAction?.let {
            methodChannel?.invokeMethod("onCallAction", it)
            pendingCallAction = null
        }

        // === Audio Mode Channel ===
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AUDIO_MODE_CHANNEL)
            .setMethodCallHandler { call, result ->
                if (call.method == "setAudioModeInCall") {
                    val inCall = call.argument<Boolean>("inCall") ?: false
                    setAudioModeInCall(inCall)
                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
    }

    private fun setAudioModeInCall(inCall: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (inCall) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra("action")
        val callId = intent.getStringExtra("callId")
        val notificationId = intent.getIntExtra("notificationId", -1)

        Log.d("MainActivity", "handleIntent: action=$action, callId=$callId, notificationId=$notificationId")

        // Убираем уведомление после любого действия
        if (notificationId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
        }

        if (action == "accept" || action == "decline") {
            val args = mapOf("action" to action, "callId" to callId)
            if (methodChannel != null) {
                methodChannel?.invokeMethod("onCallAction", args)
            } else {
                pendingCallAction = args
            }
        }
    }
}