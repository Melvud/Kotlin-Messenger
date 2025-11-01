import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
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
 * ✅ ИСПРАВЛЕНО: Проверяем что fromUserId != toUserId
 */
export const sendCallNotification = onCall(async (request) => {
  const { toUserId, fromUserId, fromUsername, callId, callType } = request.data || {};

  console.log("[sendCallNotification] ====================================");
  console.log("[sendCallNotification] Incoming request:", {
    toUserId,
    fromUserId,
    fromUsername,
    callId,
    callType
  });
  console.log("[sendCallNotification] ====================================");

  if (!toUserId || !callId || !fromUsername || !callType) {
    throw new HttpsError("invalid-argument", "toUserId, fromUsername, callId, callType are required");
  }

  // ✅✅✅ КРИТИЧЕСКАЯ ПРОВЕРКА: не звоним сами себе
  if (fromUserId && toUserId === fromUserId) {
    console.warn("[sendCallNotification] ❌ REJECTED: Attempted to call self!");
    console.warn("[sendCallNotification] fromUserId:", fromUserId);
    console.warn("[sendCallNotification] toUserId:", toUserId);
    throw new HttpsError("invalid-argument", "Cannot call yourself");
  }

  console.log("[sendCallNotification] ✅ Validation passed: fromUserId != toUserId");

  const db = getFirestore();

  // ✅ Получаем устройства ТОЛЬКО получателя
  console.log("[sendCallNotification] Fetching devices for toUserId:", toUserId);
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

  console.log("[sendCallNotification] Found tokens:", tokens.length);

  if (tokens.length === 0) {
    console.error("[sendCallNotification] ❌ No FCM tokens for user:", toUserId);
    throw new HttpsError("not-found", "FCM tokens not found for recipient");
  }

  // ✅ Отправляем push ТОЛЬКО получателю
  const multicast = {
    tokens,
    data: {
      type: "call",
      callId: String(callId),
      fromUserId: String(fromUserId || ""),
      toUserId: String(toUserId), // ✅ Добавляем для двойной проверки
      fromUsername: String(fromUsername),
      callType: String(callType),
      isVideo: String(callType === "video")
    },
    android: {
      priority: "high" as const,
      ttl: 30000 // 30 секунд
    },
    apns: {
      headers: {
        "apns-priority": "10",
        "apns-expiration": String(Math.floor(Date.now() / 1000) + 30)
      }
    }
  };

  console.log("[sendCallNotification] Sending to tokens:", tokens);
  const res = await getMessaging().sendEachForMulticast(multicast);

  console.log("[sendCallNotification] ====================================");
  console.log("[sendCallNotification] ✅ Result: success=" + res.successCount + ", failure=" + res.failureCount);
  console.log("[sendCallNotification] ====================================");

  // Очищаем невалидные токены
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

  // Сохраняем токены в документ звонка
  try {
    await db.collection("calls").doc(callId).set({
      calleeFcmTokens: tokens,
      notificationSentAt: new Date()
    }, { merge: true });
  } catch (e) {
    console.warn("[sendCallNotification] failed to write calleeFcmTokens:", e);
  }

  return { success: true, sent: res.successCount, failed: res.failureCount };
});

/**
 * ✅ ОТКЛЮЧЕНИЕ ЗВОНКА НА ДРУГИХ УСТРОЙСТВАХ
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
 * ✅ ИСПРАВЛЕНО: ОТПРАВКА УВЕДОМЛЕНИЯ О НОВОМ СООБЩЕНИИ
 */
