package com.jarvis2.app.ai

import com.jarvis2.app.filegen.FileGenRouter
import com.jarvis2.app.integrations.IntegrationsRouter

/** Outcome of trying to interpret a message as a device action rather than plain chat. */
sealed interface CommandResult {
    data class Handled(val feedback: String) : CommandResult
    data class NeedsPermission(val permission: String, val feedback: String) : CommandResult
    data object NotACommand : CommandResult
}

/**
 * Lightweight, fully-local rule-based intent router that runs *before* the
 * LLM sees the message. This is what makes "allume la torche" instantly
 * reliable instead of depending on a 1-3B parameter model to emit a
 * perfectly-formatted function call every time. Patterns are French-first
 * (matching the brief) with a few English synonyms.
 *
 * If nothing matches, [route] returns [CommandResult.NotACommand] and the
 * caller (ChatViewModel) forwards the message to the LLM as normal
 * conversation instead.
 */
class CommandRouter(
    private val integrations: IntegrationsRouter,
    private val fileGen: FileGenRouter,
) {

    suspend fun route(rawInput: String): CommandResult {
        val text = rawInput.trim().lowercase()

        matchers.forEach { matcher ->
            if (matcher.pattern.containsMatchIn(text)) {
                return matcher.action(text)
            }
        }
        return CommandResult.NotACommand
    }

    private data class Matcher(val pattern: Regex, val action: suspend (String) -> CommandResult)

    private val matchers: List<Matcher> by lazy {
        listOf(
            Matcher(Regex("(allume|active).*(torche|lampe|flash)")) {
                integrations.flashlight.setTorch(true)
                CommandResult.Handled("Torche activée.")
            },
            Matcher(Regex("(éteins|desactive|désactive).*(torche|lampe|flash)")) {
                integrations.flashlight.setTorch(false)
                CommandResult.Handled("Torche éteinte.")
            },
            Matcher(Regex("(active|ouvre).*bluetooth")) {
                integrations.bluetooth.requestEnable()
                CommandResult.Handled("Ouverture des réglages Bluetooth pour activation.")
            },
            Matcher(Regex("(désactive|coupe).*bluetooth")) {
                integrations.bluetooth.requestDisable()
                CommandResult.Handled("Ouverture des réglages Bluetooth pour désactivation.")
            },
            Matcher(Regex("(active|ouvre).*(wi-?fi)")) {
                integrations.wifi.openWifiSettingsPanel()
                CommandResult.Handled("Panneau Wi-Fi ouvert (Android impose ce panneau depuis Android 10, l'app ne peut plus basculer le Wi-Fi silencieusement).")
            },
            Matcher(Regex("(où suis-je|ma position|localise-moi|gps)")) {
                val loc = integrations.location.lastKnownLocation()
                if (loc == null) CommandResult.Handled("Position indisponible pour le moment (GPS/permission).")
                else CommandResult.Handled("Position: ${loc.latitude}, ${loc.longitude} (précision ${loc.accuracy}m).")
            },
            Matcher(Regex("(crée|ajoute|planifie).*(rendez-vous|événement|evenement|rappel|réunion)")) { t ->
                val title = extractAfter(t, listOf("rendez-vous", "événement", "evenement", "rappel", "réunion")) ?: "Nouvel événement Jarvis"
                val eventId = integrations.calendar.createEvent(title = title, startTimeMillis = System.currentTimeMillis() + 3600_000)
                CommandResult.Handled("Événement \"$title\" créé dans l'agenda (id $eventId).")
            },
            Matcher(Regex("(crée|ajoute).*contact")) { t ->
                val name = extractAfter(t, listOf("contact")) ?: "Nouveau contact"
                integrations.contacts.createContact(name)
                CommandResult.Handled("Contact \"$name\" créé.")
            },
            Matcher(Regex("(génère|crée|exporte).*pdf")) { t ->
                val file = fileGen.pdf.generateFromText(title = "Document Jarvis", body = t)
                CommandResult.Handled("PDF généré: ${file.name}.")
            },
            Matcher(Regex("(génère|crée|exporte).*(zip|archive)")) {
                CommandResult.Handled("Dis-moi quel dossier zipper depuis l'écran Fichiers — je n'archive pas encore par commande vocale seule pour éviter de compresser le mauvais dossier.")
            },
            Matcher(Regex("(génère|crée|exporte).*(kml|carte)")) { t ->
                val file = fileGen.kml.generateFromCurrentLocation(
                    label = "Point Jarvis",
                    location = integrations.location.lastKnownLocation(),
                )
                if (file == null) CommandResult.Handled("Position GPS indisponible, impossible de générer le KML.")
                else CommandResult.Handled("Fichier KML généré: ${file.name}.")
            },
            Matcher(Regex("(envoie|rédige|compose).*mail")) { t ->
                val subject = extractAfter(t, listOf("mail", "email", "courriel")) ?: "Message depuis Jarvis"
                integrations.mail.composeMail(subject = subject, body = "")
                CommandResult.Handled("Ouverture de ton application mail avec le sujet \"$subject\".")
            },
        )
    }

    private fun extractAfter(text: String, keywords: List<String>): String? {
        for (kw in keywords) {
            val idx = text.indexOf(kw)
            if (idx >= 0) {
                val rest = text.substring(idx + kw.length).trim().removePrefix(":").trim()
                if (rest.isNotBlank()) return rest.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }
}
