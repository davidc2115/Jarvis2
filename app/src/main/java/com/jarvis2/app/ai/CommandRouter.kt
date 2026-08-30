package com.jarvis2.app.ai

import com.jarvis2.app.filegen.FileGenRouter
import com.jarvis2.app.integrations.IntegrationsRouter
import com.jarvis2.app.obsidian.VaultRepository

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
 *
 * Coverage was extended beyond the original "action-only" set (torche,
 * bluetooth, wifi, agenda create, contact create, pdf, kml, mail) to also
 * cover *reading back* what's on the phone -- contacts, agenda, vault notes,
 * memory -- and generating Word/Excel, since a real "gestion complete du
 * smartphone" needs both directions, not just triggering actions blindly.
 */
class CommandRouter(
    private val integrations: IntegrationsRouter,
    private val fileGen: FileGenRouter,
    private val vault: VaultRepository,
    private val memory: MemoryStore,
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
            Matcher(Regex("(liste|montre|quels?).*(appareils?|appairés?).*bluetooth|bluetooth.*(appareils?|appairés?)")) {
                val devices = integrations.bluetooth.pairedDevices()
                if (devices.isEmpty()) CommandResult.Handled("Aucun appareil Bluetooth appairé (ou permission Bluetooth non accordée).")
                else CommandResult.Handled("Appareils appairés : " + devices.joinToString(", ") { it.name ?: it.address })
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
            Matcher(Regex("(mes|mon).*(prochains? événements?|prochains? rendez-vous|planning|agenda)")) {
                val events = integrations.calendar.upcomingEvents(limit = 5)
                if (events.isEmpty()) CommandResult.Handled("Aucun événement à venir dans l'agenda.")
                else CommandResult.Handled("Prochains événements : " + events.joinToString(" ; ") { it.title })
            },
            Matcher(Regex("(crée|ajoute).*contact")) { t ->
                val name = extractAfter(t, listOf("contact")) ?: "Nouveau contact"
                integrations.contacts.createContact(name)
                CommandResult.Handled("Contact \"$name\" créé.")
            },
            Matcher(Regex("(liste|montre|affiche).*(mes )?contacts")) {
                val contacts = integrations.contacts.listContacts(limit = 15)
                if (contacts.isEmpty()) CommandResult.Handled("Aucun contact trouvé (ou permission Contacts non accordée).")
                else CommandResult.Handled("Contacts (${contacts.size}) : " + contacts.joinToString(", ") { it.name })
            },
            Matcher(Regex("cherche.*contact")) { t ->
                val query = extractAfter(t, listOf("contact"))
                if (query == null) {
                    CommandResult.Handled("Précise un nom à chercher, par exemple \"cherche le contact Marie\".")
                } else {
                    val matches = integrations.contacts.listContacts(limit = 200).filter { it.name.contains(query, ignoreCase = true) }
                    if (matches.isEmpty()) CommandResult.Handled("Aucun contact trouvé pour « $query ».")
                    else CommandResult.Handled("Trouvé : " + matches.joinToString(", ") { it.name })
                }
            },
            Matcher(Regex("(génère|crée|exporte).*pdf")) { t ->
                val file = fileGen.pdf.generateFromText(title = "Document Jarvis", body = t)
                CommandResult.Handled("PDF généré: ${file.name}.")
            },
            Matcher(Regex("(génère|crée|exporte).*(word|docx|document texte)")) { t ->
                val file = fileGen.docx.generateFromText(title = "Document Jarvis", body = t)
                CommandResult.Handled("Document Word généré: ${file.name}.")
            },
            Matcher(Regex("(génère|crée|exporte).*(excel|xlsx|tableur)")) { t ->
                val file = fileGen.xlsx.generateFromRows("Jarvis", listOf(listOf("Contenu"), listOf(t)))
                CommandResult.Handled("Fichier Excel généré: ${file.name} (structure minimale -- utilise l'onglet Fichiers pour un vrai tableau à plusieurs colonnes).")
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
            Matcher(Regex("(crée|ajoute|nouvelle).*(note).*(vault|obsidian)|(vault|obsidian).*(crée|ajoute|nouvelle).*note")) { t ->
                val titleGuess = extractAfter(t, listOf("intitulée", "appelée", "titre", "titrée")) ?: "Note Jarvis"
                val note = vault.createNote(titleGuess, body = t)
                CommandResult.Handled("Note « ${note.title} » créée dans le vault Obsidian.")
            },
            Matcher(Regex("cherche.*(vault|obsidian)|cherche.*note")) { t ->
                val query = extractAfter(t, listOf("vault", "obsidian", "note"))
                if (query == null) {
                    CommandResult.Handled("Précise ce que tu cherches dans le vault, par exemple \"cherche dans le vault projet X\".")
                } else {
                    val matches = vault.listNotes().filter {
                        it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
                    }
                    if (matches.isEmpty()) CommandResult.Handled("Aucune note trouvée pour « $query ».")
                    else CommandResult.Handled("Notes trouvées : " + matches.joinToString(", ") { it.title })
                }
            },
            Matcher(Regex("(liste|montre).*(mes )?notes")) {
                val notes = vault.listNotes()
                if (notes.isEmpty()) CommandResult.Handled("Le vault est vide pour l'instant.")
                else CommandResult.Handled("Notes du vault (${notes.size}) : " + notes.joinToString(", ") { it.title })
            },
            Matcher(Regex("(tu te souviens|de quoi (a-t-on|on a) parlé|rappelle-moi ce qu)")) { t ->
                val relevant = memory.relevant(t)
                if (relevant.isEmpty()) CommandResult.Handled("Rien de particulier en mémoire à ce sujet.")
                else CommandResult.Handled("Je me souviens : " + relevant.joinToString(" | ") { it.text.take(80) })
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
