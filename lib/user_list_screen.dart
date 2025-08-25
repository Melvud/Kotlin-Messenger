import 'package:flutter/material.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'call_screen.dart';
import 'search_user_screen.dart';
import 'dart:async';
import 'package:uuid/uuid.dart';
import 'package:cloud_functions/cloud_functions.dart';
// Импортируйте ваш app_logger.dart, если его еще нет:
import 'app_logger.dart';

class UserListScreen extends StatefulWidget {
  const UserListScreen({super.key});
  @override
  State<UserListScreen> createState() => _UserListScreenState();
}

class _UserListScreenState extends State<UserListScreen> {
  late String myId;
  String? myUsername;
  StreamSubscription<DocumentSnapshot>? _callSub;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    final user = FirebaseAuth.instance.currentUser!;
    myId = user.uid;
    _loadMyUsername();
    logInfo("[UserListScreen] initState: myId=$myId");
  }

  Future<void> _loadMyUsername() async {
    try {
      final doc = await FirebaseFirestore.instance.collection('users').doc(myId).get();
      setState(() {
        myUsername = doc.data()?['username'] ?? doc.data()?['email'] ?? myId;
      });
      logInfo("[UserListScreen] Loaded myUsername: $myUsername");
    } catch (e, st) {
      setState(() {
        myUsername = myId;
      });
      logError("[UserListScreen] Exception in loading myUsername: $e\n$st");
    }
  }

  @override
  void dispose() {
    _callSub?.cancel();
    logInfo("[UserListScreen] dispose called");
    super.dispose();
  }

  Future<void> _callUser(String peerId, String peerUsername, String callType) async {
    setState(() => _loading = true);
    logInfo("[UserListScreen] Start _callUser: peerId=$peerId, peerUsername=$peerUsername, callType=$callType, myId=$myId, myUsername=$myUsername");
    try {
      if (peerId == myId) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Вы не можете звонить самому себе.')),
          );
        }
        logError("[UserListScreen] Attempted to call self, abort.");
        return;
      }

      final peerDoc = await FirebaseFirestore.instance.collection('users').doc(peerId).get();
      logDebug("[UserListScreen] peerDoc data: ${peerDoc.data()}");
      final peerTokens = peerDoc.data()?['fcmTokens'];
      logInfo("[UserListScreen] peerTokens: $peerTokens");

      if (peerTokens == null || !(peerTokens is List) || peerTokens.isEmpty) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Пользователь еще не может принимать звонки (нет FCM токена).')),
          );
        }
        logError("[UserListScreen] No FCM tokens for peer: $peerId");
        return;
      }

      final callId = const Uuid().v4();

      final calls = FirebaseFirestore.instance.collection('calls');
      await calls.doc(callId).set({
        'type': 'call',
        'from': myId,
        'fromUsername': myUsername,
        'to': peerId,
        'toUsername': peerUsername,
        'callType': callType,
        'calleeStatus': 'ringing',
        'callerStatus': 'calling',
        'timestamp': FieldValue.serverTimestamp(),
        'calleeFcmTokens': peerTokens,
      });
      logInfo("[UserListScreen] Created call doc with callId=$callId");

      final params = {
        'toUserId': peerId,
        'fromUsername': myUsername ?? '',
        'callId': callId,
        'callType': callType,
      };
      logDebug("[UserListScreen] Calling sendCallNotification with: $params");

      final result = await FirebaseFunctions.instance
          .httpsCallable('sendCallNotification')
          .call(params);

      logInfo("[UserListScreen] sendCallNotification result: $result");

      Navigator.push(context, MaterialPageRoute(
        builder: (_) => CallScreen(
          isCaller: true,
          peerId: peerId,
          peerUsername: peerUsername,
          myId: myId,
          myUsername: myUsername ?? '',
          docId: callId,
          callType: callType,
        ),
      ));
    } catch (e, stack) {
      logError("[UserListScreen] Exception in _callUser: $e\n$stack");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Ошибка вызова: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _deleteContact(String contactId) async {
    try {
      await FirebaseFirestore.instance
          .collection('users')
          .doc(myId)
          .collection('contacts')
          .doc(contactId)
          .delete();
      logInfo("[UserListScreen] Deleted contact: $contactId");
    } catch (e, st) {
      logError("[UserListScreen] Exception in _deleteContact: $e\n$st");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Ошибка удаления: $e')),
        );
      }
    }
  }

  Future<void> _addContact(BuildContext context) async {
    final result = await Navigator.push<Map<String, dynamic>?>(
      context,
      MaterialPageRoute(builder: (_) => const SearchUserScreen()),
    );
    if (result != null && result['uid'] != null) {
      final contactId = result['uid'] as String;
      await FirebaseFirestore.instance
          .collection('users')
          .doc(myId)
          .collection('contacts')
          .doc(contactId)
          .set({
        'username': result['username'] ?? '',
        'email': result['email'] ?? '',
      });
      logInfo("[UserListScreen] Added contact: $contactId");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Контакт добавлен')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final contactsRef = FirebaseFirestore.instance
        .collection('users')
        .doc(myId)
        .collection('contacts');
    return Scaffold(
      appBar: AppBar(
        title: Text('Контакты: ${myUsername ?? "..."}'),
        actions: [
          IconButton(
            icon: const Icon(Icons.person_add),
            tooltip: 'Добавить контакт',
            onPressed: () => _addContact(context),
          ),
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Выйти',
            onPressed: () async {
              final confirm = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('Выйти из аккаунта?'),
                  content: const Text('Вы действительно хотите выйти?'),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, false),
                      child: const Text('Отмена'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, true),
                      child: const Text('Выйти', style: TextStyle(color: Colors.red)),
                    ),
                  ],
                ),
              );
              if (confirm == true) {
                await FirebaseAuth.instance.signOut();
                logInfo("[UserListScreen] Signed out");
              }
            },
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : StreamBuilder<QuerySnapshot>(
              stream: contactsRef.snapshots(),
              builder: (_, snap) {
                if (!snap.hasData) return const Center(child: CircularProgressIndicator());
                final contacts = snap.data!.docs;
                if (contacts.isEmpty) {
                  return const Center(child: Text('Нет добавленных контактов.'));
                }
                contacts.sort((a, b) => (a['username'] as String).compareTo(b['username'] as String));
                return ListView.builder(
                  itemCount: contacts.length,
                  itemBuilder: (_, i) {
                    final u = contacts[i];
                    final peerId = u.id;
                    final peerUsername = u['username'];
                    return ListTile(
                      leading: CircleAvatar(
                        child: Text(peerUsername.isNotEmpty ? peerUsername[0].toUpperCase() : "?"),
                      ),
                      title: Text(peerUsername),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            tooltip: "Аудиозвонок",
                            icon: const Icon(Icons.call),
                            color: Colors.blue,
                            onPressed: () => _callUser(peerId, peerUsername, 'audio'),
                          ),
                          IconButton(
                            tooltip: "Видеозвонок",
                            icon: const Icon(Icons.videocam),
                            color: Colors.green,
                            onPressed: () => _callUser(peerId, peerUsername, 'video'),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete),
                            color: Colors.red,
                            tooltip: "Удалить контакт",
                            onPressed: () async {
                              final confirm = await showDialog<bool>(
                                context: context,
                                builder: (ctx) => AlertDialog(
                                  title: const Text('Удалить контакт?'),
                                  content: Text('Вы действительно хотите удалить $peerUsername из контактов?'),
                                  actions: [
                                    TextButton(
                                      onPressed: () => Navigator.pop(ctx, false),
                                      child: const Text('Отмена'),
                                    ),
                                    TextButton(
                                      onPressed: () => Navigator.pop(ctx, true),
                                      child: const Text('Удалить', style: TextStyle(color: Colors.red)),
                                    ),
                                  ],
                                ),
                              );
                              if (confirm == true) {
                                await _deleteContact(peerId);
                              }
                            },
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
    );
  }
}