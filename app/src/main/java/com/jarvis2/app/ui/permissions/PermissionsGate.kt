package com.jarvis2.app.ui.permissions

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold

/**
 * Toutes les integrations telephone (integrations/*) supposent deja la permission
 * accordee -- rien ne la demandait nulle part avant cet ecran, donc en pratique
 * chaque action reelle echouait silencieusement ou levait une SecurityException des
 * la premiere utilisation sur un vrai appareil (Android exige une demande runtime
 * explicite pour toute permission "dangereuse" depuis l'API 23, la declaration dans
 * le manifest seule ne suffit jamais). Cet ecran regroupe la demande une seule fois
 * au lancement -- style HUD "autorisation d'acces aux systemes" plutot qu'une
 * cascade de popups systeme deroutante une par une.
 */
private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.WRITE_CONTACTS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsGate(content: @Composable () -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(requiredPermissions())
    var skipped by rememberSaveable { mutableStateOf(false) }

    if (permissionsState.allPermissionsGranted || skipped) {
        content()
        return
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Autorisations", color = JarvisCyan) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Jarvis a besoin de ces acces pour gerer ton telephone (torche, Bluetooth, agenda, contacts, position, notifications). Tout reste 100% local -- rien n'est envoye nulle part.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(permissionsState.permissions) { perm ->
                    Text(
                        "• ${permissionLabel(perm.permission)} — ${if (perm.status.isGranted) "accordee" else "en attente"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            Button(
                onClick = { permissionsState.launchMultiplePermissionRequest() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Autoriser") }
            TextButton(onClick = { skipped = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Continuer sans tout autoriser (certaines actions ne marcheront pas)", color = JarvisGold)
            }
        }
    }
}

private fun permissionLabel(permission: String): String = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "Position (GPS)"
    Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "Agenda"
    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS -> "Contacts"
    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "Stockage"
    else -> permission.substringAfterLast('.')
}
