import { onCall, HttpsError } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import type { DocumentReference } from "firebase-admin/firestore";

initializeApp();

type DeviceDoc = {
  token?: string;
  deviceName?: string;
  updatedAt?: unknown;
};

/**
 * ОТПРАВКА ВЫЗОВА
 * Ожидает: { toUserId, fromUsername, callId, callType }
 * Берёт FCM-токены из users/{toUserId}/devices/* и шлёт data-push на ВСЕ устройства.
 * Битые токены удаляет (удаляет соответствующие документы devices/{deviceId}).
 */
export const sendCallNotification = onCall(async (request) => {
  const { toUserId, fromUsername, callId, callType } = request.data || {};
  console.log("[sendCallNotification] Params:", { toUserId, fromUsername, callId, callType });

  if (!toUserId || !callId || !fromUsername || !callType) {
    throw new HttpsError("invalid-argument", "toUserId, fromUsername, callId, callType are required");
  }

  const db = getFirestore();

  // Собираем токены из саб-коллекции devices
  const devicesSnap = await db.collection("users").doc(toUserId).collection("devices").get();
  const tokens: string[] = [];
  const tokenToDocRef: Record<string, DocumentReference> = {};

  devicesSnap.docs.forEach((d) => {
    const data = d.data() as DeviceDoc;
    const t = data.token;
    if (typeof t === "string" && t.length > 0) {
      tokens.push(t);
      tokenToDocRef[t] = d.ref;
    }
  });

  console.log("[sendCallNotification] tokens count:", tokens.length);

  if (tokens.length === 0) {
    console.error("[sendCallNotification] No FCM tokens for user:", toUserId);
    throw new HttpsError("not-found", "FCM tokens not found");
  }

  const multicast = {
    tokens,
    data: {
      type: "call",
      callId: String(callId),
      fromUsername: String(fromUsername),
      callType: String(callType)
    },
    android: { priority: "high" as const },
    apns: { headers: { "apns-priority": "10" } }
  };

  const res = await getMessaging().sendEachForMulticast(multicast);
  console.log(
    `[sendCallNotification] sent: success=${res.successCount}, failure=${res.failureCount}`
  );

  // Чистим битые токены — копим ссылки, потом одним батчем удалим их документы
  const invalidDocRefs: DocumentReference[] = [];
  res.responses.forEach((r, i) => {
    if (!r.success) {
      const token = tokens[i];
      const code = (r.error && (r.error as any).code) || "unknown";
      const msg = r.error?.message;
      console.warn("[sendCallNotification] send error:", token, code, msg);
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        const ref = tokenToDocRef[token];
        if (ref) invalidDocRefs.push(ref);
      }
    }
  });

  if (invalidDocRefs.length > 0) {
    const batch = db.batch();
    invalidDocRefs.forEach((ref) => batch.delete(ref));
    await batch.commit();
    console.log("[sendCallNotification] Removed invalid device docs:", invalidDocRefs.length);
  }

  // (Необязательно) Сохраним снимок токенов в документе звонка — удобно для отладки
  try {
    await db.collection("calls").doc(callId).set({ calleeFcmTokens: tokens }, { merge: true });
  } catch (e) {
    console.warn("[sendCallNotification] failed to write calleeFcmTokens:", e);
  }

  return { success: true, sent: res.successCount, failed: res.failureCount };
});

/**
 * ОТКЛЮЧЕНИЕ ЗВОНКА НА ДРУГИХ УСТРОЙСТВАХ
 * Ожидает: { callId, acceptedToken }
 * Берёт calleeUid из calls/{callId}, вытягивает ВСЕ его токены из users/{calleeUid}/devices/*,
 * отфильтровывает токен принявшего устройства и шлёт остальным data "hangup".
 */
export const hangupOtherDevices = onCall(async (request) => {
  const { callId, acceptedToken } = request.data || {};
  console.log("[hangupOtherDevices] Params:", { callId, acceptedToken });

  if (!callId || !acceptedToken) {
    throw new HttpsError("invalid-argument", "callId and acceptedToken are required");
  }

  const db = getFirestore();
  const callSnap = await db.collection("calls").doc(callId).get();

  if (!callSnap.exists) {
    console.error("[hangupOtherDevices] Call not found:", callId);
    throw new HttpsError("not-found", "Call not found");
  }

  const { calleeUid } = callSnap.data() || {};
  if (!calleeUid) {
    console.error("[hangupOtherDevices] calleeUid missing in call:", callId);
    throw new HttpsError("failed-precondition", "calleeUid is missing in call doc");
  }

  // Все устройства адресата
  const devicesSnap = await db.collection("users").doc(String(calleeUid)).collection("devices").get();
  const tokens: string[] = [];
  const tokenToDocRef: Record<string, DocumentReference> = {};
  devicesSnap.docs.forEach((d) => {
    const t = (d.data() as DeviceDoc).token;
    if (typeof t === "string" && t.length > 0 && t !== acceptedToken) {
      tokens.push(t);
      tokenToDocRef[t] = d.ref;
    }
  });

  console.log("[hangupOtherDevices] tokens to hangup:", tokens.length);

  if (tokens.length === 0) {
    return { success: true, sent: 0, failed: 0 };
  }

  const multicast = {
    tokens,
    data: {
      type: "hangup",
      callId: String(callId),
      reason: "answered_on_another_device"
    },
    android: { priority: "high" as const },
    apns: { headers: { "apns-priority": "10" } }
  };

  const res = await getMessaging().sendEachForMulticast(multicast);
  console.log(
    `[hangupOtherDevices] sent: success=${res.successCount}, failure=${res.failureCount}`
  );

  // Чистка битых токенов — аналогично
  const invalidDocRefs: DocumentReference[] = [];
  res.responses.forEach((r, i) => {
    if (!r.success) {
      const token = tokens[i];
      const code = (r.error && (r.error as any).code) || "unknown";
      const msg = r.error?.message;
      console.warn("[hangupOtherDevices] send error:", token, code, msg);
      if (
        code === "messaging/registration-token-not-registered" ||
        code === "messaging/invalid-registration-token"
      ) {
        const ref = tokenToDocRef[token];
        if (ref) invalidDocRefs.push(ref);
      }
    }
  });

  if (invalidDocRefs.length > 0) {
    const batch = db.batch();
    invalidDocRefs.forEach((ref) => batch.delete(ref));
    await batch.commit();
    console.log("[hangupOtherDevices] Removed invalid device docs:", invalidDocRefs.length);
  }

  return { success: true, sent: res.successCount, failed: res.failureCount };
});
