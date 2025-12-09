package com.example.messenger_app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.messenger_app.data.model.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    chatId: String,
    chatName: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onAddParticipantClick: () -> Unit
) {
    val participants by viewModel.participants.collectAsState()
    var isEditingName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(chatName) }

    LaunchedEffect(Unit) {
        viewModel.loadParticipants()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Информация о группе") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Group Name Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Название группы") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        viewModel.updateGroupName(newName)
                        isEditingName = false
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Save") // Using Add as Check/Save icon substitute or find Check
                    }
                } else {
                    Text(
                        text = chatName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { isEditingName = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Participants Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${participants.size} участников",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onAddParticipantClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add Participant")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Participants List
            LazyColumn {
                items(participants) { user ->
                    ParticipantItem(
                        user = user,
                        onRemove = { viewModel.removeParticipant(user.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ParticipantItem(user: User, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (user.avatarUrl.isNotEmpty()) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = user.name,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red)
        }
    }
}
