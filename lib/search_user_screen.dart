import 'dart:async';
import 'package:flutter/material.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';

import 'ui/avatars/user_avatar.dart';

class SearchUserScreen extends StatefulWidget {
  const SearchUserScreen({super.key});
  @override
  State<SearchUserScreen> createState() => _SearchUserScreenState();
}

class _SearchUserScreenState extends State<SearchUserScreen> {
  final _controller = TextEditingController();
  Timer? _debounce;
  bool _loading = false;
  String _query = '';
  List<DocumentSnapshot> _results = [];
  String? _info;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () {
      setState(() => _query = v.trim());
      _search();
    });
  }

  Future<void> _search() async {
    final q = _query;
    if (q.isEmpty) {
      setState(() { _results = []; _info = 'Введите имя пользователя или email'; });
      return;
    }
    setState(() { _loading = true; _info = null; });

    try {
      // Простой и надёжный поиск: по точному username или email.
      Query<Map<String, dynamic>> base = FirebaseFirestore.instance.collection('users');
      QuerySnapshot<Map<String, dynamic>> snap;
      if (q.contains('@')) {
        snap = await base.where('email', isEqualTo: q).limit(25).get();
      } else {
        snap = await base.where('username', isEqualTo: q).limit(25).get();
      }
      setState(() {
        _results = snap.docs;
        if (_results.isEmpty) _info = 'Ничего не найдено';
      });
    } catch (e) {
      setState(() => _info = 'Ошибка поиска: $e');
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _addContact(String contactUid, String username, String email) async {
    try {
      final myUid = FirebaseAuth.instance.currentUser!.uid;
      await FirebaseFirestore.instance
          .collection('users')
          .doc(myUid)
          .collection('contacts')
          .doc(contactUid)
          .set({
        'username': username,
        'email': email,
        'createdAt': FieldValue.serverTimestamp(),
      });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Добавлено в контакты')));
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Не удалось добавить: $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Поиск пользователей')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12.0),
            child: TextField(
              controller: _controller,
              onChanged: _onChanged,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: 'Введите имя пользователя или email',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _query.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _controller.clear();
                          _onChanged('');
                        },
                      )
                    : null,
              ),
            ),
          ),
          if (_loading) const LinearProgressIndicator(minHeight: 2),
          Expanded(
            child: _query.isEmpty
                ? const Center(child: Text('Введите имя пользователя или email'))
                : _results.isEmpty
                    ? Center(child: Text(_info ?? 'Ничего не найдено'))
                    : ListView.separated(
                        itemCount: _results.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (ctx, i) {
                          final doc = _results[i];
                          final username = (doc['username'] ?? '') as String;
                          final email = (doc['email'] ?? '') as String;
                          final uid = doc.id;

                          return ListTile(
                            leading: UserAvatar(displayName: username, email: email),
                            title: Text(username.isEmpty ? '—' : username),
                            subtitle: email.isEmpty ? null : Text(email),
                            trailing: FilledButton.tonal(
                              onPressed: () => _addContact(uid, username, email),
                              child: const Text('Добавить'),
                            ),
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}
