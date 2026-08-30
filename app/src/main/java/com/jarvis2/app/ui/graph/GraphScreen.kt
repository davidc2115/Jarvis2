package com.jarvis2.app.ui.graph

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jarvis2.app.ui.theme.JarvisCyan
import org.koin.androidx.compose.koinViewModel

/** Full interactive graph of every note, node, and link in the vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(viewModel: GraphViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Toile — ${state.graph.nodes.size} nœuds, ${state.graph.edges.size} liens", color = JarvisCyan) }) }) { padding ->
        if (state.isLoading) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = JarvisCyan)
            }
        } else {
            GraphView(graph = state.graph, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}
