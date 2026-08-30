package com.jarvis2.app.ui.vault

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.obsidian.Note
import com.jarvis2.app.obsidian.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VaultUiState(
    val notes: List<Note> = emptyList(),
    val selected: Note? = null,
    val isLoading: Boolean = true,
)

class VaultViewModel(private val vaultRepository: VaultRepository) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val notes = vaultRepository.listNotes()
            _state.value = _state.value.copy(notes = notes, isLoading = false)
        }
    }

    fun select(note: Note?) {
        _state.value = _state.value.copy(selected = note)
    }

    fun createNote(title: String) {
        viewModelScope.launch {
            val note = vaultRepository.createNote(title, body = "")
            refresh()
            select(note)
        }
    }

    fun saveNoteBody(note: Note, newBody: String) {
        viewModelScope.launch {
            vaultRepository.saveNote(note.copy(body = newBody))
            refresh()
        }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            vaultRepository.deleteNote(note.fileName)
            if (_state.value.selected == note) select(null)
            refresh()
        }
    }

    /** Item 2 de la roadmap README : pointer le vault vers un dossier externe choisi via SAF
     *  (ex. un dossier deja synchronise avec l'app Obsidian desktop/mobile via Syncthing). */
    fun setExternalVault(uri: Uri) {
        viewModelScope.launch {
            vaultRepository.setExternalVaultUri(uri)
            refresh()
        }
    }

    /** Revient au vault prive par defaut de l'app. */
    fun useLocalVault() {
        viewModelScope.launch {
            vaultRepository.clearExternalVault()
            refresh()
        }
    }
}
