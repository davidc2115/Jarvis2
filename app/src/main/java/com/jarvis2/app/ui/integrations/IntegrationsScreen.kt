package com.jarvis2.app.ui.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis2.app.integrations.CalendarEvent
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(viewModel: IntegrationsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Scaffold(topBar = { TopAppBar(title = { Text("Contrôle téléphone", color = JarvisCyan) }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            ToggleRow(label = "Torche", checked = state.torchOn, onToggle = { viewModel.toggleTorch() })
            ActionRow(label = "Bluetooth (${if (state.bluetoothEnabled) "activé" else "désactivé"})", actionLabel = "Réglages") {
                viewModel.openBluetoothSettings()
            }
            ActionRow(label = "Wi-Fi (${if (state.wifiEnabled) "activé" else "désactivé"})", actionLabel = "Panneau") {
                viewModel.openWifiPanel()
            }
            Divider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Prochains événements", style = MaterialTheme.typography.titleLarge, color = JarvisGold)
            LazyColumn {
                items(state.upcomingEvents) { event -> EventRow(event, dateFormat) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun ActionRow(label: String, actionLabel: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClick) { Text(actionLabel, color = JarvisCyan) }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, dateFormat: SimpleDateFormat) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            Text(dateFormat.format(Date(event.startMillis)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

