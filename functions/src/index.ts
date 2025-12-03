import { onCall, onRequest, HttpsError } from "firebase-functions/v2/https";
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
 * Вебхук для Stream Chat (событие message.new)
 */
export const streamWebhook = onRequest(async (req, res) => {
  const body = req.body;
  console.log("[streamWebhook] Incoming webhook:", JSON.stringify(body));

  if (body.type !== "message.new") {
    console.log("[streamWebhook] Ignoring event type:", body.type);
    res.status(200).send("Ignored");
    return;
  }

  const message = body.message;
  const senderId = message.user.id;
  const channelId = body.channel_id;
  const channelType = body.channel_type;
  const members = body.members || [];

  console.log(`[streamWebhook] New message in ${channelType}:${channelId} from ${senderId}`);

  const db = getFirestore();
  const messaging = getMessaging();

  // Ищем получателей (все участники кроме отправителя)
  const recipientIds = members
    .map((m: any) => m.user_id)
    .filter((uid: string) => uid !== senderId);

  if (recipientIds.length === 0) {
    console.log("[streamWebhook] No recipients found");
    res.status(200).send("No recipients");
    return;
  }

  console.log("[streamWebhook] Recipients:", recipientIds);

  // Собираем токены всех получателей
  const tokens: string[] = [];
  for (const uid of recipientIds) {
    const devicesSnap = await db.collection("users").doc(uid).collection("devices").get();
    devicesSnap.docs.forEach((d) => {
      const t = (d.data() as DeviceDoc).token;
      if (t) tokens.push(t);
    });
  }

  if (tokens.length === 0) {
    console.log("[streamWebhook] No FCM tokens found for recipients");
    res.status(200).send("No tokens");
    return;
  }

  // Отправляем пуш
  const payload = {
    tokens,
    data: {
      type: "message.new",
      cid: `${channelType}:${channelId}`,
      channel_id: String(channelId),
      channel_type: String(channelType),
      sender_id: String(senderId),
      sender_name: String(message.user.name || "User"),
      text: String(message.text || "Photo/Video"),
      message_id: String(message.id)
    },
    notification: {
      title: String(message.user.name || "New Message"),
      body: String(message.text || "Sent a file")
    },
    android: { priority: "high" as const },
    apns: { headers: { "apns-priority": "10" } }
  };

  try {
    const response = await messaging.sendEachForMulticast(payload);
    console.log(`[streamWebhook] Sent ${response.successCount} notifications`);
    res.status(200).send(`Sent ${response.successCount}`);
  } catch (error) {
    console.error("[streamWebhook] Error sending push:", error);
    res.status(500).send("Error sending push");
  }
});

/**
 * ✅ ТЕСТОВОЕ УВЕДОМЛЕНИЕ (для отладки)
 */
export const sendTestPush = onCall(async (request) => {
  const { auth } = request;
  if (!auth) throw new HttpsError("unauthenticated", "Login required");

  const uid = auth.uid;
  console.log(`[sendTestPush] Request from user: ${uid}`);

  const db = getFirestore();

  const devicesSnap = await db.collection("users").doc(uid).collection("devices").get();
  const tokens: string[] = [];
  devicesSnap.docs.forEach((d) => {
    const t = (d.data() as DeviceDoc).token;
    if (t) tokens.push(t);
  });

  console.log(`[sendTestPush] Found ${tokens.length} tokens for user ${uid}`);

  if (tokens.length === 0) {
    console.warn(`[sendTestPush] ❌ No tokens found for user ${uid}`);
    return { success: false, message: "No devices found" };
  }

  const res = await getMessaging().sendEachForMulticast({
    tokens,
    notification: {
      title: "Test Push",
      body: "If you see this, FCM is working!"
    },
    data: {
      type: "test_push",
      click_action: "FLUTTER_NOTIFICATION_CLICK"
    }
  });

  console.log(`[sendTestPush] Sent: success=${res.successCount}, failure=${res.failureCount}`);

  return {
    success: true,
    sent: res.successCount,
    failed: res.failureCount,
    tokensCount: tokens.length
  };
});


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

/**
 * ✅ ГЕНЕРАЦИЯ ТОКЕНА STREAM CHAT
 */
export const createStreamToken = onCall(async (request) => {
  const { auth } = request;
  if (!auth) {
    throw new HttpsError("unauthenticated", "User must be logged in");
  }

  const uid = auth.uid;
  console.log(`[createStreamToken] Generating token for user: ${uid}`);

  try {
    const { StreamChat } = await import("stream-chat");

    // Инициализируем серверный клиент Stream
    // API KEY и SECRET KEY из запроса пользователя
    const apiKey = "ph9xg7m6nja5";
    const secret = "cuuq7t667u5t25n69x3gd6adhmmfnguebkq7zxabh3ge5vejeqkdbfb2sn4jxpsr";

    const serverClient = StreamChat.getInstance(apiKey, secret);

    // Создаем токен (без срока действия или с долгим сроком)
    const token = serverClient.createToken(uid);

    console.log(`[createStreamToken] Token generated successfully for ${uid}`);

    // Опционально: можно сразу создать/обновить пользователя в Stream

    await serverClient.upsertUser({
      id: uid,
      name: auth.token.name || auth.token.username || "User",
      image: auth.token.picture,
    });

    return { token };
  } catch (error) {
    console.error("[createStreamToken] Error generating token:", error);
    throw new HttpsError("internal", "Failed to generate token");
  }
});