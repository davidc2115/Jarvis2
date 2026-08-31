package com.jarvis2.app.integrations

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Levee quand Google exige une confirmation utilisateur (ecran systeme) avant de rendre un jeton d'acces. */
class GoogleAuthNeedsUserActionException(val intent: Intent) :
    Exception("Confirmation Google requise. Suis l'écran qui vient de s'ouvrir puis réessaie.")

/**
 * Authentification Google pour Gmail (scope lecture seule) via les comptes
 * DEJA synchronises sur le telephone (Reglages Android -> Comptes), au lieu
 * d'un ecran de connexion separe a l'interieur de Jarvis -- exactement ce
 * que fait deja CalendarRepository/ContactsRepository pour l'agenda et les
 * contacts (ContentResolver contre les fournisseurs systeme, deja
 * synchronises, sans OAuth). Utilise AccountManager pour lister les comptes
 * "com.google" existants et GoogleAuthUtil.getToken (partie de
 * com.google.android.gms:play-services-auth, deja une dependance) pour
 * obtenir le jeton -- l'API classique et eprouvee, PAS la nouvelle
 * Authorization API (Identity.getAuthorizationClient) utilisee avant ce
 * changement, qui provoquait systematiquement l'erreur "unregistered on API
 * console" cote utilisateur meme avec un client OAuth Android correctement
 * enregistre (l'Authorization API a des exigences d'enregistrement propres,
 * plus strictes/differentes de l'API classique).
 *
 * IMPORTANT, pour rester honnete envers l'utilisateur : Android n'expose
 * AUCUN ContentProvider public pour lire le contenu des messages Gmail --
 * contrairement au Calendrier ou aux Contacts, qui sont de vrais
 * fournisseurs systeme lisibles sans aucune authentification. Lire de vrais
 * mails Gmail necessite donc TOUJOURS, quelle que soit la methode, un jeton
 * OAuth avec le scope gmail.readonly, ce qui exige un projet Google Cloud
 * avec un client OAuth "Android" (package + empreinte SHA-1) et l'API
 * Gmail activee -- c'est une exigence de Google, pas un choix de conception
 * de l'appli. Ce changement supprime uniquement (1) l'ecran de connexion
 * Google *dans* Jarvis (le compte deja present sur le telephone est reutilise
 * directement) et (2) la dependance a l'Authorization API recente qui
 * semble etre la source de l'erreur persistante -- pas l'enregistrement
 * ponctuel du projet Google Cloud lui-meme, qui reste necessaire une fois.
 */
class GoogleAuthController(private val context: Context) {

    /** Comptes Google deja ajoutes sur le telephone -- aucune connexion separee necessaire dans Jarvis. */
    fun listAccounts(): List<Account> =
        runCatching { AccountManager.get(context).getAccountsByType("com.google").toList() }.getOrDefault(emptyList())

    /**
     * Jeton d'acces Gmail pour [accountEmail] (ou le premier compte Google
     * du telephone si non precise). Si Google exige une confirmation
     * (premiere utilisation, ou consentement revoque), leve
     * [GoogleAuthNeedsUserActionException] portant l'Intent que l'UI doit
     * lancer via ActivityResultContracts.StartActivityForResult ; il suffit
     * ensuite de rappeler cette meme fonction, aucun parsing de resultat
     * necessaire (contrairement a l'ancienne Authorization API).
     */
    suspend fun getAccessToken(accountEmail: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val accounts = listAccounts()
            if (accounts.isEmpty()) {
                error("Aucun compte Google sur ce téléphone. Ajoute-en un dans Réglages Android → Comptes, puis réessaie.")
            }
            val account = accountEmail?.let { email -> accounts.find { it.name.equals(email, ignoreCase = true) } }
                ?: accounts.first()
            try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$GMAIL_READONLY_SCOPE")
            } catch (e: UserRecoverableAuthException) {
                val recoveryIntent = e.intent
                    ?: error("Confirmation Google requise mais l'ecran systeme est indisponible. Reessaie depuis Reglages -> Comptes.")
                throw GoogleAuthNeedsUserActionException(recoveryIntent)
            }
        }
    }

    /** Verification legere pour l'affichage Reglages ("Connecté"/"Non connecté"). */
    suspend fun isConnected(): Boolean = getAccessToken().isSuccess

    private companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    }
}
