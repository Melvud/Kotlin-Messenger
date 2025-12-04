package com.example.messenger_app.utils

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.IOException

class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun playFile(uri: Uri, onCompletion: () -> Unit) {
        stop()
        MediaPlayer().apply {
            try {
                setDataSource(context, uri)
                prepare()
                start()
                setOnCompletionListener { 
                    onCompletion()
                    stop() 
                }
            } catch (e: IOException) {
                Log.e("AudioPlayer", "prepare() failed", e)
            }
        }.also { player = it }
    }

    fun stop() {
        player?.release()
        player = null
    }
}
