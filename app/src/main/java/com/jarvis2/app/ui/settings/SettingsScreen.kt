package com.jarvis2.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.jarvis2.app.ai.gguf.LocalGgufModel
import com.jarvis2.app.ui.theme.BubbleStyle
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var apiKeyField by remember(state.webSearchApiKey) { mutableStateOf(state.webSearchApiKey) }

    // Lance l'ecran de consentement Google quand GoogleAuthController signale qu'il en
    // faut un (voir SettingsViewModel.connectGmail/pendingGmailAuthIntent) ; le resultat
    // (accorde ou annule) revient dans viewModel.onGoogleAuthResult.
    val gmailAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onGoogleAuthResult(result.data) }

    LaunchedEffect(state.pendingGmailAuthIntent) {
        state.pendingGmailAuthIntent?.let { intentSender ->
            gmailAuthLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            viewModel.clearPendingGmailAuthIntent()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages", color = JarvisCyan) }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Moteur IA", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text("Actif : " + (state.engine?.displayName ?: "…"))
            Text(state.engine?.notes.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text(
                "Automatique essaie Gemini Nano (si le téléphone le supporte), sinon SmolVLM2 " +
                    "(léger, se télécharge tout seul, aucun compte requis). Choisir un moteur ci-dessous " +
                    "force Jarvis à s'en servir en priorité — le téléchargement (si besoin) démarre " +
                    "aussitôt, sans bouton séparé. Aucune de ces options ne nécessite de compte ni de " +
                    "jeton (contrairement à l'ancien Gemma 3, verrouillé par Google).",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Liste verticale plutot qu'une Row de FilterChip : sur un ecran de
            // telephone (~360-412dp), 6 chips avec libelles complets (ex.
            // "Dolphin 3.0 (Qwen2.5 1.5B)") ne rentrent pas dans une Row non
            // scrollable -- les options en trop sont simplement coupees hors
            // ecran et donc impossibles a toucher, ce qui empechait de fait
            // de choisir Qwen/Phi/Dolphin (et donc de declencher leur
            // telechargement).
            val engineOptions = buildList {
                add(Triple("auto", "Automatique", state.preferredEngineId == "auto"))
                add(Triple("aicore-gemini-nano", "Gemini Nano", state.preferredEngineId == "aicore-gemini-nano"))
                add(Triple("smolvlm2-llamacpp", "SmolVLM2", state.preferredEngineId == "smolvlm2-llamacpp"))
                LocalGgufModel.entries.forEach { model ->
                    add(
                        Triple(
                            "selectable-gguf:${model.id}",
                            model.displayName,
                            state.preferredEngineId == "selectable-gguf" && state.selectedLocalModel == model.id,
                        ),
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                engineOptions.forEach { (id, label, selected) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected) {
                                if (id.startsWith("selectable-gguf:")) {
                                    viewModel.setPreferredEngine("selectable-gguf", id.removePrefix("selectable-gguf:"))
                                } else {
                                    viewModel.setPreferredEngine(id)
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (state.isDownloadingLocalModel) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), color = JarvisCyan, strokeWidth = 2.dp)
            }
            state.localModelDownloadError?.let { error ->
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            Button(onClick = { viewModel.refreshEngine() }, modifier = Modifier.padding(top = 8.dp)) { Text("Ré-détecter") }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Recherche web (secours)", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Utilisée uniquement quand Jarvis dit explicitement ne pas savoir. Nécessite une clé d'API d'un fournisseur de recherche de ton choix.",
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                label = { Text("Clé API de recherche") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { viewModel.setWebSearchApiKey(apiKeyField) }, modifier = Modifier.padding(top = 8.dp)) { Text("Enregistrer") }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Mail (Google)", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Lecture seule via l'API Gmail. Connecte ton compte Google ci-dessous (écran de " +
                    "consentement standard Google -- Jarvis ne voit jamais ton mot de passe). Une fois " +
                    "connecté, demande à Jarvis dans le chat : « lis mes mails ».",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (state.gmailConnected) "Statut : connecté" else "Statut : non connecté",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.gmailConnected) JarvisCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { viewModel.connectGmail() }, enabled = !state.isConnectingGmail) {
                    Text(
                        when {
                            state.isConnectingGmail -> "Connexion…"
                            state.gmailConnected -> "Reconnecter"
                            else -> "Connecter Gmail"
                        },
                    )
                }
                if (state.isConnectingGmail) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp), color = JarvisCyan, strokeWidth = 2.dp)
                }
            }
            state.gmailConnectError?.let { error ->
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Apparence des bulles de chat", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text("Forme", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                BubbleStyle.shapeOptions.forEach { shapeId ->
                    FilterChip(
                        selected = state.bubbleShape == shapeId,
                        onClick = { viewModel.setBubbleShape(shapeId) },
                        label = { Text(BubbleStyle.shapeLabel(shapeId)) },
                    )
                }
            }
            Text("Couleur — mes messages", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                BubbleStyle.colorOptions.forEach { colorId ->
                    FilterChip(
                        selected = state.bubbleUserColor == colorId,
                        onClick = { viewModel.setBubbleUserColor(colorId) },
                        label = { Text(BubbleStyle.colorLabel(colorId), color = BubbleStyle.color(colorId)) },
                    )
                }
            }
            Text("Couleur — messages de Jarvis", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                BubbleStyle.colorOptions.forEach { colorId ->
                    FilterChip(
                        selected = state.bubbleAssistantColor == colorId,
                        onClick = { viewModel.setBubbleAssistantColor(colorId) },
                        label = { Text(BubbleStyle.colorLabel(colorId), color = BubbleStyle.color(colorId)) },
                    )
                }
            }
            Text(
                "S'applique à la prochaine ouverture de l'écran Chat.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Présentation du planning", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = state.calendarGroupByDay, onCheckedChange = { viewModel.setCalendarGroupByDay(it) })
                Text(
                    "Regrouper les événements par jour (sinon liste simple)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "Modifiable aussi depuis le chat : \"regroupe mon planning par jour\" / \"planning en liste simple\".",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Présentation des contacts", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = state.contactPresentationStyle == "compact",
                    onClick = { viewModel.setContactPresentationStyle("compact") },
                    label = { Text("Compacte") },
                )
                FilterChip(
                    selected = state.contactPresentationStyle == "detailed",
                    onClick = { viewModel.setContactPresentationStyle("detailed") },
                    label = { Text("Détaillée (avec numéro)") },
                )
            }
            Text(
                "Modifiable aussi depuis le chat : \"contacts en détaillé\" / \"contacts en compact\".",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Présentation de la recherche web", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = state.webSearchPresentationStyle == "detailed",
                    onClick = { viewModel.setWebSearchPresentationStyle("detailed") },
                    label = { Text("Détaillée (titre + extrait + lien)") },
                )
                FilterChip(
                    selected = state.webSearchPresentationStyle == "compact",
                    onClick = { viewModel.setWebSearchPresentationStyle("compact") },
                    label = { Text("Compacte (titres seuls)") },
                )
            }
            Text(
                "Modifiable aussi depuis le chat : \"recherche web en détaillé\" / \"recherche web en compact\".",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                "Vault Obsidian : par défaut l'app utilise son propre dossier privé. Pour pointer vers un vault externe (ex. synchronisé avec Obsidian desktop), utilise le bouton « 📁 Externe » dans l'onglet Vault.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
