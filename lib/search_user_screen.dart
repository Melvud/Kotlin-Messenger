import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'dart:async';

class SearchUserScreen extends StatefulWidget {
  const SearchUserScreen({super.key});
  @override
  State<SearchUserScreen> createState() => _SearchUserScreenState();
}

class _SearchUserScreenState extends State<SearchUserScreen> {
  final _controller = TextEditingController();
  List<DocumentSnapshot> _results = [];
  String? _info;
  bool _loading = false;
  Timer? _debounce;

  Future<void> _search() async {
    final query = _controller.text.trim();
    if (query.isEmpty) return;
    setState(() {
      _loading = true;
      _info = null;
    });
    try {
      final snap = await FirebaseFirestore.instance
          .collection('users')
          .where('username', isGreaterThanOrEqualTo: query)
          .where('username', isLessThanOrEqualTo: '$query\uf8ff')
          .limit(10)
          .get();
      setState(() => _results = snap.docs);
    } catch (e) {
      setState(() => _info = 'Ошибка поиска: $e');
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _addContact(String contactUid, String username, String email) async {
    final myUid = FirebaseAuth.instance.currentUser!.uid;
    final myDoc = await FirebaseFirestore.instance.collection('users').doc(myUid).get();
    final myUsername = myDoc['username'];
    final myEmail = myDoc['email'];

    await FirebaseFirestore.instance
        .collection('users')
        .doc(myUid)
        .collection('contacts')
        .doc(contactUid)
        .set({'username': username, 'email': email});
    await FirebaseFirestore.instance
        .collection('users')
        .doc(contactUid)
        .collection('contacts')
        .doc(myUid)
        .set({'username': myUsername, 'email': myEmail});
    if (mounted) {
      Navigator.pop(context, {
        'uid': contactUid,
        'username': username,
        'email': email,
      });
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final myUid = FirebaseAuth.instance.currentUser!.uid;
    return Scaffold(
      appBar: AppBar(title: const Text("Поиск пользователей")),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(
              controller: _controller,
              decoration: const InputDecoration(labelText: "Никнейм"),
              onChanged: (_) {
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 400), _search);
              },
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 10),
            _loading
                ? const CircularProgressIndicator()
                : ElevatedButton(onPressed: _search, child: const Text("Поиск")),
            if (_info != null) Text(_info!, style: const TextStyle(color: Colors.green)),
            Expanded(
              child: ListView(
                children: _results
                    .where((doc) => doc.id != myUid)
                    .map((doc) {
                  final username = doc['username'] as String;
                  final email = doc['email'] as String;
                  final uid = doc.id;
                  // Для MVP не проверяем в контактах ли уже
                  return ListTile(
                    leading: CircleAvatar(
                      backgroundColor: Colors.blueGrey,
                      child: Text(username.isNotEmpty ? username[0].toUpperCase() : "?"),
                    ),
                    title: Text(username),
                    subtitle: Text(email),
                    trailing: ElevatedButton(
                      onPressed: () => _addContact(uid, username, email),
                      child: const Text("Добавить"),
                    ),
                  );
                }).toList(),
              ),
            ),
          ],
        ),
      ),
    );
  }
}