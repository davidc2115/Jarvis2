package com.jarvis2.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis2.app.ui.theme.BubbleStyle
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var apiKeyField by remember(state.webSearchApiKey) { mutableStateOf(state.webSearchApiKey) }
    var hfTokenField by remember(state.huggingFaceToken) { mutableStateOf(state.huggingFaceToken) }
    val context = LocalContext.current

    // Item 1 de la roadmap README : selecteur de fichier .task (SAF) pour importer le
    // modele MediaPipe (fallback quand AICore/Gemini Nano n'est pas disponible sur
    // l'appareil), au lieu d'avoir a le pousser via adb dans Android/data/.../models/.
    val pickModelFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val dest = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/local-llm.task")
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            viewModel.refreshEngine()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages", color = JarvisCyan) }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Moteur IA", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(state.engine?.displayName ?: "…")
            Text(state.engine?.notes.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Button(onClick = { viewModel.refreshEngine() }, modifier = Modifier.padding(top = 8.dp)) { Text("Ré-détecter") }
            Button(
                onClick = { pickModelFile.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Importer un modèle .task (secours hors AICore)") }
            Text(
                "Par défaut, Jarvis télécharge et utilise automatiquement SmolVLM2 (léger, texte + image, aucune connexion/compte requis) — pas besoin de faire quoi que ce soit ici pour ça.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Gemma 3 1B (optionnel)", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Alternative à SmolVLM2, verrouillée par Google derrière une licence Hugging Face. " +
                    "Pour l'utiliser : crée un compte gratuit sur huggingface.co, ouvre la page " +
                    "litert-community/Gemma3-1B-IT et accepte la licence, puis va dans Settings → " +
                    "Access Tokens pour générer un jeton (lecture seule suffit) et colle-le ci-dessous.",
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = hfTokenField,
                onValueChange = { hfTokenField = it },
                label = { Text("Jeton Hugging Face") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(onClick = { viewModel.setHuggingFaceToken(hfTokenField) }, modifier = Modifier.padding(top = 8.dp)) { Text("Enregistrer le jeton") }
            Button(
                onClick = { viewModel.downloadGemma() },
                enabled = !state.isDownloadingGemma,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(if (state.isDownloadingGemma) "Téléchargement en cours…" else "Télécharger Gemma 3 1B (529 Mo)") }
            if (state.isDownloadingGemma) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), color = JarvisCyan, strokeWidth = 2.dp)
            }
            state.gemmaDownloadError?.let { error ->
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }

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

            Divider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                "Vault Obsidian : par défaut l'app utilise son propre dossier privé. Pour pointer vers un vault externe (ex. synchronisé avec Obsidian desktop), utilise le bouton « 📁 Externe » dans l'onglet Vault.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
