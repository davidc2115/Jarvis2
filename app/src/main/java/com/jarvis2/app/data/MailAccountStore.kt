package com.jarvis2.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Un compte mail IMAP (voir integrations/MailReader.kt). */
data class MailAccount(
    val host: String,
    val port: Int,
    val username: String,
    val appPassword: String,
    val useSsl: Boolean = true,
)

/**
 * Stocke le compte mail IMAP de l'utilisateur (host/port/utilisateur/mot de
 * passe d'application) via EncryptedSharedPreferences plutot que le
 * SettingsDataStore classique (voir sa propre doc de classe : "pour
 * n'importe quoi de sensible ... route-le via EncryptedSharedPreferences
 * plutot") -- un mot de passe d'application est un vrai identifiant, pas une
 * simple preference d'UI comme une couleur de bulle.
 *
 * IMAP a ete choisi plutot que l'API Gmail/OAuth : Claude ne peut pas creer
 * de projet Google Cloud ni de client OAuth pour le compte de l'utilisateur
 * (voir contraintes standard de la session), alors qu'IMAP fonctionne avec
 * n'importe quel fournisseur, y compris Gmail lui-meme via un "mot de passe
 * d'application" genere dans les parametres du compte Google de
 * l'utilisateur (necessite la validation en 2 etapes) -- jamais le mot de
 * passe principal du compte.
 *
 * Note : androidx.security:security-crypto a ete marque deprecie par Google
 * en version 1.1.0-alpha07 (avril 2025) au profit de DataStore+Tink. Ce
 * projet reste fige sur 1.1.0-alpha06 (la derniere version avant cet avis),
 * qui continue de fonctionner a l'identique -- rien ne casse ici, c'est
 * juste un point a garder en tete pour une future migration si jamais cette
 * dependance est mise a jour.
 */
class MailAccountStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jarvis2_mail_account",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): MailAccount? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        val useSsl = prefs.getBoolean(KEY_SSL, true)
        return MailAccount(host, port, username, password, useSsl)
    }

    fun save(account: MailAccount) {
        prefs.edit()
            .putString(KEY_HOST, account.host)
            .putInt(KEY_PORT, account.port)
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_PASSWORD, account.appPassword)
            .putBoolean(KEY_SSL, account.useSsl)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "app_password"
        const val KEY_SSL = "use_ssl"
        const val DEFAULT_PORT = 993
    }
}
