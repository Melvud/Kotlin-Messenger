import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
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
 */
export const sendCallNotification = onCall(async (request) => {
  const { toUserId, fromUsername, callId, callType } = request.data || {};
  console.log("[sendCallNotification] Params:", { toUserId, fromUsername, callId, callType });

  if (!toUserId || !callId || !fromUsername || !callType) {
    throw new HttpsError("invalid-argument", "toUserId, fromUsername, callId, callType are required");
  }

  const db = getFirestore();

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

/**
 * НОВОЕ: Автоматическое завершение звонков по таймауту
 * Триггер: когда звонок обновляется и прошло 30+ секунд с момента создания
 */
export const autoEndCallOnTimeout = onDocumentUpdated(
  "calls/{callId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();

    if (!after) return;

    // Если звонок уже завершен, ничего не делаем
    if (after.endedAt || after.status === "ended" || after.status === "timeout") {
      return;
    }

    // Проверяем, прошло ли 30 секунд с момента создания
    const createdAt = after.createdAt;
    if (!createdAt || !createdAt.toDate) return;

    const now = Date.now();
    const created = createdAt.toDate().getTime();
    const elapsed = now - created;

    // Если прошло больше 30 секунд и нет startedAt (звонок не был принят)
    if (elapsed > 30000 && !after.startedAt && !before?.endedAt) {
      console.log(`[autoEndCallOnTimeout] Ending call ${event.params.callId} due to timeout`);

      const db = getFirestore();
      await db.collection("calls").doc(event.params.callId).update({
        endedAt: new Date(),
        status: "timeout"
      });

      // Отправляем уведомление caller'у о таймауте
      const callerUid = after.callerUid;
      if (callerUid) {
        const devicesSnap = await db.collection("users").doc(String(callerUid)).collection("devices").get();
        const tokens: string[] = [];
        devicesSnap.docs.forEach((d) => {
          const t = (d.data() as DeviceDoc).token;
          if (typeof t === "string" && t.length > 0) tokens.push(t);
        });

        if (tokens.length > 0) {
          await getMessaging().sendEachForMulticast({
            tokens,
            data: {
              type: "call_timeout",
              callId: String(event.params.callId)
            },
            android: { priority: "high" as const }
          });
        }
      }
    }
  }
);

/**
 * НОВОЕ: Уведомление о запросе перехода на видео
 * Триггер: когда в документе звонка появляется videoUpgradeRequest
 */
export const notifyVideoUpgrade = onDocumentUpdated(
  "calls/{callId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();

    if (!after) return;

    // Проверяем, появился ли новый videoUpgradeRequest
    const hadRequest = before?.videoUpgradeRequest;
    const hasRequest = after.videoUpgradeRequest;

    if (!hadRequest && hasRequest) {
      console.log(`[notifyVideoUpgrade] Video upgrade requested for call ${event.params.callId}`);

      const db = getFirestore();

      // Определяем, кому отправлять (противоположная сторона от того, кто запросил)
      const callerUid = after.callerUid;
      const calleeUid = after.calleeUid;

      // Предполагаем, что запрос идет от caller к callee (можно улучшить логику)
      const targetUid = calleeUid;

      if (targetUid) {
        const devicesSnap = await db.collection("users").doc(String(targetUid)).collection("devices").get();
        const tokens: string[] = [];
        devicesSnap.docs.forEach((d) => {
          const t = (d.data() as DeviceDoc).token;
          if (typeof t === "string" && t.length > 0) tokens.push(t);
        });

        if (tokens.length > 0) {
          // Получаем имя запросившего
          const callerDoc = await db.collection("users").doc(String(callerUid)).get();
          const fromUsername = callerDoc.data()?.username || callerDoc.data()?.name || "Собеседник";

          await getMessaging().sendEachForMulticast({
            tokens,
            data: {
              type: "video_upgrade_request",
              callId: String(event.params.callId),
              fromUsername: String(fromUsername)
            },
            android: { priority: "high" as const }
          });

          console.log(`[notifyVideoUpgrade] Sent video upgrade notification to ${tokens.length} devices`);
        }
      }
    }
  }
);