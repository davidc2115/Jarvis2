package com.jarvis2.app.ui.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis2.app.integrations.CalendarEvent
import com.jarvis2.app.integrations.IntegrationsRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IntegrationsUiState(
    val torchOn: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val upcomingEvents: List<CalendarEvent> = emptyList(),
)

class IntegrationsViewModel(private val integrations: IntegrationsRouter) : ViewModel() {

    private val _state = MutableStateFlow(IntegrationsUiState())
    val state: StateFlow<IntegrationsUiState> = _state.asStateFlow()

    init { refreshStatus() }

    fun refreshStatus() {
        _state.value = _state.value.copy(
            bluetoothEnabled = integrations.bluetooth.isEnabled(),
            wifiEnabled = integrations.wifi.isEnabled(),
        )
        viewModelScope.launch {
            _state.value = _state.value.copy(upcomingEvents = integrations.calendar.upcomingEvents())
        }
    }

    fun toggleTorch() {
        val newState = !_state.value.torchOn
        if (integrations.flashlight.setTorch(newState)) {
            _state.value = _state.value.copy(torchOn = newState)
        }
    }

    fun openBluetoothSettings() = integrations.bluetooth.requestEnable()
    fun openWifiPanel() = integrations.wifi.openWifiSettingsPanel()

    fun createQuickEvent(title: String) {
        viewModelScope.launch {
            integrations.calendar.createEvent(title, System.currentTimeMillis() + 3_600_000)
            refreshStatus()
        }
    }
}
