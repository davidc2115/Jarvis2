package com.jarvis2.app.ui.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.obsidian.Graph
import com.jarvis2.app.obsidian.VaultRepository
import com.jarvis2.app.obsidian.buildGraph
import com.jarvis2.app.obsidian.relax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GraphUiState(val graph: Graph = Graph(emptyList(), emptyList()), val isLoading: Boolean = true)

class GraphViewModel(private val vaultRepository: VaultRepository) : ViewModel() {

    private val _state = MutableStateFlow(GraphUiState())
    val state: StateFlow<GraphUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val notes = vaultRepository.listNotes()
            val laidOut = withContext(Dispatchers.Default) { relax(buildGraph(notes)) }
            _state.value = GraphUiState(graph = laidOut, isLoading = false)
        }
    }
}
