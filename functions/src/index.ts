import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
admin.initializeApp();

/**
 * ОТПРАВКА ВЫЗОВА
 */
export const sendCallNotification = onCall(
  async (request) => {
    const {toUserId, fromUsername, callId, callType} = request.data;
    console.log(
      "[sendCallNotification] Params:",
      {toUserId, fromUsername, callId, callType}
    );

    const userDoc = await admin
      .firestore()
      .collection("users")
      .doc(toUserId)
      .get();

    console.log(
      "[sendCallNotification] userDoc.exists:",
      userDoc.exists
    );
    console.log(
      "[sendCallNotification] userDoc.data():",
      JSON.stringify(userDoc.data())
    );

    if (!userDoc.exists) {
      console.error(
        "[sendCallNotification] User not found:",
        toUserId
      );
      throw new HttpsError("not-found", "User not found");
    }

    const fcmTokens = userDoc.data()?.fcmTokens;
    console.log("[sendCallNotification] fcmTokens:", fcmTokens);

    if (!Array.isArray(fcmTokens) || fcmTokens.length === 0) {
      console.error(
        "[sendCallNotification] FCM tokens not found:",
        fcmTokens
      );
      throw new HttpsError("not-found", "FCM tokens not found");
    }

    const message = {
      data: {
        type: "call",
        callId,
        fromUsername,
        callType,
      },
      android: {priority: "high" as const},
      apns: {
        headers: {"apns-priority": "10"},
        payload: {aps: {sound: "default"}},
      },
    };

    const tokensToRemove: string[] = [];
    for (const token of fcmTokens) {
      try {
        await admin.messaging().send({...message, token});
        console.log(
          "[sendCallNotification] Push sent to token:",
          token
        );
      } catch (e: unknown) {
        const error = e as { code?: string; message?: string };
        console.error(
          "[sendCallNotification] Error sending to token:",
          token,
          error.code,
          error.message
        );
        if (
          error.code === "messaging/registration-token-not-registered" ||
          error.code === "messaging/invalid-registration-token"
        ) {
          tokensToRemove.push(token);
        }
      }
    }

    if (tokensToRemove.length > 0) {
      await admin
        .firestore()
        .collection("users")
        .doc(toUserId)
        .update({
          fcmTokens: admin.firestore.FieldValue.arrayRemove(...tokensToRemove),
        });
      console.log(
        "[sendCallNotification] Removed invalid tokens:",
        tokensToRemove
      );
    }

    return {success: true};
  }
);

/**
 * ОТКЛЮЧЕНИЕ ЗВОНКА НА ДРУГИХ УСТРОЙСТВАХ
 */
export const hangupOtherDevices = onCall(
  async (request) => {
    const {callId, acceptedToken} = request.data;
    console.log(
      "[hangupOtherDevices] Params:",
      {callId, acceptedToken}
    );

    const callDoc = await admin
      .firestore()
      .collection("calls")
      .doc(callId)
      .get();

    console.log(
      "[hangupOtherDevices] callDoc.exists:",
      callDoc.exists
    );
    console.log(
      "[hangupOtherDevices] callDoc.data():",
      JSON.stringify(callDoc.data())
    );

    if (!callDoc.exists) {
      console.error(
        "[hangupOtherDevices] Call not found:",
        callId
      );
      throw new HttpsError("not-found", "Call not found");
    }

    const {calleeFcmTokens = []} = callDoc.data() || {};
    console.log(
      "[hangupOtherDevices] calleeFcmTokens (raw):",
      calleeFcmTokens
    );

    const tokensToHangup = Array.isArray(calleeFcmTokens) ?
      calleeFcmTokens.filter(
        (t: string) => t !== acceptedToken
      ) :
      [];
    console.log(
      "[hangupOtherDevices] tokensToHangup:",
      tokensToHangup
    );

    if (tokensToHangup.length === 0) {
      console.log(
        "[hangupOtherDevices] No tokens to hang up."
      );
      return {success: true};
    }

    const message = {
      data: {
        type: "hangup",
        callId,
        reason: "answered_on_another_device",
      },
      android: {priority: "high" as const},
      apns: {
        headers: {"apns-priority": "10"},
        payload: {aps: {sound: "default"}},
      },
    };

    for (const token of tokensToHangup) {
      try {
        await admin.messaging().send({...message, token});
        console.log(
          "[hangupOtherDevices] Hangup sent to token:",
          token
        );
      } catch (e: unknown) {
        const error = e as { code?: string; message?: string };
        console.error(
          "[hangupOtherDevices] Error sending hangup to token:",
          token,
          error.code,
          error.message
        );
      }
    }

    return {success: true};
  }
);
