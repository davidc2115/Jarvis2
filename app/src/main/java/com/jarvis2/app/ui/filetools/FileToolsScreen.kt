package com.jarvis2.app.ui.filetools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.jarvis2.app.ui.theme.JarvisCyan
import org.koin.androidx.compose.koinViewModel

/** Manual creation of PDF/DOCX/XLSX/KML/ZIP — the same generators the chat's CommandRouter calls, exposed directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileToolsScreen(viewModel: FileToolsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var title by remember { mutableStateOf("Document Jarvis") }
    var body by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Fichiers", color = JarvisCyan) }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = body, onValueChange = { body = it }, label = { Text("Contenu") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minLines = 4,
            )
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = { viewModel.generatePdf(title, body) }, modifier = Modifier.fillMaxWidth()) { Text("Générer PDF") }
                Button(onClick = { viewModel.generateDocx(title, body) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Générer DOCX") }
                Button(
                    onClick = { viewModel.generateXlsx(title, listOf(listOf("Colonne A", "Colonne B"), body.split(" ").take(2))) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Générer XLSX (démo)") }
                Button(onClick = { viewModel.zipGeneratedFiles("${title}_export") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Zipper les fichiers générés")
                }
            }

            state.lastMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = JarvisCyan) }

            Text("Fichiers générés cette session", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            LazyColumn {
                items(state.generatedFiles) { file -> Text("• ${file.name}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
