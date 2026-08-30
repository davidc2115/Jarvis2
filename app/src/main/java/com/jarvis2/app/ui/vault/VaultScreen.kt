package com.jarvis2.app.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis2.app.obsidian.Note
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import org.koin.androidx.compose.koinViewModel

/** Vault note list + a simple editor for the selected note's body. Full-text markdown, real Obsidian frontmatter/tags/links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(viewModel: VaultViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var showNewNoteDialog by remember { mutableStateOf(false) }
    var newNoteTitle by remember { mutableStateOf("") }

    val selected = state.selected
    if (selected != null) {
        NoteEditor(
            note = selected,
            onBack = { viewModel.select(null) },
            onSave = { body -> viewModel.saveNoteBody(selected, body) },
            onDelete = { viewModel.deleteNote(selected) },
        )
        return
    }

    // Item 2 de la roadmap README : selecteur de dossier vault externe (SAF), pour pointer
    // vers un vault deja synchronise (Syncthing, etc.) au lieu du dossier prive par defaut de
    // l'app -- VaultRepository/StorageAccess geraient deja ce cas, il ne manquait que ce bouton.
    val pickVaultFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setExternalVault(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault Obsidian", color = JarvisCyan) },
                actions = {
                    TextButton(onClick = { pickVaultFolder.launch(null) }) { Text("📁 Externe", color = JarvisCyan) }
                    TextButton(onClick = { viewModel.useLocalVault() }) { Text("Local", color = JarvisGold) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewNoteDialog = true }, containerColor = JarvisGold) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle note")
            }
        },
    ) { padding ->
        if (showNewNoteDialog) {
            Surface(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
                Column {
                    OutlinedTextField(
                        value = newNoteTitle,
                        onValueChange = { newNoteTitle = it },
                        label = { Text("Titre de la note") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(onClick = { showNewNoteDialog = false }) { Text("Annuler") }
                        TextButton(onClick = {
                            if (newNoteTitle.isNotBlank()) {
                                viewModel.createNote(newNoteTitle)
                                newNoteTitle = ""
                            }
                            showNewNoteDialog = false
                        }) { Text("Créer") }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), modifier = Modifier.padding(padding)) {
                items(state.notes, key = { it.fileName }) { note ->
                    NoteRow(note, onClick = { viewModel.select(note) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleLarge)
            if (note.tags.isNotEmpty()) {
                Text("#${note.tags.joinToString(" #")}", style = MaterialTheme.typography.labelSmall, color = JarvisGold)
            }
            if (note.links.isNotEmpty()) {
                Text("${note.links.size} lien(s)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(note: Note, onBack: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var body by remember(note.fileName) { mutableStateOf(note.body) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Retour") } },
                actions = {
                    TextButton(onClick = { onSave(body) }) { Text("Enregistrer", color = JarvisCyan) }
                    TextButton(onClick = { onDelete(); onBack() }) { Text("Supprimer") }
                },
            )
        },
    ) { padding ->
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            placeholder = { Text("Écris en Markdown : [[liens]], #tags, ...") },
        )
    }
}
