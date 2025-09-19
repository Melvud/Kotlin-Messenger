package com.example.messenger_app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.messenger_app.AppGraph
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    onAuthed: () -> Unit
) {
    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (isLogin) "Вход" else "Регистрация", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))

                AnimatedContent(targetState = isLogin, label = "auth-swap") { login ->
                    Column(Modifier.fillMaxWidth()) {
                        if (!login) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it },
                                label = { Text("Имя") }, singleLine = true,
                                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            label = { Text("Email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Email),
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text("Пароль") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        loading = true
                        error = null
                        scope.launch {
                            runCatching {
                                if (isLogin) {
                                    AppGraph.userRepo.signIn(email.trim(), password)
                                } else {
                                    AppGraph.userRepo.signUp(name.trim(), email.trim(), password)
                                }
                            }.onSuccess {
                                loading = false
                                onAuthed()
                            }.onFailure {
                                loading = false
                                error = it.message ?: "Ошибка"
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(if (loading) "Подождите…" else if (isLogin) "Войти" else "Создать аккаунт")
                }
                TextButton(onClick = { isLogin = !isLogin }) {
                    Text(if (isLogin) "Нет аккаунта? Зарегистрироваться" else "Уже есть аккаунт? Войти")
                }
            }
        }
    }
}
