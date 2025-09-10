import 'package:flutter/material.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:cloud_functions/cloud_functions.dart';
import 'package:uuid/uuid.dart';

import 'call_screen.dart';
import 'search_user_screen.dart';
import 'ui/avatars/user_avatar.dart';
import 'ui/placeholders/empty_state.dart';

class UserListScreen extends StatefulWidget {
  const UserListScreen({super.key});

  @override
  State<UserListScreen> createState() => _UserListScreenState();
}

class _UserListScreenState extends State<UserListScreen> {
  late final String myId;

  @override
  void initState() {
    super.initState();
    final user = FirebaseAuth.instance.currentUser;
    myId = user?.uid ?? '';
  }

  Future<void> _deleteContact(String contactId) async {
    try {
      await FirebaseFirestore.instance
          .collection('users')
          .doc(myId)
          .collection('contacts')
          .doc(contactId)
          .delete();
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Контакт удалён')));
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Ошибка удаления: $e')));
    }
  }

  Future<void> _callUser(String peerId, String peerUsername, String callType) async {
    try {
      final myDoc = await FirebaseFirestore.instance.collection('users').doc(myId).get();
      final myUsername = myDoc.data()?['username'] as String? ?? 'Me';

      // Создаём документ звонка
      final callId = const Uuid().v4();
      final callRef = FirebaseFirestore.instance.collection('calls').doc(callId);
      await callRef.set({
        'callerId': myId,
        'calleeId': peerId,
        'callerUsername': myUsername,
        'calleeUsername': peerUsername,
        'callType': callType, // 'audio' | 'video'
        'callerStatus': 'calling',
        'calleeStatus': 'ringing',
        'createdAt': FieldValue.serverTimestamp(),
      });

      // Cloud Function уведомление собеседнику
      try {
        final params = {
          'callId': callId,
          'callerId': myId,
          'calleeId': peerId,
          'callType': callType,
          'callerUsername': myUsername,
          'calleeUsername': peerUsername,
        };
        await FirebaseFunctions.instance.httpsCallable('sendCallNotification').call(params);
      } catch (_) {
        // CF может быть опционален — не блокируем UI
      }

      if (!mounted) return;
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (_) => CallScreen(
            isCaller: true,
            peerId: peerId,
            peerUsername: peerUsername,
            myId: myId,
            myUsername: myUsername,
            docId: callId,
            callType: callType,
          ),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось начать звонок: $e')));
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
        title: const Text('Мои контакты'),
        actions: [
          IconButton(
            tooltip: 'Поиск',
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SearchUserScreen())),
            icon: const Icon(Icons.search),
          ),
          PopupMenuButton<String>(
            itemBuilder: (ctx) => const [
              PopupMenuItem(value: 'settings', child: Text('Настройки')),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SearchUserScreen())),
        icon: const Icon(Icons.person_add),
        label: const Text('Добавить контакт'),
      ),
      body: StreamBuilder<QuerySnapshot>(
        stream: contactsRef.snapshots(),
        builder: (_, snap) {
          if (snap.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snap.hasError) {
            return Center(child: Text('Ошибка загрузки: ${snap.error}'));
          }
          final contacts = snap.data?.docs ?? [];
          if (contacts.isEmpty) {
            return EmptyState(
              icon: Icons.contact_page_outlined,
              title: 'Здесь пока пусто',
              subtitle: 'Добавьте первый контакт, чтобы начать звонок',
              action: FilledButton(
                onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SearchUserScreen())),
                child: const Text('Добавить контакт'),
              ),
            );
          }
          contacts.sort((a, b) => (a['username'] as String).compareTo(b['username'] as String));
          return ListView.separated(
            itemCount: contacts.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (_, i) {
              final u = contacts[i];
              final peerId = u.id;
              final peerUsername = u['username'] as String? ?? '—';
              final email = u['email'] as String? ?? '';

              return ListTile(
                onLongPress: () async {
                  final confirm = await showDialog<bool>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      title: const Text('Удалить контакт?'),
                      content: Text('Вы действительно хотите удалить $peerUsername?'),
                      actions: [
                        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Отмена')),
                        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Удалить')),
                      ],
                    ),
                  );
                  if (confirm == true) await _deleteContact(peerId);
                },
                leading: UserAvatar(displayName: peerUsername, email: email, radius: 20),
                title: Text(peerUsername),
                subtitle: email.isNotEmpty ? Text(email) : null,
                trailing: Wrap(spacing: 6, children: [
                  IconButton(
                    tooltip: "Аудиозвонок",
                    icon: const Icon(Icons.call),
                    onPressed: () => _callUser(peerId, peerUsername, 'audio'),
                  ),
                  IconButton(
                    tooltip: "Видеозвонок",
                    icon: const Icon(Icons.videocam),
                    onPressed: () => _callUser(peerId, peerUsername, 'video'),
                  ),
                  IconButton(
                    tooltip: "Удалить",
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () async {
                      final confirm = await showDialog<bool>(
                        context: context,
                        builder: (ctx) => AlertDialog(
                          title: const Text('Удалить контакт?'),
                          content: Text('Вы действительно хотите удалить $peerUsername?'),
                          actions: [
                            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Отмена')),
                            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Удалить')),
                          ],
                        ),
                      );
                      if (confirm == true) await _deleteContact(peerId);
                    },
                  ),
                ]),
              );
            },
          );
        },
      ),
    );
  }
}
