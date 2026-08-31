package com.jarvis2.app.integrations

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Levee quand Google exige une action utilisateur (ecran de consentement) avant de rendre un jeton d'acces. */
class GoogleAuthNeedsUserActionException(val intentSender: android.content.IntentSender) :
    Exception("Aucun compte Google connecté pour Gmail. Va dans Réglages → Mail (Google) pour te connecter.")

/**
 * Authentification Google via l'Authorization API (Identity.getAuthorizationClient),
 * la voie officiellement recommandee par Google pour obtenir un jeton d'acces avec des
 * scopes precis (ici Gmail lecture seule) -- verifie via developer.android.com/identity/authorization :
 * Credential Manager (GetGoogleIdOption/GetSignInWithGoogleOption) ne rend qu'un jeton
 * d'IDENTITE, jamais un jeton d'acces avec scopes, donc inutilisable pour appeler l'API Gmail.
 *
 * Remplace l'ancienne integration IMAP (voir l'historique de MailReader.kt) a la demande
 * explicite de l'utilisateur, qui avait deja un projet Google Cloud + Client ID OAuth Web
 * pret (recupere plus haut dans la conversation).
 *
 * Le Client ID Web ci-dessous N'EST PAS un secret : c'est un identifiant public, verifie
 * par Google via le nom de package + l'empreinte SHA-1 du certificat de signature de
 * l'appli (enregistres separement comme client OAuth Android dans la meme console Google
 * Cloud) -- exactement comme un google-services.json, qui est routinement commite meme
 * dans des depots publics.
 */
class GoogleAuthController(private val context: Context) {

    private val authorizationRequest: AuthorizationRequest by lazy {
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
            .build()
    }

    /**
     * Tente d'obtenir un jeton d'acces Gmail. Si l'utilisateur a deja accorde l'acces
     * precedemment, ceci reussit silencieusement (aucune UI) -- c'est le chemin normal
     * emprunte a chaque lecture de mail, le jeton (duree de vie ~1h) etant redemande a
     * chaque appel plutot que mis en cache manuellement (Google gere ca en interne).
     * Si aucun consentement n'a encore ete donne (ou a ete revoque), leve
     * [GoogleAuthNeedsUserActionException] portant l'IntentSender que l'UI doit lancer.
     */
    suspend fun getAccessToken(): Result<String> = runCatching {
        val result = authorize()
        if (result.hasResolution()) {
            val intentSender = result.pendingIntent?.intentSender
                ?: throw IllegalStateException("Consentement Google requis mais aucun IntentSender fourni.")
            throw GoogleAuthNeedsUserActionException(intentSender)
        }
        result.accessToken ?: throw IllegalStateException("Google n'a retourné aucun jeton d'accès.")
    }

    /**
     * Verification legere pour l'affichage Reglages ("Connecté"/"Non connecté") --
     * mappe simplement [getAccessToken] sur un booleen, sans propager l'erreur.
     */
    suspend fun isConnected(): Boolean = getAccessToken().isSuccess

    private suspend fun authorize(): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context)
            .authorize(authorizationRequest)
            .addOnSuccessListener { result -> cont.resume(result) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    /**
     * Termine le flux apres que l'UI a lance l'IntentSender de
     * [GoogleAuthNeedsUserActionException] et recu son resultat via
     * ActivityResultContracts.StartIntentSenderForResult.
     */
    fun parseAuthorizationResult(data: Intent?): Result<String> = runCatching {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        result.accessToken ?: throw IllegalStateException("Google n'a retourné aucun jeton d'accès après consentement.")
    }

    private companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    }
}
