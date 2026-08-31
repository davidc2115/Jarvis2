package com.jarvis2.app.ai

import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.filegen.FileGenRouter
import com.jarvis2.app.integrations.CalendarEvent
import com.jarvis2.app.integrations.Contact
import com.jarvis2.app.integrations.IntegrationsRouter
import com.jarvis2.app.integrations.MailSummary
import com.jarvis2.app.obsidian.VaultRepository
import com.jarvis2.app.ui.settings.BUBBLE_ASSISTANT_COLOR
import com.jarvis2.app.ui.settings.BUBBLE_SHAPE
import com.jarvis2.app.ui.settings.BUBBLE_USER_COLOR
import com.jarvis2.app.ui.settings.CALENDAR_GROUP_BY_DAY
import com.jarvis2.app.ui.settings.CONTACT_PRESENTATION_STYLE
import com.jarvis2.app.ui.settings.WEB_SEARCH_PRESENTATION_STYLE
import com.jarvis2.app.ui.theme.BubbleStyle

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
    private val settings: SettingsDataStore,
) {

    private companion object {
        val FRENCH_UNITS = mapOf(
            "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
            "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
        )
        val FRENCH_TEENS = mapOf(
            "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14,
            "quinze" to 15, "seize" to 16,
        )
        val FRENCH_TENS = mapOf(
            "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
        )
    }

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
            // --- Reglages de presentation pilotables depuis le chat (voir
            // ui/settings/SettingsScreen.kt pour les memes reglages via UI).
            // Places en tete de liste : sans ca, des phrases comme "regroupe
            // mon planning par jour" ou "contacts en detaille" seraient
            // interceptees par les matchers de LECTURE plus generiques
            // (agenda/contacts) plus bas, qui ne font que consulter.
            Matcher(Regex("""bulles?.*(arrondies?|carr[ée]e?s?|pilule)""")) { t ->
                val shape = when {
                    Regex("pilule").containsMatchIn(t) -> "pill"
                    Regex("carr[ée]e?s?").containsMatchIn(t) -> "square"
                    else -> "rounded"
                }
                settings.set(BUBBLE_SHAPE, shape)
                CommandResult.Handled("Forme des bulles changée : ${BubbleStyle.shapeLabel(shape)}.")
            },
            Matcher(Regex("""couleur.*(mes|moi).*(bulles?|messages?)|(mes|moi).*(bulles?|messages?).*couleur""")) { t ->
                setBubbleColorFromText(t, forUser = true)
            },
            Matcher(Regex("""couleur.*(jarvis|tes|assistant).*(bulles?|messages?)|(jarvis|tes|assistant).*(bulles?|messages?).*couleur""")) { t ->
                setBubbleColorFromText(t, forUser = false)
            },
            Matcher(Regex("""planning.*(regroupe?|group[ée]).*jour|regroupe.*planning.*jour""")) {
                settings.set(CALENDAR_GROUP_BY_DAY, "true")
                CommandResult.Handled("Planning regroupé par jour.")
            },
            Matcher(Regex("""planning.*(liste simple|à plat|a plat|sans regroupement)""")) {
                settings.set(CALENDAR_GROUP_BY_DAY, "false")
                CommandResult.Handled("Planning affiché en liste simple.")
            },
            Matcher(Regex("""contacts?.*(détaillé|detaille|détail|detail|avec.*numéro|avec.*numero)""")) {
                settings.set(CONTACT_PRESENTATION_STYLE, "detailed")
                CommandResult.Handled("Présentation des contacts : détaillée (avec numéro).")
            },
            Matcher(Regex("""contacts?.*(compact|sans détail|sans detail)""")) {
                settings.set(CONTACT_PRESENTATION_STYLE, "compact")
                CommandResult.Handled("Présentation des contacts : compacte.")
            },
            Matcher(Regex("""(recherche web|résultats? web|resultats? web).*(détaillé|detaille|détail|detail)""")) {
                settings.set(WEB_SEARCH_PRESENTATION_STYLE, "detailed")
                CommandResult.Handled("Présentation des résultats de recherche web : détaillée.")
            },
            Matcher(Regex("""(recherche web|résultats? web|resultats? web).*(compact|simple)""")) {
                settings.set(WEB_SEARCH_PRESENTATION_STYLE, "compact")
                CommandResult.Handled("Présentation des résultats de recherche web : compacte.")
            },
            Matcher(Regex("(allume|active).*(torche|lampe|flash)")) {
                integrations.flashlight.setTorch(true)
                CommandResult.Handled("Torche activée.")
            },
            Matcher(Regex("(éteins|desactive|désactive).*(torche|lampe|flash)")) {
                integrations.flashlight.setTorch(false)
                CommandResult.Handled("Torche éteinte.")
            },
            Matcher(Regex("(reveil|réveil|alarme|reveille-moi|réveille-moi)")) { t ->
                val time = extractTime(t)
                if (time == null) {
                    CommandResult.Handled("Précise l'heure du réveil, par exemple \"mets un réveil à 7h30\".")
                } else {
                    val (hour, minute) = time
                    val label = extractAfter(t, listOf("pour", "intitulé", "intitule", "appelé", "appele"))
                    val ok = integrations.alarm.setAlarm(hour, minute, label)
                    val timeLabel = "${hour}h${minute.toString().padStart(2, '0')}"
                    if (ok) CommandResult.Handled("Réveil réglé à $timeLabel.")
                    else CommandResult.Handled("Impossible de régler le réveil (aucune application Horloge trouvée, ou permission refusée).")
                }
            },
            Matcher(Regex("(minuteur|minuterie|compte a rebours|compte à rebours|chronometre|chronomètre)")) { t ->
                val seconds = extractDurationSeconds(t)
                if (seconds == null || seconds <= 0) {
                    CommandResult.Handled("Précise la durée du minuteur, par exemple \"minuteur de 10 minutes\".")
                } else {
                    val label = extractAfter(t, listOf("pour", "intitulé", "intitule", "appelé", "appele"))
                    val ok = integrations.alarm.setTimer(seconds, label)
                    if (ok) CommandResult.Handled("Minuteur de ${formatDuration(seconds)} lancé.")
                    else CommandResult.Handled("Impossible de lancer le minuteur (aucune application Horloge trouvée, ou permission refusée).")
                }
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
                val events = integrations.calendar.upcomingEvents(limit = 10)
                if (events.isEmpty()) {
                    CommandResult.Handled("Aucun événement à venir dans l'agenda.")
                } else {
                    val groupByDay = (settings.get(CALENDAR_GROUP_BY_DAY) ?: "true") == "true"
                    CommandResult.Handled(formatEvents(events, groupByDay))
                }
            },
            Matcher(Regex("(crée|ajoute).*contact")) { t ->
                val name = extractAfter(t, listOf("contact")) ?: "Nouveau contact"
                integrations.contacts.createContact(name)
                CommandResult.Handled("Contact \"$name\" créé.")
            },
            Matcher(Regex("(liste|montre|affiche).*(mes )?contacts")) {
                val contacts = integrations.contacts.listContacts(limit = 15)
                if (contacts.isEmpty()) {
                    CommandResult.Handled("Aucun contact trouvé (ou permission Contacts non accordée).")
                } else {
                    val detailed = (settings.get(CONTACT_PRESENTATION_STYLE) ?: "compact") == "detailed"
                    CommandResult.Handled(formatContacts(contacts, detailed))
                }
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
            Matcher(Regex("""(lis|montre|affiche).*mails?|(derniers?|nouveaux?) mails?|mails? non lus?""")) { t ->
                if (!integrations.mailReader.isConfigured()) {
                    CommandResult.Handled(
                        "Aucun compte mail configuré. Va dans Réglages → Mail pour renseigner " +
                            "hôte IMAP, port, utilisateur et mot de passe d'application.",
                    )
                } else {
                    val unreadOnly = Regex("non lus?").containsMatchIn(t)
                    val result = integrations.mailReader.fetchRecent(limit = 10, unreadOnly = unreadOnly)
                    result.fold(
                        onSuccess = { mails -> CommandResult.Handled(formatMails(mails)) },
                        onFailure = { e -> CommandResult.Handled("Impossible de lire les mails : ${e.message}") },
                    )
                }
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
            Matcher(Regex("(renomme).*note")) { t ->
                val pair = extractRenamePair(t)
                if (pair == null) {
                    CommandResult.Handled("Précise ainsi : \"renomme la note Courses en Liste de courses\".")
                } else {
                    val (oldName, newName) = pair
                    val existing = vault.findByTitleOrFileName(oldName)
                    if (existing == null) {
                        CommandResult.Handled("Aucune note trouvée pour « $oldName ».")
                    } else {
                        val ok = vault.renameNote(existing.fileName, newName)
                        if (ok) CommandResult.Handled("Note « $oldName » renommée en « $newName ».")
                        else CommandResult.Handled("Échec du renommage de « $oldName ».")
                    }
                }
            },
            Matcher(Regex("(supprime|efface|retire).*note")) { t ->
                val query = extractAfter(t, listOf("note"))
                if (query == null) {
                    CommandResult.Handled("Précise quelle note supprimer, par exemple \"supprime la note Courses\".")
                } else {
                    val existing = vault.findByTitleOrFileName(query)
                    if (existing == null) {
                        CommandResult.Handled("Aucune note trouvée pour « $query ».")
                    } else {
                        vault.deleteNote(existing.fileName)
                        CommandResult.Handled("Note « ${existing.title} » supprimée.")
                    }
                }
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

    /** Detecte une heure du type "7h30", "7h", "19:45", "midi", "minuit". */
    private fun extractTime(text: String): Pair<Int, Int>? {
        val normalized = normalizeFrenchNumberWords(text)
        Regex("""(\d{1,2})\s*[h:]\s*(\d{1,2})?""").find(normalized)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull()
            val minute = m.groupValues[2].ifBlank { "0" }.toIntOrNull()
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) return hour to minute
        }
        if (Regex("""\bmidi\b""").containsMatchIn(text)) return 12 to 0
        if (Regex("""\bminuit\b""").containsMatchIn(text)) return 0 to 0
        return null
    }

    /** Additionne heures/minutes/secondes mentionnees (n'importe quel sous-ensemble) en secondes totales. */
    private fun extractDurationSeconds(text: String): Int? {
        val normalized = normalizeFrenchNumberWords(text)
        var total = 0
        var found = false
        Regex("""(\d+)\s*(heures?|h\b)""").find(normalized)?.let {
            total += it.groupValues[1].toInt() * 3600
            found = true
        }
        Regex("""(\d+)\s*(minutes?|min\b)""").find(normalized)?.let {
            total += it.groupValues[1].toInt() * 60
            found = true
        }
        Regex("""(\d+)\s*(secondes?|sec\b)""").find(normalized)?.let {
            total += it.groupValues[1].toInt()
            found = true
        }
        return if (found) total else null
    }

    /**
     * Convertit les nombres ecrits en toutes lettres (0-69, ce qui couvre les
     * durees/heures realistes d'un minuteur ou d'un reveil) en chiffres, pour
     * qu'extractDurationSeconds/extractTime les reconnaissent aussi bien que
     * "10". Necessaire car la dictee vocale Android ne convertit pas toujours
     * les nombres parles en chiffres -- c'etait la cause du bug ou le
     * minuteur redemandait une duree meme quand l'utilisateur en donnait une
     * ("minuteur de dix minutes" ne contenait alors aucun chiffre).
     *
     * Limitation connue : ne couvre pas 70-99 (soixante-dix / quatre-vingts,
     * constructions irregulieres) -- non prioritaire pour des durees de
     * minuteur/reveil, ou les utilisateurs restent quasi toujours sous une
     * heure/soixante minutes.
     */
    private fun normalizeFrenchNumberWords(text: String): String {
        var result = " " + text.replace('-', ' ') + " "

        // Dix-sept / dix-huit / dix-neuf doivent etre convertis avant que la
        // passe "dix" seul (valeur 10) ne s'execute plus bas.
        listOf(17 to "dix sept", 18 to "dix huit", 19 to "dix neuf").forEach { (value, phrase) ->
            result = result.replace(Regex("""\b$phrase\b"""), " $value ")
        }

        // Dizaines composees : "vingt et un", "trente deux", ... "soixante neuf".
        FRENCH_TENS.forEach { (tenWord, tenValue) ->
            for (unit in 1..9) {
                val unitWord = FRENCH_UNITS.entries.first { it.value == unit && it.key != "une" }.key
                val liaison = if (unit == 1) "et $unitWord" else unitWord
                result = result.replace(Regex("""\b$tenWord $liaison\b"""), " ${tenValue + unit} ")
            }
        }

        // Dizaines seules restantes : "vingt", "trente", ...
        FRENCH_TENS.forEach { (word, value) ->
            result = result.replace(Regex("""\b$word\b"""), " $value ")
        }

        // Unites et 10-16 restants.
        (FRENCH_TEENS + FRENCH_UNITS).forEach { (word, value) ->
            result = result.replace(Regex("""\b$word\b"""), " $value ")
        }

        return result
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return buildString {
            if (h > 0) append("${h}h")
            if (m > 0) append("${m}min")
            if (s > 0 || (h == 0 && m == 0)) append("${s}s")
        }
    }

    /**
     * Applique une couleur de bulle (utilisateur ou assistant) detectee dans
     * [text] via [extractColorId]. Utilise par les deux matchers "couleur de
     * mes bulles" / "couleur des bulles de Jarvis" tout en haut de la liste
     * de matchers -- factorise ici pour ne pas dupliquer la logique.
     */
    private suspend fun setBubbleColorFromText(text: String, forUser: Boolean): CommandResult {
        val color = extractColorId(text)
            ?: return CommandResult.Handled("Précise une couleur : cyan, or, rouge, violet ou vert.")
        val key = if (forUser) BUBBLE_USER_COLOR else BUBBLE_ASSISTANT_COLOR
        settings.set(key, color)
        val target = if (forUser) "tes messages" else "les messages de Jarvis"
        return CommandResult.Handled("Couleur de $target changée en ${BubbleStyle.colorLabel(color)}.")
    }

    /** Detecte un identifiant de couleur parmi la palette de [BubbleStyle.colorOptions]. */
    private fun extractColorId(text: String): String? = when {
        Regex("""\bor\b|dor[ée]e?s?|gold""").containsMatchIn(text) -> "gold"
        Regex("rouge|red").containsMatchIn(text) -> "red"
        Regex("violet|purple").containsMatchIn(text) -> "violet"
        Regex("vert|green").containsMatchIn(text) -> "green"
        Regex("cyan").containsMatchIn(text) -> "cyan"
        else -> null
    }

    /**
     * Presentation des contacts (voir ui/settings/SettingsScreen.kt : reglage
     * "Présentation des contacts"). Compacte par defaut (juste les noms,
     * comportement historique) ou detaillee (nom + numero, une ligne par
     * contact) -- necessite que ContactsRepository.listContacts() ait bien
     * peuple le numero (voir integrations/ContactsRepository.kt).
     */
    private fun formatContacts(contacts: List<Contact>, detailed: Boolean): String {
        if (!detailed) {
            return "Contacts (${contacts.size}) : " + contacts.joinToString(", ") { it.name }
        }
        return buildString {
            appendLine("Contacts (${contacts.size}) :")
            contacts.forEach { c ->
                val phone = c.phone?.takeIf { it.isNotBlank() } ?: "pas de numéro"
                appendLine("👤 ${c.name} — $phone")
            }
        }.trim()
    }

    /**
     * Formate un lot de mails pour affichage chat -- voir integrations/MailReader.kt.
     * Marqueur visuel simple (🔵) pour les non-lus, pas de reglage de style
     * dedie contrairement aux contacts/planning : un mail n'a qu'une seule
     * presentation utile ici (expediteur + objet + court extrait).
     */
    private fun formatMails(mails: List<MailSummary>): String {
        if (mails.isEmpty()) return "Aucun mail à afficher."
        return buildString {
            appendLine("Mails (${mails.size}) :")
            mails.forEach { m ->
                val marker = if (m.isUnread) "🔵" else "•"
                appendLine("$marker ${m.from} — ${m.subject}")
                if (m.snippet.isNotBlank()) appendLine("   ${m.snippet}")
            }
        }.trim()
    }

    /**
     * Presentation du planning (voir ui/settings/SettingsScreen.kt : reglage
     * "Regrouper par jour"). Groupe par defaut, sinon liste plate simple --
     * les deux formats restent locaux, aucune dependance a un LLM.
     */
    private fun formatEvents(events: List<CalendarEvent>, groupByDay: Boolean): String {
        if (!groupByDay) {
            return "Prochains événements : " + events.joinToString(" ; ") { it.title }
        }

        val zone = java.time.ZoneId.systemDefault()
        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm")
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH)

        val grouped = events.groupBy { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }

        return buildString {
            appendLine("Planning :")
            grouped.forEach { (date, dayEvents) ->
                val dayLabel = date.format(dayFmt).replaceFirstChar { it.uppercase() }
                appendLine("📅 $dayLabel")
                dayEvents.forEach { e ->
                    val time = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalTime().format(timeFmt)
                    appendLine("  • $time — ${e.title}")
                }
            }
        }.trim()
    }

    /**
     * Extrait (ancien nom, nouveau nom) depuis une phrase du type
     * "renomme la note Courses en Liste de courses". Cherche le mot "note"
     * puis coupe sur le premier " en " qui suit -- suffisant pour ce cas
     * d'usage precis sans faire un vrai parseur NLP.
     */
    private fun extractRenamePair(text: String): Pair<String, String>? {
        val noteIdx = text.indexOf("note")
        if (noteIdx < 0) return null
        val afterNote = text.substring(noteIdx + "note".length).trim()
        val enIdx = afterNote.indexOf(" en ")
        if (enIdx <= 0) return null
        val oldName = afterNote.substring(0, enIdx).trim()
        val newName = afterNote.substring(enIdx + " en ".length).trim()
        if (oldName.isBlank() || newName.isBlank()) return null
        return oldName.replaceFirstChar { it.uppercase() } to newName.replaceFirstChar { it.uppercase() }
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
