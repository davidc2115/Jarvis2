package com.jarvis2.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jarvis2.app.ui.Jarvis2NavHost
import com.jarvis2.app.ui.permissions.PermissionsGate
import com.jarvis2.app.ui.theme.Jarvis2Theme

/**
 * Launcher activity. Le manifest declare .MainActivity depuis le debut du projet mais cette
 * classe n'existait encore nulle part dans le code source -- l'app plantait donc au tout
 * premier lancement (ClassNotFoundException levee par le systeme au moment de resoudre
 * l'activite LAUNCHER, avant meme d'executer la moindre ligne de code applicatif). Cette
 * classe se contente d'installer le theme HUD (Jarvis2Theme) puis la navigation a onglets
 * deja ecrite dans ui/NavGraph.kt (Chat/Vault/Toile/Telephone/Reglages) -- aucune autre piece
 * ne manquait pour que l'app demarre.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Jarvis2Theme {
                PermissionsGate {
                    Jarvis2NavHost()
                }
            }
        }
    }
}
