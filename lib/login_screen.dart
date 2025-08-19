import 'package:flutter/material.dart';
import 'package:firebase_auth/firebase_auth.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final ctrl = TextEditingController();
  String error = '';
  bool _loading = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Login')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(
              controller: ctrl,
              decoration: const InputDecoration(labelText: 'Enter username'),
            ),
            const SizedBox(height: 10),
            _loading
                ? const CircularProgressIndicator()
                : ElevatedButton(
                    onPressed: () async {
                      if (ctrl.text.trim().isEmpty) {
                        setState(() => error = 'Enter username');
                        return;
                      }
                      setState(() => _loading = true);
                      try {
                        await FirebaseAuth.instance.signInAnonymously();
                        if (!mounted) return;
                        Navigator.pushReplacementNamed(context, '/users', arguments: ctrl.text.trim());
                      } catch (e) {
                        setState(() => error = 'Login failed: $e');
                      } finally {
                        if (mounted) setState(() => _loading = false);
                      }
                    },
                    child: const Text('Login'),
                  ),
            if (error.isNotEmpty) Text(error, style: const TextStyle(color: Colors.red)),
          ],
        ),
      ),
    );
  }
}