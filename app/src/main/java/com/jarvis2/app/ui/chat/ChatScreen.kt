package com.jarvis2.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis2.app.ai.Turn
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import com.jarvis2.app.ui.theme.JarvisSurfaceRaised
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("JARVIS", style = MaterialTheme.typography.headlineMedium, color = JarvisCyan)
                        Text(
                            state.engine?.let { "${it.displayName} · ${if (it.isReady) "prêt" else "indisponible"}" } ?: "Initialisation…",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                if (state.isThinking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = JarvisCyan, strokeWidth = 2.dp)
                            Text("Jarvis réfléchit…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            state.pendingWebSearchQuery?.let { query ->
                Surface(color = JarvisSurfaceRaised, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Jarvis n'est pas sûr. Rechercher \"$query\" sur le web ?",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { viewModel.dismissWebSearchPrompt() }) { Text("Ignorer") }
                        TextButton(onClick = { viewModel.searchWeb(query) }) {
                            Text("Rechercher", color = JarvisGold)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Demande quelque chose à Jarvis…") },
                )
                IconButton(onClick = {
                    if (input.isNotBlank()) {
                        viewModel.sendMessage(input)
                        input = ""
                    }
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Envoyer", tint = JarvisCyan)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatUiMessage) {
    val isUser = message.role == Turn.Role.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) JarvisSurfaceRaised else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isUser) JarvisGold.copy(alpha = 0.4f) else JarvisCyan.copy(alpha = 0.4f)),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
