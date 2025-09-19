package com.example.messenger_app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.messenger_app.R

/**
 * Простой плеер для служебных звуков:
 * - playIncomingRinging() — можно использовать вместо звука канала, если захочется луп.
 * - playRingback() — «гудки» при наборе (вызывающий).
 * Включайте/выключайте из вашего CallScreen по состоянию звонка.
 */
object CallSoundPlayer {
    private var ringing: MediaPlayer? = null
    private var ringback: MediaPlayer? = null

    fun playIncomingRinging(context: Context, loop: Boolean = true) {
        stopIncomingRinging()
        ringing = MediaPlayer.create(context, R.raw.ringing)?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = loop
            start()
        }
    }

    fun stopIncomingRinging() {
        ringing?.runCatching { stop(); release() }
        ringing = null
    }

    fun playRingback(context: Context, loop: Boolean = true) {
        stopRingback()
        ringback = MediaPlayer.create(context, R.raw.ringback)?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = loop
            start()
        }
    }

    fun stopRingback() {
        ringback?.runCatching { stop(); release() }
        ringback = null
    }

    fun stopAll() {
        stopIncomingRinging()
        stopRingback()
    }
}
