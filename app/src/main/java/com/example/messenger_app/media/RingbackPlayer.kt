package com.example.messenger_app.media

import android.content.Context
import android.media.MediaPlayer
import com.example.messenger_app.R

object RingbackPlayer {
    private var mp: MediaPlayer? = null

    fun start(context: Context) {
        if (mp != null) return
        mp = MediaPlayer.create(context.applicationContext, R.raw.ringback).apply {
            isLooping = true
            start()
        }
    }

    fun stop() {
        mp?.run { stop(); release() }
        mp = null
    }
}
