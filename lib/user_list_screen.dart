import 'package:flutter/material.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'call_screen.dart';
import 'search_user_screen.dart';
import 'incoming_call_screen.dart';
import 'dart:async';

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
    _listenForIncomingCalls();
  }

  Future<void> _loadMyUsername() async {
    try {
      final doc = await FirebaseFirestore.instance.collection('users').doc(myId).get();
      setState(() {
        myUsername = doc.data()?['username'] ?? doc.data()?['email'] ?? myId;
      });
    } catch (_) {
      setState(() {
        myUsername = myId;
      });
    }
  }

  void _listenForIncomingCalls() {
    _callSub?.cancel();
    _callSub = FirebaseFirestore.instance
        .collection('calls')
        .doc(myId)
        .snapshots()
        .listen((doc) {
      if (!doc.exists) return;
      final data = doc.data();
      if (data == null) return;

      if (data['calleeStatus'] == 'ringing' &&
          data['from'] != myId &&
          ModalRoute.of(context)?.settings.name != '/incoming_call') {
        Navigator.of(context).push(MaterialPageRoute(
          builder: (_) => IncomingCallScreen(
            myId: myId,
            myUsername: myUsername ?? '',
            peerId: data['from'],
            peerUsername: data['fromUsername'] ?? '',
            docId: myId,
            callType: data['callType'] ?? 'audio',
          ),
        ));
      }
    });
  }

  @override
  void dispose() {
    _callSub?.cancel();
    super.dispose();
  }

  Future<void> _callUser(String peerId, String peerUsername, String callType) async {
    setState(() => _loading = true);
    try {
      final peerDoc = await FirebaseFirestore.instance.collection('users').doc(peerId).get();
      final peerToken = peerDoc.data()?['fcmToken'];
      if (peerToken != null) {
        final calls = FirebaseFirestore.instance.collection('calls');
        await calls.doc(peerId).set({
          'type': 'call',
          'from': myId,
          'fromUsername': myUsername,
          'callType': callType,
          'calleeStatus': 'ringing',
          'timestamp': FieldValue.serverTimestamp(),
        });
      }
      Navigator.push(context, MaterialPageRoute(
        builder: (_) => CallScreen(
          isCaller: true,
          peerId: peerId,
          peerUsername: peerUsername,
          myId: myId,
          myUsername: myUsername!,
          docId: peerId,
          callType: callType,
        ),
      ));
    } catch (e) {
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
    } catch (e) {
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
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Контакт добавлен')));
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