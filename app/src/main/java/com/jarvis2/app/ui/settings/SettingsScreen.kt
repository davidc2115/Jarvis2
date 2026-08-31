package com.jarvis2.app.ui.settings

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

    Scaffold(topBar = { TopAppBar(title = { Text("Réglages", color = JarvisCyan) }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Moteur IA", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(state.engine?.displayName ?: "…")
            Text(state.engine?.notes.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Button(onClick = { viewModel.refreshEngine() }, modifier = Modifier.padding(top = 8.dp)) { Text("Ré-détecter") }
            Text(
                "Par défaut, Jarvis télécharge et utilise automatiquement SmolVLM2 (léger, texte + image, aucune connexion/compte requis) — pas besoin de faire quoi que ce soit ici pour ça.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Modèle IA local optionnel", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            Text(
                "Alternatives à SmolVLM2, aucune ne nécessite de compte ni de jeton (contrairement à " +
                    "l'ancien Gemma 3, verrouillé par Google) : le téléchargement démarre dès que tu " +
                    "choisis un modèle ci-dessous.",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(
                    selected = state.selectedLocalModel == "none",
                    onClick = { viewModel.setSelectedLocalModel("none") },
                    label = { Text("Aucun") },
                )
                LocalGgufModel.entries.forEach { model ->
                    FilterChip(
                        selected = state.selectedLocalModel == model.id,
                        onClick = { viewModel.setSelectedLocalModel(model.id) },
                        label = { Text(model.displayName) },
                    )
                }
            }
            if (state.isDownloadingLocalModel) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp), color = JarvisCyan, strokeWidth = 2.dp)
            }
            state.localModelDownloadError?.let { error ->
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
