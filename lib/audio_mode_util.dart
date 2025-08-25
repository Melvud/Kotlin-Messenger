import 'package:flutter/services.dart';

class AudioModeUtil {
  static const MethodChannel _channel = MethodChannel('com.example.messenger_app/audio_mode');

  static Future<void> setInCallMode(bool inCall) async {
    try {
      await _channel.invokeMethod('setAudioModeInCall', {'inCall': inCall});
    } catch (e) {
      // ignore errors for now
    }
  }
}