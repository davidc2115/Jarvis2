package com.jarvis2.app.ui.chat

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis2.app.ai.Turn
import com.jarvis2.app.ai.VoiceState
import com.jarvis2.app.ui.theme.BubbleStyle
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
    val context = LocalContext.current

    // Dictee vocale (voix -> texte) : delegue a l'ecran systeme de reconnaissance
    // vocale (RecognizerIntent.ACTION_RECOGNIZE_SPEECH), donc pas besoin de la
    // permission RECORD_AUDIO cote appli ni d'un moteur STT embarque. Le texte
    // transcrit est envoye directement comme message (pas juste rempli dans le
    // champ) -- dialogue "tour par tour", voir ai/TtsController.kt pour la
    // lecture des reponses en retour.
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            viewModel.sendMessage(text)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- Fond HUD "JARVIS vivant" (voir ui/chat/HudOrb.kt) : demande
        // explicite de l'utilisateur d'un "veritable systeme JARVIS/Ironman
        // comme la premiere version, mais en local". Alpha volontairement
        // faible pour ne jamais nuire a la lisibilite des messages, qui
        // restent affiches par-dessus sur leurs propres surfaces opaques.
        HudOrbBackground(
            voiceState = state.voiceState,
            thinking = state.isThinking,
            modifier = Modifier.fillMaxSize(),
            alpha = 0.55f,
        )
        Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("JARVIS", style = MaterialTheme.typography.headlineMedium, color = JarvisCyan)
                        // Ligne 1 : moteur local pret + priorite actuelle
                        // (cloud/local -- voir ChatUiState.aiPriorityMode).
                        // Ajoute suite au signalement "toujours pas de choix
                        // avec Groq en IA principal" : avant ça, cette ligne
                        // n'affichait QUE le moteur local, donnant
                        // l'impression que Groq n'etait jamais "choisi" meme
                        // s'il repond deja en priorite des qu'une cle est
                        // configuree (voir CloudAiClient.kt).
                        val engineLabel = state.engine?.let {
                            "${it.displayName} · ${if (it.isReady) "prêt" else "indisponible"}"
                        } ?: "Initialisation…"
                        val priorityLabel = if (state.aiPriorityMode == "local_first") {
                            "priorité : local"
                        } else {
                            "priorité : Groq/Gemini cloud"
                        }
                        Text("$engineLabel · $priorityLabel", style = MaterialTheme.typography.labelSmall)
                        // Ligne 2 (seulement si au moins une reponse cloud a
                        // deja ete envoyee dans cette session) : confirme
                        // explicitement QUI a repondu en dernier.
                        state.lastReplySource?.let { source ->
                            Text(
                                "Dernière réponse : $source",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisCyan,
                            )
                        }
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
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message, shape = state.bubbleShape, userColorId = state.bubbleUserColor, assistantColorId = state.bubbleAssistantColor)
                }
                if (state.isThinking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = JarvisCyan, strokeWidth = 2.dp)
                            Text("Jarvis réfléchit…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            state.voiceModeError?.let { error ->
                Surface(color = JarvisSurfaceRaised, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { viewModel.dismissVoiceModeError() }) { Text("OK") }
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
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez à Jarvis…")
                    }
                    try {
                        speechLauncher.launch(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Reconnaissance vocale indisponible sur cet appareil", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Dicter", tint = JarvisGold)
                }
                // --- Mode vocal mains-libres (voir ai/VoiceModeController.kt) :
                // contrairement au bouton "Dicter" ci-dessus (un aller-retour),
                // ce bouton bascule une ecoute EN CONTINU avec coupure de
                // parole automatique -- reste actif tant qu'on ne rappuie pas
                // dessus. L'icone/couleur reflete l'etat courant.
                IconButton(onClick = { viewModel.toggleVoiceMode() }) {
                    val (icon, tint, description) = when (state.voiceState) {
                        VoiceState.OFF -> Triple(Icons.Filled.RecordVoiceOver, JarvisCyan, "Activer le mode vocal mains-libres")
                        VoiceState.LISTENING -> Triple(Icons.Filled.Stop, JarvisGold, "Mode vocal actif : écoute (appuyer pour arrêter)")
                        VoiceState.SPEAKING -> Triple(Icons.Filled.Stop, JarvisGold, "Mode vocal actif : Jarvis parle (appuyer pour arrêter)")
                    }
                    Icon(icon, contentDescription = description, tint = tint)
                }
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
}

@Composable
private fun MessageBubble(
    message: ChatUiMessage,
    shape: String = "rounded",
    userColorId: String = "gold",
    assistantColorId: String = "cyan",
) {
    val isUser = message.role == Turn.Role.USER
    val accent = BubbleStyle.color(if (isUser) userColorId else assistantColorId)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) JarvisSurfaceRaised else MaterialTheme.colorScheme.surface,
            shape = BubbleStyle.shape(shape),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