export const sendMessageNotification = onDocumentCreated(
  "chats/{chatId}/messages/{messageId}",
  async (event) => {
    const message = event.data?.data();
    if (!message) return;

    const { chatId, messageId } = event.params;
    const senderId = message.senderId;
    const senderName = message.senderName || "Пользователь";
    const messageType = message.type || "TEXT";
    const messageStatus = message.status || "SENT";

    console.log(`[sendMessageNotification] New message ${messageId} from ${senderId} in chat ${chatId}`);

    if (messageStatus === "READ") {
      console.log("[sendMessageNotification] Message already READ, skipping");
      return;
    }

    const contentMap: Record<string, string> = {
      TEXT: message.content || "Сообщение",
      IMAGE: "📷 Фото",
      VIDEO: "🎬 Видео",
      FILE: "📎 Файл",
      VOICE: "🎤 Голосовое",
      STICKER: "😊 Стикер",
    };

    const content = contentMap[String(messageType)] || "Сообщение";
    const notificationBody = messageType === "TEXT"
      ? String(message.content || "").substring(0, 100)
      : content;

    const db = getFirestore();

    const chatDoc = await db.collection("chats").doc(chatId).get();
    if (!chatDoc.exists) {
      console.error("[sendMessageNotification] Chat not found:", chatId);
      return;
    }

    const chatData = chatDoc.data();
    const participants = (chatData?.participants || []) as string[];
    const recipients = participants.filter(p => p !== senderId);

    if (recipients.length === 0) {
      console.log("[sendMessageNotification] No recipients");
      return;
    }

    const allTokens: string[] = [];
    const tokenToDocRef: Record<string, DocumentReference> = {};
    let hasDeliveredToAnyone = false;

    for (const recipientId of recipients) {
      const userDoc = await db.collection("users").doc(recipientId).get();
      const activeChat = userDoc.data()?.activeChat;

      if (activeChat === chatId) {
        console.log(`[sendMessageNotification] User ${recipientId} is in active chat, skipping notification`);
        hasDeliveredToAnyone = true;
        continue;
      }

      const devicesSnap = await db.collection("users").doc(recipientId).collection("devices").get();
      devicesSnap.docs.forEach((d) => {
        const t = (d.data() as DeviceDoc).token;
        if (typeof t === "string" && t.length > 0) {
          allTokens.push(t);
          tokenToDocRef[t] = d.ref;
          hasDeliveredToAnyone = true;
        }
      });
    }

    console.log(`[sendMessageNotification] Sending to ${allTokens.length} devices`);

    if (hasDeliveredToAnyone && messageStatus === "SENT") {
      try {
        await db.collection("chats").doc(chatId).collection("messages").doc(messageId).update({
          status: "DELIVERED"
        });
        console.log(`[sendMessageNotification] Message ${messageId} -> DELIVERED`);
      } catch (e) {
        console.warn("[sendMessageNotification] Failed to update status:", e);
      }
    }

    if (allTokens.length === 0) {
      console.log("[sendMessageNotification] No tokens to send");
      return;
    }

    const multicast = {
      tokens: allTokens,
      notification: {
        title: senderName,
        body: notificationBody
      },
      data: {
        type: "message",
        chatId: String(chatId),
        messageId: String(messageId),
        senderId: String(senderId),
        senderName: String(senderName),
        messageType: String(messageType),
        content: String(message.content || content)
      },
      android: {
        priority: "high" as const,
        notification: {
          channelId: "messages",
          sound: "default",
          priority: "high" as const,
          color: "#2AABEE",
          icon: "ic_notification",
          tag: chatId,
          notificationCount: 1
        }
      },
      apns: {
        headers: { "apns-priority": "10" },
        payload: {
          aps: {
            sound: "default",
            badge: 1,
            alert: {
              title: senderName,
              body: notificationBody
            },
            "thread-id": chatId,
            category: "MESSAGE_CATEGORY"
          }
        }
      }
    };

    const res = await getMessaging().sendEachForMulticast(multicast);
    console.log(
      `[sendMessageNotification] sent: success=${res.successCount}, failure=${res.failureCount}`
    );

    const invalidDocRefs: DocumentReference[] = [];
    res.responses.forEach((r, i) => {
      if (!r.success) {
        const token = allTokens[i];
        const code = (r.error && (r.error as any).code) || "unknown";
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
      console.log("[sendMessageNotification] Removed invalid device docs:", invalidDocRefs.length);
    }
  }
);

/**
 * ✅ Автоматическое завершение звонков по таймауту
 */
export const autoEndCallOnTimeout = onDocumentUpdated(
  "calls/{callId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();

    if (!after) return;

    if (after.endedAt || after.status === "ended" || after.status === "timeout") {
      return;
    }

    const createdAt = after.createdAt;
    if (!createdAt || !createdAt.toDate) return;

    const now = Date.now();
    const created = createdAt.toDate().getTime();
    const elapsed = now - created;

    if (elapsed > 30000 && !after.startedAt && !before?.endedAt) {
      console.log(`[autoEndCallOnTimeout] Ending call ${event.params.callId} due to timeout`);

      const db = getFirestore();
      await db.collection("calls").doc(event.params.callId).update({
        endedAt: new Date(),
        status: "timeout"
      });

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

      const calleeUid = after.calleeUid;
      if (calleeUid && calleeUid !== callerUid) {
        const devicesSnap = await db.collection("users").doc(String(calleeUid)).collection("devices").get();
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
 * ✅ Уведомление о запросе перехода на видео
 */
export const notifyVideoUpgrade = onDocumentUpdated(
  "calls/{callId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();

    if (!after) return;

    const hadRequest = before?.videoUpgradeRequest;
    const hasRequest = after.videoUpgradeRequest;

    if (!hadRequest && hasRequest) {
      console.log(`[notifyVideoUpgrade] Video upgrade requested for call ${event.params.callId}`);

      const db = getFirestore();

      const callerUid = after.callerUid;
      const calleeUid = after.calleeUid;
      const targetUid = calleeUid;

      if (targetUid) {
        const devicesSnap = await db.collection("users").doc(String(targetUid)).collection("devices").get();
        const tokens: string[] = [];
        devicesSnap.docs.forEach((d) => {
          const t = (d.data() as DeviceDoc).token;
          if (typeof t === "string" && t.length > 0) tokens.push(t);
        });

        if (tokens.length > 0) {
          const callerDoc = await db.collection("users").doc(String(callerUid)).get();
          const fromUsername = callerDoc.data()?.username || callerDoc.data()?.name || "Собеседник";

          await getMessaging().sendEachForMulticast({
            tokens,
            data: {
              type: "video_upgrade_request",
              callId: String(event.params.callId),
              fromUsername: String(fromUsername)
            },
            android: { priority: "high" as const },
            apns: { headers: { "apns-priority": "10" } }
          });

          console.log(`[notifyVideoUpgrade] Sent video upgrade notification to ${tokens.length} devices`);
        }
      }
    }
  }
);