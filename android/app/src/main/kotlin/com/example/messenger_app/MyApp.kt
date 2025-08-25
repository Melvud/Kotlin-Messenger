package com.example.messenger_app

import android.app.Application
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

class MyApp : Application() {

    companion object {
        var flutterEngine: FlutterEngine? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Создаём FlutterEngine и сразу прогружаем Dart
        flutterEngine = FlutterEngine(this).apply {
            dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault()
            )
        }

        // Кэшируем, чтобы FlutterActivity мог его переиспользовать
        FlutterEngineCache
            .getInstance()
            .put("engine_id", flutterEngine!!)
    }
}
