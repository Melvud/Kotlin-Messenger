import 'package:flutter/services.dart';

class AndroidCallUtil {
  static const MethodChannel _channel = MethodChannel('com.example.messenger_app/call_actions');

  static Future<void> simulateIncomingCall(String callId) async {
    await _channel.invokeMethod('simulateIncomingCall', {
      'callId': callId,
    });
  }
}