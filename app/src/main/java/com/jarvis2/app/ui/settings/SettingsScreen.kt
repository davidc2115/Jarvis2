package com.jarvis2.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // Multi-cles Groq : liste editable locale, resynchronisee depuis le
    // ViewModel a chaque changement persiste (ajout/suppression/sauvegarde) ;
    // toujours au moins une ligne vide si aucune cle n'est encore configuree,
    // comme dans l'ancienne Newjarvis (SettingsActivity.buildApiKeyFields).
    val groqKeyFields = remember(state.groqApiKeys) {
        mutableStateListOf(*state.groqApiKeys.ifEmpty { listOf("") }.toTypedArray())
    }
    var geminiCloudKeyField by remember(state.geminiCloudApiKey) { mutableStateOf(state.geminiCloudApiKey) }

    // Lance l'ecran de consentement Google quand GoogleAuthController signale qu'il en
    // faut un (voir SettingsViewModel.connectGmail/pendingGmailAuthIntent) ; le resultat
    // (accorde ou annule) revient dans viewModel.onGoogleAuthResult.
    val gmailAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onGoogleAuthResult(result.data) }

    LaunchedEffect(state.pendingGmailAuthIntent) {
        state.pendingGmailAuthIntent?.let { intent ->
            gmailAuthLauncher.launch(intent)
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

            Text("IA cloud gratuite (compréhension avancée)", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Optionnel. Permet à Jarvis de comprendre le langage naturel libre (créer une fiche " +
                    "contact, une note, retenir une préférence...) au lieu de se limiter aux phrases " +
                    "reconnues localement. Sans clé, Jarvis reste 100% local/hors-ligne comme avant. " +
                    "Groq est utilisé en priorité (gratuit, sans carte bancaire : console.groq.com), " +
                    "Gemini cloud en repli (gratuit : aistudio.google.com).",
                style = MaterialTheme.typography.labelSmall,
            )
            // Statut actuel visible ici (voir aiPriorityMode dans
            // SettingsUiState) : suite au signalement "toujours pas de choix
            // avec Groq en IA principal" -- avant ça, rien dans Reglages ne
            // confirmait que Groq est deja utilise en priorite des qu'une
            // cle est configuree ci-dessous (reglable uniquement depuis le
            // chat, voir CommandRouter.kt, pas un toggle ici).
            Text(
                if (state.groqApiKeys.isEmpty() && state.geminiCloudApiKey.isBlank()) {
                    "Statut : aucune clé configurée -- Jarvis reste 100% local."
                } else if (state.aiPriorityMode == "local_first") {
                    "Statut : clé(s) configurée(s), mais priorité actuellement mise sur le modèle local " +
                        "(dis « remets le cloud en priorité » dans le chat pour revenir à Groq/Gemini en premier)."
                } else {
                    "Statut : Groq/Gemini cloud utilisé en priorité dès l'envoi d'un message (comportement par défaut)."
                },
                style = MaterialTheme.typography.labelSmall,
                color = JarvisCyan,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Clés API Groq (rotation automatique)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    "+",
                    color = JarvisCyan,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { groqKeyFields.add("") }
                        .padding(4.dp),
                )
            }
            groqKeyFields.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { groqKeyFields[index] = it },
                        label = { Text("Clé Groq ${index + 1}") },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                groqKeyFields.removeAt(index)
                                // Suppression immediate (pas besoin d'appuyer sur "Enregistrer") :
                                // au moins une ligne vide reste affichee si la liste est vidée.
                                viewModel.setGroqApiKeys(groqKeyFields.toList())
                                if (groqKeyFields.isEmpty()) groqKeyFields.add("")
                            }
                            .padding(6.dp),
                    )
                }
            }
            Button(
                onClick = { viewModel.setGroqApiKeys(groqKeyFields.toList()) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Enregistrer") }
            OutlinedTextField(
                value = geminiCloudKeyField,
                onValueChange = { geminiCloudKeyField = it },
                label = { Text("Clé API Gemini cloud (repli)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { viewModel.setGeminiCloudApiKey(geminiCloudKeyField) }, modifier = Modifier.padding(top = 8.dp)) { Text("Enregistrer") }

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

            Text("Voix", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = state.ttsEnabled, onCheckedChange = { viewModel.setTtsEnabled(it) })
                Text(
                    "Lire les réponses de Jarvis à voix haute",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "Le bouton micro à côté du champ de saisie du chat permet de dicter un message (dictée vocale système).",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Présentation du planning", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = state.calendarGroupByDay, onCheckedChange = { viewModel.setCalendarGroupByDay(it) })
                Text(
                    "Regrouper les événements par jour (par défaut, si aucune présentation personnalisée n'est enregistrée)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Proactivité", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = state.proactiveRemindersEnabled, onCheckedChange = { viewModel.setProactiveRemindersEnabled(it) })
                Text(
                    "Rappel notifié 15 min avant chaque événement d'agenda",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(checked = state.proactiveBriefingEnabled, onCheckedChange = { viewModel.setProactiveBriefingEnabled(it) })
                Text(
                    "Briefing du matin (résumé de la journée, vers 8h)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Présentation personnalisée (contacts, planning, recherche web)", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Décris dans le chat, en détail, comment tu veux que chaque chose soit présentée -- Jarvis l'enregistre dans le vault et l'applique à chaque fois, jusqu'à ce que tu la changes. Par exemple :",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "« Enregistre la présentation des contacts : nom en gras, numéro et email sur la même ligne, trié par ordre alphabétique. »",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "« Enregistre la présentation du planning : regroupe par semaine, indique la durée de chaque événement. »",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "« Enregistre la présentation de la recherche web : réponds en 3 phrases maximum, avec les sources en fin de message. »",
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
