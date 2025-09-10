import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

import 'auth_screen.dart';
import 'user_list_screen.dart';
import 'call_screen.dart';
import 'update_util.dart';
import 'app_logger.dart';
import 'theme/app_theme.dart';

final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// Канал, через который Android (MainActivity/Service) пробрасывает действия по звонкам:
/// - method: 'onCallAction'
/// - args: { 'action': 'accept'|'decline', 'callId': String }
/// См. MainActivity.kt — configureFlutterEngine/handleIntent. :contentReference[oaicite:4]{index=4}
const MethodChannel callActionsChannel =
    MethodChannel('com.example.messenger_app/call_actions');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();

  // (Опция) Фоновая обработка FCM (не обязательна для звонков через нотификацию)
  FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

  runApp(const _RootApp());
}

Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  // Инициализация не обязательна, если уже в памяти, но безопасно добавить
  await Firebase.initializeApp();
  logInfo('BG FCM: ${message.data}');
}

class _RootApp extends StatefulWidget {
  const _RootApp();

  @override
  State<_RootApp> createState() => _RootAppState();
}

class _RootAppState extends State<_RootApp> {
  StreamSubscription<User?>? _authSub;
  StreamSubscription<RemoteMessage>? _onMessageOpenedSub;

  @override
  void initState() {
    super.initState();

    // Привязка обработчика MethodChannel от Android.
    callActionsChannel.setMethodCallHandler((call) async {
      if (call.method == 'onCallAction') {
        final Map<dynamic, dynamic> args = call.arguments;
        final String? action = args['action'] as String?;
        final String? callId = args['callId'] as String?;
        logInfo('onCallAction <- $args');

        if (action == 'accept' && callId != null) {
          await _openCallScreen(callId, acceptedFromPlatform: true);
        } else if (action == 'decline' && callId != null) {
          // Пометим отказ в документе звонка
          try {
            await FirebaseFirestore.instance.collection('calls').doc(callId).update({
              'calleeStatus': 'declined',
            });
          } catch (e, st) {
            logError('Decline update failed: $e\n$st');
          }
        }
      }
    });

    // Реакция на открытие пуша (если Android запустил напрямую Activity — тоже придёт сюда)
    _onMessageOpenedSub = FirebaseMessaging.onMessageOpenedApp.listen((msg) async {
      logInfo('onMessageOpenedApp: ${msg.data}');
      final data = msg.data;
      if (data['type'] == 'call' && data['callId'] != null) {
        await _openCallScreen(data['callId'] as String);
      }
    });

    // Если приложение запускалось по пушу (cold start)
    _maybeOpenInitialCallFromNotification();

    // Обновление/снятие FCM токена при логине/логауте (опционально, как у вас)
    _authSub = FirebaseAuth.instance.authStateChanges().listen((user) async {
      try {
        final token = await FirebaseMessaging.instance.getToken();
        if (user != null && token != null) {
          await FirebaseFirestore.instance.collection('users').doc(user.uid).set({
            'fcmTokens': FieldValue.arrayUnion([token]),
          }, SetOptions(merge: true));
        }
      } catch (e, st) {
        logError('FCM token sync failed: $e\n$st');
      }
    });
  }

  @override
  void dispose() {
    _authSub?.cancel();
    _onMessageOpenedSub?.cancel();
    super.dispose();
  }

  Future<void> _maybeOpenInitialCallFromNotification() async {
    try {
      final msg = await FirebaseMessaging.instance.getInitialMessage();
      if (msg == null) return;
      logInfo('getInitialMessage: ${msg.data}');
      final data = msg.data;
      if (data['type'] == 'call' && data['callId'] != null) {
        await _openCallScreen(data['callId'] as String);
      }
    } catch (e, st) {
      logError('Initial message handling failed: $e\n$st');
    }
  }

  /// Открытие экрана звонка по callId, считывая Firestore-документ calls/<callId>.
  /// Совместимо с Android-трамплином (CallTrampolineService → MainActivity → MethodChannel).
  Future<void> _openCallScreen(String callId, {bool acceptedFromPlatform = false}) async {
    try {
      final doc = await FirebaseFirestore.instance.collection('calls').doc(callId).get();
      if (!doc.exists) {
        logError('_openCallScreen: call not found $callId');
        return;
      }
      final data = doc.data()!;
      final String callerId = data['callerId'] as String? ?? '';
      final String calleeId = data['calleeId'] as String? ?? '';
      final String callerUsername = data['callerUsername'] as String? ?? 'Caller';
      final String calleeUsername = data['calleeUsername'] as String? ?? 'Callee';
      final String callType = data['callType'] as String? ?? 'audio';

      final my = FirebaseAuth.instance.currentUser;
      if (my == null) {
        // если нет сессии — предложим логин
        navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute(builder: (_) => const AuthScreen()),
          (_) => false,
        );
        return;
      }

      final bool amCaller = my.uid == callerId;
      final String peerId = amCaller ? calleeId : callerId;
      final String peerUsername = amCaller ? calleeUsername : callerUsername;
      final String myUsername = amCaller ? callerUsername : calleeUsername;

      // Если пришло "accept" с платформы — мы уже callee -> просто открываем звонок.
      navigatorKey.currentState?.pushAndRemoveUntil(
        MaterialPageRoute(
          builder: (_) => CallScreen(
            isCaller: amCaller,
            peerId: peerId,
            peerUsername: peerUsername,
            myId: my.uid,
            myUsername: myUsername,
            docId: callId,
            callType: callType,
          ),
        ),
        (route) => false,
      );

      logInfo('_openCallScreen -> CallScreen($callId)');
    } catch (e, st) {
      logError('_openCallScreen exception: $e\n$st');
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: navigatorKey,
      debugShowCheckedModeBanner: false,
      title: 'Calls Only',
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      themeMode: ThemeMode.system,
      home: StreamBuilder<User?>(
        stream: FirebaseAuth.instance.authStateChanges(),
        builder: (_, snap) {
          if (snap.connectionState == ConnectionState.waiting) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }
          if (snap.data == null) {
            return const AuthScreen();
          }
          return const UserListScreen();
        },
      ),
    );
  }
}
