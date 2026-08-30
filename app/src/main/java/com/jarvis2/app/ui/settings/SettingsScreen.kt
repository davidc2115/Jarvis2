package com.jarvis2.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var apiKeyField by remember(state.webSearchApiKey) { mutableStateOf(state.webSearchApiKey) }
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
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Moteur IA", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(state.engine?.displayName ?: "…")
            Text(state.engine?.notes.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Button(onClick = { viewModel.refreshEngine() }, modifier = Modifier.padding(top = 8.dp)) { Text("Ré-détecter") }
            Button(
                onClick = { pickModelFile.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Importer un modèle .task (secours hors AICore)") }

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
            Text(
                "Vault Obsidian : par défaut l'app utilise son propre dossier privé. Pour pointer vers un vault externe (ex. synchronisé avec Obsidian desktop), utilise le bouton « 📁 Externe » dans l'onglet Vault.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
