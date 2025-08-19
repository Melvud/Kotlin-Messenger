import 'package:cloud_functions/cloud_functions.dart';

Future<void> sendCallNotification({
  required String toUserId,
  required String fromUsername,
  required String callId,
  required String callType,
}) async {
  final callable = FirebaseFunctions.instance.httpsCallable('sendCallNotification');
  await callable.call({
    'toUserId': toUserId,
    'fromUsername': fromUsername,
    'callId': callId,
    'callType': callType, // 'audio' или 'video'
  });
}