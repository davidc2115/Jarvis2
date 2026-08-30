package com.jarvis2.app.ui.filetools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.filegen.FileGenRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class FileToolsUiState(val generatedFiles: List<File> = emptyList(), val lastMessage: String? = null)

class FileToolsViewModel(private val fileGen: FileGenRouter) : ViewModel() {

    private val _state = MutableStateFlow(FileToolsUiState())
    val state: StateFlow<FileToolsUiState> = _state.asStateFlow()

    fun generatePdf(title: String, body: String) = viewModelScope.launch {
        val file = fileGen.pdf.generateFromText(title, body)
        report(file)
    }

    fun generateDocx(title: String, body: String) = viewModelScope.launch {
        val file = fileGen.docx.generateFromText(title, body)
        report(file)
    }

    fun generateXlsx(title: String, rows: List<List<String>>) = viewModelScope.launch {
        val file = fileGen.xlsx.generateFromRows(title, rows)
        report(file)
    }

    fun generateKml(label: String, lat: Double, lon: Double) = viewModelScope.launch {
        val file = fileGen.kml.generatePlacemark(label, lat, lon)
        report(file)
    }

    fun zipGeneratedFiles(archiveName: String) = viewModelScope.launch {
        val file = fileGen.zip.zipFiles(_state.value.generatedFiles, archiveName)
        report(file)
    }

    private fun report(file: File) {
        _state.value = _state.value.copy(
            generatedFiles = _state.value.generatedFiles + file,
            lastMessage = "Créé: ${file.name}",
        )
    }
}
