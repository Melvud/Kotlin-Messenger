import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'dart:async';
import 'dart:io';
import 'package:flutter/services.dart';
import 'user_list_screen.dart';
import 'auth_screen.dart';
import 'call_screen.dart';
import 'update_util.dart';
import 'app_logger.dart';

final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();
const MethodChannel callActionsChannel = MethodChannel('com.example.messenger_app/call_actions');

Future<void> requestNotificationPermission() async {
  if (Platform.isAndroid) {
    final settings = await FirebaseMessaging.instance.getNotificationSettings();
    if (settings.authorizationStatus != AuthorizationStatus.authorized) {
      await FirebaseMessaging.instance.requestPermission(
        alert: true,
        badge: true,
        sound: true,
      );
    }
  }
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});
  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  StreamSubscription<String>? _fcmTokenSub;

  @override
  void initState() {
    super.initState();

    requestNotificationPermission();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      logInfo("main: checkForUpdateAndInstall start");
      await checkForUpdateAndInstall(navigatorKey.currentContext!);
      logInfo("main: checkForUpdateAndInstall end");
    });

    FirebaseAuth.instance.authStateChanges().listen((user) async {
      if (user != null) {
        logInfo("authStateChanges: вошёл пользователь ${user.uid}");
        await addFcmTokenToUser();
        _listenFcmTokenRefresh();
      } else {
        logInfo("authStateChanges: пользователь вышел");
        _fcmTokenSub?.cancel();
        await removeFcmTokenFromLastUser();
      }
    });

    // Слушаем события из Android
    callActionsChannel.setMethodCallHandler((call) async {
      if (call.method == "onCallAction") {
        final action = call.arguments["action"];
        final callId = call.arguments["callId"];
        logInfo("Flutter: onCallAction received: action=$action, callId=$callId");

        if (action == "accept" && callId != null) {
          _openCallScreen(callId);
        } else if (action == "decline") {
          _handleDecline();
        }
      }
    });

    _checkInitialIntent();
  }

  void _listenFcmTokenRefresh() {
    _fcmTokenSub?.cancel();
    _fcmTokenSub = FirebaseMessaging.instance.onTokenRefresh.listen((token) async {
      logInfo("FCM onTokenRefresh: $token");
      final user = FirebaseAuth.instance.currentUser;
      if (user != null) {
        await FirebaseFirestore.instance.collection('users').doc(user.uid).set({
          'fcmTokens': FieldValue.arrayUnion([token])
        }, SetOptions(merge: true));
        logInfo("Добавлен новый FCM токен в Firestore (onTokenRefresh): $token для user: ${user.uid}");
      } else {
        logError("onTokenRefresh: Пользователь не найден!");
      }
    });
  }

  Future<void> _checkInitialIntent() async {
    try {
      final result = await callActionsChannel.invokeMethod<Map<dynamic, dynamic>?>("getInitialCallAction");
      logDebug("Flutter: getInitialCallAction result: $result");
      if (result != null) {
        final action = result["action"];
        final callId = result["callId"];
        if (action == "accept" && callId != null) {
          _openCallScreen(callId);
        } else if (action == "decline") {
          _handleDecline();
        }
      }
    } catch (e, st) {
      logError("Flutter: Exception in _checkInitialIntent: $e\n$st");
    }
  }

  Future<void> _openCallScreen(String callId) async {
    try {
      logInfo("_openCallScreen: callId=$callId");
      final callDoc = await FirebaseFirestore.instance.collection('calls').doc(callId).get();
      final data = callDoc.data();
      if (data != null) {
        final peerId = data['from'] ?? '';
        final peerUsername = data['fromUsername'] ?? '';
        final myId = data['to'] ?? '';
        final myUsername = '';
        final callType = data['callType'] ?? 'audio';
        navigatorKey.currentState?.pushAndRemoveUntil(
          MaterialPageRoute(
            builder: (_) => CallScreen(
              isCaller: false,
              peerId: peerId,
              peerUsername: peerUsername,
              myId: myId,
              myUsername: myUsername,
              docId: callId,
              callType: callType,
            ),
          ),
          (route) => false,
        );
        logInfo("_openCallScreen: success push CallScreen");
      } else {
        logError("_openCallScreen: call document not found for $callId");
      }
    } catch (e, st) {
      logError("Flutter: Exception in _openCallScreen: $e\n$st");
    }
  }

  void _handleDecline() {
    logInfo("_handleDecline: call declined");
    navigatorKey.currentState?.pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const UserListScreen()),
      (route) => false,
    );
    if (navigatorKey.currentContext != null) {
      ScaffoldMessenger.of(navigatorKey.currentContext!)
          .showSnackBar(const SnackBar(content: Text("Звонок отклонён")));
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: navigatorKey,
      title: 'Messenger',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0088cc)),
        scaffoldBackgroundColor: const Color(0xFFF9F9F9),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF0088cc),
          foregroundColor: Colors.white,
        ),
      ),
      home: StreamBuilder<User?>(
        stream: FirebaseAuth.instance.authStateChanges(),
        builder: (context, snap) {
          if (snap.connectionState == ConnectionState.waiting) {
            return const Scaffold(body: Center(child: CircularProgressIndicator()));
          }
          if (snap.hasData && snap.data != null) {
            return const UserListScreen();
          }
          return const AuthScreen();
        },
      ),
    );
  }
}

// === FCM Tokен-менеджмент ===

// Добавляем токен при входе/старте, удаляя его у всех других пользователей
Future<void> addFcmTokenToUser() async {
  final user = FirebaseAuth.instance.currentUser;
  if (user != null) {
    try {
      final token = await FirebaseMessaging.instance.getToken();
      logInfo("addFcmTokenToUser: получен FCM токен: $token");
      if (token == null) {
        logError("addFcmTokenToUser: не удалось получить FCM токен");
        return;
      }

      final usersRef = FirebaseFirestore.instance.collection('users');

      // Найти всех пользователей, где этот токен уже есть (кроме текущего)
      final query = await usersRef
          .where('fcmTokens', arrayContains: token)
          .get();

      WriteBatch batch = FirebaseFirestore.instance.batch();

      for (final doc in query.docs) {
        if (doc.id != user.uid) {
          logDebug("Удаляю FCM токен из другого пользователя: ${doc.id}");
          batch.update(doc.reference, {
            'fcmTokens': FieldValue.arrayRemove([token])
          });
        }
      }

      // Добавить токен к текущему пользователю
      batch.set(
        usersRef.doc(user.uid),
        {'fcmTokens': FieldValue.arrayUnion([token])},
        SetOptions(merge: true),
      );

      await batch.commit();
      logInfo("addFcmTokenToUser: Batch commit завершён");
    } catch (e, st) {
      logError('Ошибка при добавлении FCM токена: $e\n$st');
    }
  }
}

// При логауте удаляем токен у пользователя, который был залогинен до этого
Future<void> removeFcmTokenFromLastUser() async {
  final user = FirebaseAuth.instance.currentUser;
  if (user != null) {
    try {
      final token = await FirebaseMessaging.instance.getToken();
      logInfo("removeFcmTokenFromLastUser: получен FCM токен: $token");
      if (token != null) {
        await FirebaseFirestore.instance.collection('users').doc(user.uid).update({
          'fcmTokens': FieldValue.arrayRemove([token])
        });
        logInfo("removeFcmTokenFromLastUser: токен удалён из Firestore для ${user.uid}");
      }
    } catch (e, st) {
      logError('Ошибка удаления FCM токена: $e\n$st');
    }
  }
}