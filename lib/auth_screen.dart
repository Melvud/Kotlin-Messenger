import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'user_list_screen.dart';

class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key});

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _usernameCtrl = TextEditingController();

  final _focusPass = FocusNode();
  final _focusUsername = FocusNode();

  bool _isRegister = false;
  bool _obscure = true;
  bool _loading = false;
  String? _errorInline;

  String? _emailValidator(String? v) {
    final value = (v ?? '').trim();
    final emailRegex = RegExp(r"^[\w\.\-]+@[\w\.\-]+\.\w+$");
    if (value.isEmpty) return 'Email обязателен';
    if (!emailRegex.hasMatch(value)) return 'Некорректный email';
    return null;
  }

  String? _passwordValidator(String? v) {
    final value = (v ?? '').trim();
    if (value.length < 6) return 'Минимум 6 символов';
    return null;
  }

  String? _usernameValidator(String? v) {
    if (!_isRegister) return null;
    final value = (v ?? '').trim();
    final rgx = RegExp(r'^[a-zA-Z0-9_]{3,24}$');
    if (!rgx.hasMatch(value)) return '3–24 символа: латиница/цифры/_';
    return null;
  }

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passCtrl.dispose();
    _usernameCtrl.dispose();
    _focusPass.dispose();
    _focusUsername.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) {
      HapticFeedback.heavyImpact();
      return;
    }
    setState(() {
      _loading = true;
      _errorInline = null;
    });

    try {
      if (_isRegister) {
        final cred = await FirebaseAuth.instance.createUserWithEmailAndPassword(
          email: _emailCtrl.text.trim(),
          password: _passCtrl.text,
        );
        final uid = cred.user!.uid;
        await FirebaseFirestore.instance.collection('users').doc(uid).set({
          'email': _emailCtrl.text.trim(),
          'username': _usernameCtrl.text.trim(),
          'createdAt': FieldValue.serverTimestamp(),
        }, SetOptions(merge: true));
      } else {
        await FirebaseAuth.instance.signInWithEmailAndPassword(
          email: _emailCtrl.text.trim(),
          password: _passCtrl.text,
        );
      }

      if (!mounted) return;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (_) => const UserListScreen()),
      );
    } on FirebaseAuthException catch (e) {
      setState(() => _errorInline = e.message ?? 'Ошибка аутентификации');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_errorInline!)),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Неожиданная ошибка: $e')));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final title = _isRegister ? 'Регистрация' : 'Войти';
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Form(
              key: _formKey,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 180),
                    child: Text(
                      _isRegister ? 'Создайте аккаунт' : 'С возвращением',
                      key: ValueKey(_isRegister),
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                  ),
                  const SizedBox(height: 16),
                  if (_errorInline != null) ...[
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(_errorInline!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ),
                  ],
                  TextFormField(
                    controller: _emailCtrl,
                    decoration: const InputDecoration(labelText: 'Email'),
                    keyboardType: TextInputType.emailAddress,
                    textInputAction: TextInputAction.next,
                    validator: _emailValidator,
                    onFieldSubmitted: (_) => _focusPass.requestFocus(),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _passCtrl,
                    focusNode: _focusPass,
                    decoration: InputDecoration(
                      labelText: 'Пароль',
                      suffixIcon: IconButton(
                        tooltip: _obscure ? 'Показать' : 'Скрыть',
                        onPressed: () => setState(() => _obscure = !_obscure),
                        icon: Icon(_obscure ? Icons.visibility : Icons.visibility_off),
                      ),
                    ),
                    obscureText: _obscure,
                    textInputAction: _isRegister ? TextInputAction.next : TextInputAction.done,
                    validator: _passwordValidator,
                    onFieldSubmitted: (_) => _isRegister ? _focusUsername.requestFocus() : _submit(),
                  ),
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 180),
                    child: _isRegister
                        ? Padding(
                            key: const ValueKey('username_field'),
                            padding: const EdgeInsets.only(top: 12),
                            child: TextFormField(
                              controller: _usernameCtrl,
                              focusNode: _focusUsername,
                              decoration: const InputDecoration(labelText: 'Имя пользователя'),
                              textInputAction: TextInputAction.done,
                              validator: _usernameValidator,
                              onFieldSubmitted: (_) => _submit(),
                            ),
                          )
                        : const SizedBox.shrink(key: ValueKey('no_username')),
                  ),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: _loading ? null : _submit,
                    icon: _loading
                        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.login),
                    label: Text(_isRegister ? 'Зарегистрироваться' : 'Войти'),
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: _loading ? null : () => setState(() => _isRegister = !_isRegister),
                    child: Text(_isRegister ? 'Уже есть аккаунт? Войти' : 'Нет аккаунта? Регистрация'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
