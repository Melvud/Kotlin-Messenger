import {onCall} from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
admin.initializeApp();

export const sendCallNotification = onCall(async (request) => {
  const {toUserId, fromUsername, callId, callType} = request.data;

  const userDoc = await admin
    .firestore()
    .collection("users")
    .doc(toUserId)
    .get();
  const fcmToken = userDoc.data()?.fcmToken;
  if (!fcmToken) {
    throw new Error("FCM token not found");
  }

  const message = {
    token: fcmToken,
    data: {
      type: "call",
      callId,
      fromUsername,
      callType,
    },
    notification: {
      title: `Входящий звонок от ${fromUsername}`,
      body: callType === "video" ? "Видеозвонок" : "Аудиозвонок",
    },
    android: {priority: "high" as const},
    apns: {
      headers: {"apns-priority": "10"},
      payload: {aps: {sound: "default"}},
    },
  };

  await admin.messaging().send(message);
  return {success: true};
});
