package com.jarvis2.app.ai

import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.filegen.FileGenRouter
import com.jarvis2.app.integrations.CalendarEvent
import com.jarvis2.app.integrations.CalendarInfo
import com.jarvis2.app.integrations.Contact
import com.jarvis2.app.integrations.IntegrationsRouter
import com.jarvis2.app.integrations.MailSummary
import com.jarvis2.app.integrations.WeatherReport
import com.jarvis2.app.obsidian.Note
import com.jarvis2.app.obsidian.VaultRepository
import com.jarvis2.app.ui.settings.BUBBLE_ASSISTANT_COLOR
import com.jarvis2.app.ui.settings.BUBBLE_SHAPE
import com.jarvis2.app.ui.settings.BUBBLE_USER_COLOR
import com.jarvis2.app.ui.settings.CALENDAR_GROUP_BY_DAY
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
    private val engineManager: AiEngineManager,
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
        val FRENCH_DAYS_OF_WEEK = mapOf(
            "lundi" to java.time.DayOfWeek.MONDAY, "mardi" to java.time.DayOfWeek.TUESDAY,
            "mercredi" to java.time.DayOfWeek.WEDNESDAY, "jeudi" to java.time.DayOfWeek.THURSDAY,
            "vendredi" to java.time.DayOfWeek.FRIDAY, "samedi" to java.time.DayOfWeek.SATURDAY,
            "dimanche" to java.time.DayOfWeek.SUNDAY,
        )
        val FRENCH_MONTHS = mapOf(
            "janvier" to 1, "fevrier" to 2, "février" to 2, "mars" to 3, "avril" to 4,
            "mai" to 5, "juin" to 6, "juillet" to 7, "aout" to 8, "août" to 8,
            "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12, "décembre" to 12,
        )
        const val PREF_NOTE_CONTACTS = "Preference presentation contacts"
        const val PREF_NOTE_PLANNING = "Preference presentation planning"
        const val PREF_NOTE_WEBSEARCH = "Preference presentation recherche web"
        const val PREF_NOTE_CONTACT_FICHE = "Preference presentation fiche contact"
        const val PREF_NOTE_CALENDAR_NICKNAMES = "Surnoms calendrier"

        /**
         * Mots qui suivent "planning de"/"agenda de" mais qui designent une
         * PERIODE (deja geree par resolvePeriod) et non un calendrier/une
         * personne -- sert a eviter de chercher un calendrier nomme "demain"
         * ou "la" (mot vide francais) dans extractCalendarNameQuery().
         */
        val PERIOD_OR_STOPWORDS = setOf(
            "demain", "aujourd'hui", "aujourdhui", "semaine", "mois", "soir", "matin", "midi", "minuit",
            "après-midi", "apres-midi", "week-end", "weekend", "lundi", "mardi", "mercredi",
            "jeudi", "vendredi", "samedi", "dimanche", "prochaine", "prochain",
            "la", "le", "l", "les", "ce", "cette", "cet", "mon", "ma", "mes",
            "notre", "nos", "votre", "vos", "leur", "leurs",
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
            // --- Presentation entierement libre (voir renderWithLlm plus bas) :
            // au lieu de choisir entre deux styles figes, l'utilisateur decrit
            // en detail la presentation voulue apres les deux-points, et
            // c'est sauvegarde tel quel dans une note du vault Obsidian pour
            // etre reapplique a chaque affichage (contacts/planning/recherche
            // web), y compris apres redemarrage de l'appli.
            Matcher(Regex("""(enregistre|retiens|mémorise|memorise).*(présentation|presentation).*contacts?.*:""")) { t ->
                handleSavePresentationInstruction(t, PREF_NOTE_CONTACTS, "des contacts")
            },
            Matcher(Regex("""(enregistre|retiens|mémorise|memorise).*(présentation|presentation).*(planning|agenda).*:""")) { t ->
                handleSavePresentationInstruction(t, PREF_NOTE_PLANNING, "du planning")
            },
            Matcher(Regex("""(enregistre|retiens|mémorise|memorise).*(présentation|presentation).*(recherche web|résultats? web|resultats? web).*:""")) { t ->
                handleSavePresentationInstruction(t, PREF_NOTE_WEBSEARCH, "de la recherche web")
            },
            // --- Presentation des fiches contact du vault (voir renderContactFiche
            // plus bas) : meme mecanisme que contacts/planning/recherche web --
            // permet un format "comme le vrai Obsidian" (proprietes personnalisees,
            // sections libres...) au lieu du format fixe par defaut.
            Matcher(Regex("""(enregistre|retiens|mémorise|memorise).*(présentation|presentation).*(fiche).*(contact).*:""")) { t ->
                handleSavePresentationInstruction(t, PREF_NOTE_CONTACT_FICHE, "des fiches contact")
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
            Matcher(Regex("""(météo|meteo|quel temps|il fait quel temps|va-t-il pleuvoir|va t il pleuvoir|température dehors|temperature dehors|température qu'il fait|il fait combien dehors)""")) {
                val loc = integrations.location.lastKnownLocation()
                    ?: kotlinx.coroutines.withTimeoutOrNull(8_000) { integrations.location.requestSingleFreshLocation() }
                if (loc == null) {
                    CommandResult.Handled("Position GPS indisponible pour la météo (vérifie que la localisation est activée et autorisée).")
                } else {
                    val result = integrations.weather.currentWeather(loc.latitude, loc.longitude)
                    result.fold(
                        onSuccess = { w -> CommandResult.Handled(formatWeather(w)) },
                        onFailure = { e -> CommandResult.Handled("Impossible de récupérer la météo : ${e.message}") },
                    )
                }
            },
            Matcher(Regex("(crée|ajoute|planifie).*(rendez-vous|événement|evenement|rappel|réunion)")) { t ->
                val title = extractAfter(t, listOf("rendez-vous", "événement", "evenement", "rappel", "réunion")) ?: "Nouvel événement Jarvis"
                val eventId = integrations.calendar.createEvent(title = title, startTimeMillis = System.currentTimeMillis() + 3600_000)
                CommandResult.Handled("Événement \"$title\" créé dans l'agenda (id $eventId).")
            },
            // Le 3e groupe ("j'ai quoi"/"je fais quoi"/...) couvre les phrases qui
            // demandent le planning SANS jamais dire le mot "planning"/"agenda" --
            // avant cet ajout, "j'ai quoi demain ?" ne matchait aucun matcher et
            // tombait tout droit dans la conversation LLM generale, ou le petit
            // modele local (CPU, voir gpuLayers=0) n'a de toute facon PAS acces au
            // vrai contenu de l'agenda et ne peut qu'inventer une reponse -- exactement
            // le symptome "les infos donnees ne sont pas correctes" remonte par
            // l'utilisateur, mais applique au planning plutot qu'a la recherche web.
            Matcher(Regex("""((mes?|mon|montre|affiche).*(prochains? événements?|prochains? rendez-vous|planning|agenda))|((planning|agenda).*(aujourd'?hui|demain|après.?demain|apres.?demain|avant.?hier|hier|cette semaine|semaine prochaine|ce mois|mois prochain|ce soir|ce matin|après.?midi|apres.?midi|week-?end|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|\d{1,2}/\d{1,2}|dans\s+\d+\s+jours?))|((planning|agenda)\s+de\s+\S+)|((j'ai quoi|je fais quoi|qu'est-ce que j'ai|qu'est ce que j'ai|qu'est-ce que je fais|qu'est ce que je fais|je suis pris|je suis occupé|je suis occupe|suis-je pris|suis je pris).*(aujourd'?hui|demain|après.?demain|apres.?demain|avant.?hier|hier|cette semaine|semaine prochaine|ce mois|mois prochain|ce soir|ce matin|après.?midi|apres.?midi|week-?end|lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|\d{1,2}/\d{1,2}))""")) { t ->
                val period = resolvePeriod(t)
                val limit = if (period.label == "Prochains événements") 10 else 50
                // --- Filtrage par calendrier precis ("planning de Thomas") : voir
                // extractCalendarNameQuery/resolveCalendarFilter plus bas. nameQuery
                // reste null pour "planning de demain"/"planning de lundi" etc (mots
                // de periode, deja geres par resolvePeriod ci-dessus), donc aucune
                // regression sur le comportement existant sans nom de calendrier.
                val nameQuery = extractCalendarNameQuery(t)
                val calendarInfo = nameQuery?.let { resolveCalendarFilter(it) }
                val events = integrations.calendar.eventsInRange(period.fromMillis, period.toMillis, limit, calendarId = calendarInfo?.id)
                val label = if (calendarInfo != null) "${period.label} — ${calendarInfo.displayName}" else period.label
                if (events.isEmpty()) {
                    val extra = if (nameQuery != null && calendarInfo == null) " (aucun calendrier trouvé pour « $nameQuery » — dis \"liste les calendriers\" pour voir les noms disponibles)" else ""
                    CommandResult.Handled("Aucun événement trouvé (${label.lowercase()})$extra.")
                } else {
                    CommandResult.Handled(renderEvents(events, label))
                }
            },
            Matcher(Regex("""(liste|montre|affiche|quels?).*calendriers?""")) {
                val calendars = integrations.calendar.listCalendars()
                if (calendars.isEmpty()) {
                    CommandResult.Handled("Aucun calendrier trouvé (ou permission Agenda non accordée).")
                } else {
                    CommandResult.Handled(
                        "Calendriers disponibles : " + calendars.joinToString(", ") { "${it.displayName} (${it.accountName})" },
                    )
                }
            },
            // --- Surnom de calendrier ("surnomme le calendrier Compte pro en
            // Thomas") : permet d'utiliser "planning de Thomas" meme si le vrai
            // nom du calendrier (compte Google, calendrier partage...) est
            // different -- stocke dans une note dediee du vault, meme mecanisme
            // que les preferences de presentation.
            Matcher(Regex("""(surnomme|renomme|appelle) le calendrier .+ (en|comme) .+""")) { t ->
                val m = Regex("""calendrier\s+(.+?)\s+(?:en|comme)\s+(.+)""").find(t)
                if (m == null) {
                    CommandResult.Handled("Précise, par exemple : « surnomme le calendrier Compte pro en Thomas ».")
                } else {
                    val realName = m.groupValues[1].trim()
                    val nickname = m.groupValues[2].trim()
                    if (realName.isBlank() || nickname.isBlank()) {
                        CommandResult.Handled("Précise, par exemple : « surnomme le calendrier Compte pro en Thomas ».")
                    } else {
                        saveCalendarNickname(nickname, realName)
                        CommandResult.Handled("Le calendrier « $realName » peut maintenant être demandé sous le nom « $nickname ».")
                    }
                }
            },
            // --- LECTURE d'une fiche contact deja existante dans le vault (dossier
            // Contacts/) : place AVANT le matcher de CREATION juste en dessous, car
            // les verbes ("affiche"/"montre"/...) sont disjoints de ceux de creation
            // ("crée"/"ajoute"/...) mais l'ordre reste important par principe. Sans ce
            // matcher, "montre la fiche contact de Marie" tombait dans la conversation
            // LLM generale -- le petit modele local n'a pas acces au vault et ne peut
            // qu'inventer un contenu plausible. Si la fiche n'existe pas encore, elle
            // est creee a la volee depuis le contact telephone reel (meme logique que
            // le matcher de creation), pour ne jamais repondre "je ne sais pas" alors
            // que le contact existe bel et bien sur le telephone.
            Matcher(Regex("(affiche|montre|donne|voir|vois|lis|ouvre|as-tu|as tu).*(fiche).*(contact)")) { t ->
                val name = extractAfter(
                    t,
                    listOf("fiche contact pour", "fiche contact de", "fiche de contact pour", "fiche de contact de", "fiche pour", "fiche de"),
                )
                if (name == null) {
                    CommandResult.Handled("Précise pour qui, par exemple « montre la fiche contact de Marie ».")
                } else {
                    val existingNotes = vault.listNotes().filter { it.folderPath == "Contacts" && it.title.contains(name, ignoreCase = true) }
                    when {
                        existingNotes.size == 1 -> CommandResult.Handled(existingNotes.first().body)
                        existingNotes.size > 1 -> CommandResult.Handled(
                            "Plusieurs fiches correspondent à « $name » : " + existingNotes.joinToString(", ") { it.title } + ". Précise laquelle.",
                        )
                        else -> {
                            val matches = integrations.contacts.listContacts(limit = 200).filter { it.name.contains(name, ignoreCase = true) }
                            when {
                                matches.isEmpty() -> CommandResult.Handled("Aucune fiche ni contact trouvé pour « $name ».")
                                matches.size > 1 -> CommandResult.Handled(
                                    "Plusieurs contacts correspondent à « $name » : " + matches.joinToString(", ") { it.name } + ". Précise lequel.",
                                )
                                else -> {
                                    val c = matches.first()
                                    vault.createFolder("Contacts")
                                    val note = createContactFicheNote(c)
                                    CommandResult.Handled(note.body)
                                }
                            }
                        }
                    }
                }
            },
            // --- Fiche contact dans le vault (dossier Contacts/) : place AVANT le
            // matcher generique "crée un contact" ci-dessous car "fiche contact"
            // doit creer une NOTE vault a partir d'un contact existant du
            // telephone, pas un nouveau contact telephone.
            Matcher(Regex("(crée|génère|fait|ajoute|enregistre).*(fiche).*(contact)")) { t ->
                val name = extractAfter(
                    t,
                    listOf("fiche contact pour", "fiche contact de", "fiche de contact pour", "fiche de contact de", "fiche pour", "fiche de"),
                )
                if (name == null) {
                    CommandResult.Handled("Précise pour qui, par exemple « crée une fiche contact pour Marie ».")
                } else {
                    val matches = integrations.contacts.listContacts(limit = 200).filter { it.name.contains(name, ignoreCase = true) }
                    when {
                        matches.isEmpty() -> CommandResult.Handled("Aucun contact trouvé pour « $name » (ou permission Contacts non accordée).")
                        matches.size > 1 -> CommandResult.Handled(
                            "Plusieurs contacts correspondent à « $name » : " + matches.joinToString(", ") { it.name } + ". Précise lequel.",
                        )
                        else -> {
                            val c = matches.first()
                            vault.createFolder("Contacts")
                            val note = createContactFicheNote(c)
                            CommandResult.Handled("Fiche « ${note.title} » créée dans le vault (dossier Contacts).")
                        }
                    }
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
                    CommandResult.Handled(renderContacts(contacts))
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
            // --- Fiche d'un contact precis (numero/email/coordonnees) : voir
            // integrations/ContactsRepository.kt pour phone/email reels.
            // Place explicitement AVANT tout fallback LLM pour eviter qu'un
            // petit modele local (1-3B parametres, sans acces aux vraies
            // donnees) n'invente un numero/email plausible mais faux quand on
            // lui demande "le numero de Marie" sans passer par ce matcher --
            // c'est exactement le bug signale par l'utilisateur (numero
            // invente, email en "@exemple.com").
            Matcher(Regex("""(numéro|numero|téléphone|telephone|portable|coordonnées|coordonnees|adresse mail|adresse email|email|mail) de\s+\S""")) { t ->
                val name = extractAfter(
                    t,
                    listOf(
                        "adresse mail de", "adresse email de", "numéro de téléphone de", "numero de telephone de",
                        "numéro de", "numero de", "téléphone de", "telephone de", "portable de",
                        "coordonnées de", "coordonnees de", "email de", "mail de",
                    ),
                )
                if (name == null) {
                    CommandResult.Handled("Précise un nom, par exemple « numéro de Marie ».")
                } else {
                    val matches = integrations.contacts.listContacts(limit = 200).filter { it.name.contains(name, ignoreCase = true) }
                    when {
                        matches.isEmpty() -> CommandResult.Handled("Aucun contact trouvé pour « $name » (ou permission Contacts non accordée).")
                        matches.size > 1 -> CommandResult.Handled(
                            "Plusieurs contacts correspondent à « $name » : " + matches.joinToString(", ") { it.name } + ". Précise lequel.",
                        )
                        else -> CommandResult.Handled(formatSingleContact(matches.first()))
                    }
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
            // --- Diagnostic mail : place AVANT le matcher generique "lis les
            // mails" -- expose la VRAIE cause de l'echec (voir MailReader.diagnosticStatus)
            // au lieu du message generique "Aucun compte Google connecte" qui
            // masquait toutes les autres causes possibles (app non enregistree/
            // verifiee cote Google Cloud Console, compte non ajoute comme testeur,
            // erreur reseau, etc.) sous le meme texte trompeur.
            Matcher(Regex("""(diagnostic|statut|status).*(mail|gmail)""")) {
                CommandResult.Handled(integrations.mailReader.diagnosticStatus())
            },
            Matcher(Regex("""(lis|montre|affiche).*mails?|(derniers?|nouveaux?) mails?|mails? non lus?""")) { t ->
                // Ancien comportement : un pre-check isConfigured() affichait un
                // message generique "Aucun compte Google connecte" des qu'un
                // jeton echouait pour N'IMPORTE QUELLE raison, masquant la vraie
                // cause (par ex. "unregistered on API console", 403 access_denied,
                // erreur reseau...). On appelle directement fetchRecent() et on
                // affiche le message d'erreur reel remonte par GoogleAuthController.
                val unreadOnly = Regex("non lus?").containsMatchIn(t)
                val result = integrations.mailReader.fetchRecent(limit = 10, unreadOnly = unreadOnly)
                result.fold(
                    onSuccess = { mails -> CommandResult.Handled(formatMails(mails)) },
                    onFailure = { e -> CommandResult.Handled(formatMailError(e)) },
                )
            },
            Matcher(Regex("(envoie|rédige|compose).*mail")) { t ->
                val subject = extractAfter(t, listOf("mail", "email", "courriel")) ?: "Message depuis Jarvis"
                integrations.mail.composeMail(subject = subject, body = "")
                CommandResult.Handled("Ouverture de ton application mail avec le sujet \"$subject\".")
            },
            Matcher(Regex("(crée|ajoute|nouvelle).*(note).*(vault|obsidian)|(vault|obsidian).*(crée|ajoute|nouvelle).*note")) { t ->
                val titleGuess = extractAfter(t, listOf("intitulée", "appelée", "titre", "titrée")) ?: "Note Jarvis"
                val folderGuess = extractAfter(t, listOf("dans le dossier", "dans le folder")).orEmpty()
                val body = vault.autoLink(t, excludeTitle = titleGuess)
                val note = vault.createNote(titleGuess, body = body, folderPath = folderGuess)
                val where = if (folderGuess.isBlank()) "dans le vault Obsidian" else "dans le dossier « $folderGuess » du vault"
                CommandResult.Handled("Note « ${note.title} » créée $where.")
            },
            // --- Dossiers du vault : "crée un dossier Projets (dans le vault)".
            // Place ici, apres les commandes de note, avant renomme/supprime pour
            // rester groupe avec le reste de la gestion Obsidian.
            Matcher(Regex("(crée|ajoute|nouveau|nouvelle).*(dossier|folder).*(vault|obsidian)|(vault|obsidian).*(crée|ajoute|nouveau|nouvelle).*(dossier|folder)")) { t ->
                val name = extractAfter(t, listOf("dossier", "folder"))
                if (name == null) {
                    CommandResult.Handled("Précise le nom du dossier, par exemple « crée un dossier Projets dans le vault ».")
                } else {
                    val ok = vault.createFolder(name)
                    if (ok) CommandResult.Handled("Dossier « $name » créé dans le vault.")
                    else CommandResult.Handled("Échec de la création du dossier « $name ».")
                }
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
                        vault.deleteNote(existing)
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
            contacts.forEach { c -> appendLine("👤 ${c.name} — ${phoneOrPlaceholder(c)}") }
        }.trim()
    }

    private fun phoneOrPlaceholder(c: Contact) = c.phone?.takeIf { it.isNotBlank() } ?: "pas de numéro"

    /**
     * Fiche d'un seul contact, avec UNIQUEMENT les vraies donnees issues de
     * ContactsRepository -- jamais de valeur inventee. Si le champ n'existe
     * pas dans les contacts du telephone, le dit explicitement plutot que de
     * laisser un LLM (meme via renderWithLlm) le deviner/completer.
     */
    private fun formatSingleContact(c: Contact): String {
        val phone = c.phone?.takeIf { it.isNotBlank() }
        val email = c.email?.takeIf { it.isNotBlank() }
        return buildString {
            append("${c.name} : ")
            append(phone ?: "pas de numéro enregistré")
            append(", ")
            append(email ?: "pas d'email enregistré")
            append(".")
        }
    }

    /**
     * Formate un lot de mails pour affichage chat -- voir integrations/MailReader.kt.
     * Marqueur visuel simple (🔵) pour les non-lus, pas de reglage de style
     * dedie contrairement aux contacts/planning : un mail n'a qu'une seule
     * presentation utile ici (expediteur + objet + court extrait).
     */
    /**
     * Corps markdown PAR DEFAUT d'une fiche contact enregistree dans le vault
     * (dossier Contacts/, voir le matcher "fiche contact" plus haut) --
     * uniquement des vraies donnees du telephone, jamais rien d'invente, meme
     * logique de prudence que formatSingleContact. Sections a la maniere d'un
     * vrai fichier Obsidian (proprietes en frontmatter -- voir
     * createContactFicheNote -- + sections de corps libres avec emoji, comme
     * l'ancienne appli avant la reecriture #182). Ce format n'est utilise que
     * si l'utilisateur n'a enregistre aucune presentation personnalisee (voir
     * renderContactFiche / PREF_NOTE_CONTACT_FICHE).
     */
    private fun formatContactFicheBody(c: Contact): String = buildString {
        appendLine("# ${c.name}")
        appendLine()
        appendLine("## ☎️ Coordonnées")
        appendLine("- **Téléphone** : ${c.phone?.takeIf { it.isNotBlank() } ?: "non renseigné"}")
        appendLine("- **Email** : ${c.email?.takeIf { it.isNotBlank() } ?: "non renseigné"}")
        appendLine()
        appendLine("## 📝 Notes")
        appendLine()
    }.trim()

    /**
     * Presentation de la fiche contact : meme mecanisme que renderContacts/
     * renderEvents (voir PREF_NOTE_CONTACT_FICHE) -- si l'utilisateur a
     * enregistre une presentation personnalisee ("enregistre la presentation
     * des fiches contact : ..."), le corps est redige par le moteur IA local
     * selon cette instruction ; sinon, format par defaut riche (voir
     * [formatContactFicheBody]) plutot qu'un format impose et non
     * personnalisable comme avant.
     */
    private suspend fun renderContactFiche(c: Contact): String {
        val instruction = getPresentationInstruction(PREF_NOTE_CONTACT_FICHE)
            ?: return formatContactFicheBody(c)
        val raw = buildString {
            appendLine("Nom : ${c.name}")
            appendLine("Téléphone : ${c.phone?.takeIf { it.isNotBlank() } ?: "non renseigné"}")
            appendLine("Email : ${c.email?.takeIf { it.isNotBlank() } ?: "non renseigné"}")
        }
        return renderWithLlm(raw, instruction) { formatContactFicheBody(c) }
    }

    /**
     * Cree (ou remplace) la note de fiche contact dans le vault, avec de
     * VRAIES proprietes en frontmatter YAML (tags, telephone, email) --
     * "comme le vrai Obsidian" : ces proprietes apparaissent dans le panneau
     * "Properties" natif d'Obsidian si le vault est synchronise avec l'appli
     * desktop/mobile (voir obsidian/NoteParser.kt : rendu frontmatter deja
     * byte-for-byte compatible), pas juste un tag "contact" comme avant.
     */
    private suspend fun createContactFicheNote(c: Contact): Note {
        val body = vault.autoLink(renderContactFiche(c), excludeTitle = c.name)
        val frontmatter = buildMap {
            put("tags", "contact")
            c.phone?.takeIf { it.isNotBlank() }?.let { put("telephone", it) }
            c.email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
        }
        val fileName = "${c.name.replace(Regex("[\\/:*?\"<>|]"), "-")}.md"
        val note = Note(
            fileName = fileName,
            title = c.name,
            body = body,
            frontmatter = frontmatter,
            tags = setOf("contact"),
            links = emptySet(),
            folderPath = "Contacts",
        )
        vault.saveNote(note)
        return note
    }

    /**
     * Message d'erreur mail contextuel : ajoute une piste concrete quand le
     * message brut (voir GoogleAuthController.kt) laisse penser a un probleme
     * de configuration Google Cloud Console plutot qu'a une absence de compte
     * -- c'est la cause la plus frequente et la plus difficile a diagnostiquer
     * sans indice, car elle donne un message d'erreur variable selon le cas
     * (app non verifiee, compte non ajoute comme testeur, API non activee...).
     */
    private fun formatMailError(e: Throwable): String = buildString {
        append("Impossible de lire les mails : ${e.message ?: e::class.simpleName ?: "erreur inconnue"}")
        val lower = (e.message ?: "").lowercase()
        val looksLikeConsoleIssue = listOf("unregistered", "not registered", "access_denied", "403", "invalid_client", "unauthorized")
            .any { lower.contains(it) }
        if (looksLikeConsoleIssue) {
            append(". Cause probable : le client OAuth Android (package com.jarvis2.app.debug + SHA-1 du debug.keystore) n'est pas correctement enregistre dans Google Cloud Console, ou ton compte n'est pas ajoute comme utilisateur test tant que l'appli n'est pas publiee (Ecran de consentement OAuth -> Utilisateurs test). Dis \"diagnostic mail\" pour plus de details.")
        }
    }

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

    /** Formate un releve meteo (voir integrations/WeatherController.kt) pour affichage chat. */
    private fun formatWeather(w: WeatherReport): String = buildString {
        append("Météo à ${w.locationLabel} : ${w.description}, ${Math.round(w.temperatureC)}°C")
        w.feelsLikeC?.let { append(" (ressenti ${Math.round(it)}°C)") }
        append(", vent ${Math.round(w.windKmh)} km/h.")
    }

    /**
     * Presentation du planning (voir ui/settings/SettingsScreen.kt : reglage
     * "Regrouper par jour"). Groupe par defaut, sinon liste plate simple --
     * les deux formats restent locaux, aucune dependance a un LLM. [label]
     * (ex: "Cette semaine", "Demain") est affiche en tete a la place du
     * generique "Planning :" quand une periode precise a ete demandee.
     */
    private fun formatEvents(events: List<CalendarEvent>, groupByDay: Boolean, label: String = "Planning"): String {
        // Nom du calendrier d'origine (voir CalendarEvent.calendarName) affiche
        // entre parentheses -- l'utilisateur ne le voyait jamais avant, alors
        // qu'un planning multi-comptes (perso/pro/partages) est ambigu sans ca.
        if (!groupByDay) {
            return "$label : " + events.joinToString(" ; ") { "${it.title} (${it.calendarName})" }
        }

        val zone = java.time.ZoneId.systemDefault()
        val timeFmt = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm")
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH)

        val grouped = events.groupBy { java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }

        return buildString {
            appendLine("$label :")
            grouped.forEach { (date, dayEvents) ->
                val dayLabel = date.format(dayFmt).replaceFirstChar { it.uppercase() }
                appendLine("📅 $dayLabel")
                dayEvents.forEach { e ->
                    val time = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalTime().format(timeFmt)
                    appendLine("  • $time — ${e.title} (${e.calendarName})")
                }
            }
        }.trim()
    }

    // ============================================================
    // Presentation libre (contacts/planning/recherche web) : voir
    // les matchers "enregistre la presentation de ... :" tout en
    // haut de la liste. L'instruction est sauvegardee comme texte
    // brut dans une note dediee du vault Obsidian (titre fixe) et
    // relue a chaque affichage ; si aucune instruction n'a jamais
    // ete enregistree pour la categorie, on retombe sur le format
    // local simple (ancien comportement, 0% LLM, 100% fiable).
    // ============================================================

    private suspend fun handleSavePresentationInstruction(t: String, noteTitle: String, label: String): CommandResult {
        val colonIdx = t.indexOf(':')
        val instruction = if (colonIdx >= 0) t.substring(colonIdx + 1).trim() else ""
        return if (instruction.isBlank()) {
            CommandResult.Handled("Décris la présentation voulue après les deux-points, par exemple : \"enregistre la présentation $label : ...\".")
        } else {
            savePresentationInstruction(noteTitle, instruction.replaceFirstChar { it.uppercase() })
            CommandResult.Handled("Présentation $label enregistrée dans le vault. Je l'appliquerai à chaque fois.")
        }
    }

    private suspend fun getPresentationInstruction(noteTitle: String): String? =
        vault.findByTitleOrFileName(noteTitle)?.body?.trim()?.takeIf { it.isNotBlank() }

    private suspend fun savePresentationInstruction(noteTitle: String, instructions: String) {
        val existing = vault.findByTitleOrFileName(noteTitle)
        val note = Note(
            fileName = existing?.fileName ?: "${noteTitle.replace(Regex("[\\/:*?\"<>|]"), "-")}.md",
            title = noteTitle,
            body = instructions,
            frontmatter = existing?.frontmatter ?: emptyMap(),
            tags = existing?.tags ?: emptySet(),
            links = existing?.links ?: emptySet(),
        )
        vault.saveNote(note)
    }

    /**
     * Fait rediger le rendu final par le moteur IA local en lui donnant les
     * donnees brutes + l'instruction de presentation exacte de l'utilisateur,
     * plutot que d'imposer un format fige en Kotlin -- c'est ce qui permet de
     * respecter n'importe quelle presentation decrite en langage naturel. En
     * cas d'echec du moteur (pas encore pret, erreur, etc.) on retombe sur
     * [fallback] plutot que de laisser l'utilisateur sans reponse.
     */
    private suspend fun renderWithLlm(dataDescription: String, instruction: String, fallback: () -> String): String {
        val prompt = buildString {
            appendLine("Voici des donnees brutes a presenter dans un chat, en respectant STRICTEMENT les instructions de presentation personnalisees ci-dessous. Reponds uniquement avec le texte final a afficher, sans commentaire ni explication sur ce que tu fais.")
            appendLine()
            appendLine("Instructions de presentation de l'utilisateur :")
            appendLine(instruction)
            appendLine()
            appendLine("Donnees brutes :")
            append(dataDescription)
        }
        val result = engineManager.generate(
            prompt = prompt,
            history = emptyList(),
            systemPrompt = "Tu es un moteur de mise en forme de texte. Tu reformates des donnees selon des instructions precises de l'utilisateur, sans avis ni texte hors-sujet.",
        )
        return result.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: fallback()
    }

    private suspend fun renderContacts(contacts: List<Contact>): String {
        val instruction = getPresentationInstruction(PREF_NOTE_CONTACTS)
            ?: return formatContacts(contacts, detailed = false)
        val raw = contacts.joinToString("\n") { c ->
            val email = c.email?.takeIf { it.isNotBlank() } ?: "pas d'email"
            "- ${c.name} : ${phoneOrPlaceholder(c)}, $email"
        }
        return renderWithLlm("Contacts (${contacts.size}) :\n$raw", instruction) { formatContacts(contacts, detailed = true) }
    }

    private suspend fun renderEvents(events: List<CalendarEvent>, label: String): String {
        val instruction = getPresentationInstruction(PREF_NOTE_PLANNING)
        if (instruction == null) {
            val groupByDay = (settings.get(CALENDAR_GROUP_BY_DAY) ?: "true") == "true"
            return formatEvents(events, groupByDay, label)
        }
        val zone = java.time.ZoneId.systemDefault()
        val raw = events.joinToString("\n") { e ->
            val dt = java.time.Instant.ofEpochMilli(e.startMillis).atZone(zone)
            "- ${dt.toLocalDate()} ${dt.toLocalTime()} : ${e.title} [calendrier: ${e.calendarName}]"
        }
        return renderWithLlm("$label (${events.size} événement(s)) :\n$raw", instruction) { formatEvents(events, groupByDay = true, label = label) }
    }

    /**
     * Rendu final des resultats de recherche web (voir ai/WebSearchTool.kt :
     * searchAndExtract() a deja recupere le texte reel des pages, pas juste
     * les extraits du moteur de recherche). Appele depuis ChatViewModel une
     * fois la recherche effectuee -- respecte l'instruction de presentation
     * "recherche web" sauvegardee, sinon synthese par defaut raisonnable
     * (toujours via le LLM ici, car contrairement aux contacts/planning il
     * n'existe pas de format local pertinent pour SYNTHETISER du texte libre
     * extrait du web -- un simple listing de liens n'est plus le but).
     */
    suspend fun renderWebSearchResults(query: String, extracts: List<WebSearchExtract>): String {
        if (extracts.isEmpty()) return "Aucun résultat exploitable trouvé sur le web pour « $query »."
        val instruction = getPresentationInstruction(PREF_NOTE_WEBSEARCH)
            ?: "Réponds directement et clairement à la question, en te basant UNIQUEMENT sur les informations extraites ci-dessous. N'invente jamais un chiffre, une date ou un fait qui n'apparaît pas explicitement dans les extraits : si les extraits ne contiennent pas la réponse, dis-le clairement au lieu de deviner. Cite brièvement les sources (titre + lien) à la fin."
        val raw = extracts.joinToString("\n\n") { e -> "Source : ${e.title} (${e.url})\n${e.extractedText.take(2000)}" }
        return renderWithLlm(
            dataDescription = "Question de l'utilisateur : $query\n\nExtraits de pages web trouvees :\n$raw",
            instruction = instruction,
        ) {
            "Résultats web pour « $query » :\n" + extracts.joinToString("\n") { "• ${it.title} — ${it.url}" }
        }
    }

    /**
     * Extrait un nom de calendrier/personne depuis "planning de X" / "agenda
     * de X" -- renvoie null si X est un mot de periode ou un mot vide
     * francais (voir PERIOD_OR_STOPWORDS), auquel cas c'est resolvePeriod()
     * qui gere deja la phrase entierement (pas de calendrier precis demande).
     */
    private fun extractCalendarNameQuery(t: String): String? {
        val match = Regex("""(?:planning|agenda)\s+de\s+(\S+)""").find(t) ?: return null
        val candidate = match.groupValues[1].trim().trim(',', '.', '?', '!')
        if (candidate.isBlank() || PERIOD_OR_STOPWORDS.contains(candidate)) return null
        return candidate
    }

    /**
     * Resout un nom/surnom saisi par l'utilisateur vers un vrai calendrier du
     * telephone : d'abord via les surnoms enregistres (voir
     * [loadCalendarNicknames]), puis par correspondance partielle sur le nom
     * d'affichage ou le compte du calendrier. Renvoie null si rien ne
     * correspond (l'appelant retombe alors sur "tous calendriers confondus"
     * et signale qu'aucun calendrier n'a ete trouve pour ce nom).
     */
    private suspend fun resolveCalendarFilter(nameQuery: String): CalendarInfo? {
        val calendars = integrations.calendar.listCalendars()
        if (calendars.isEmpty()) return null
        val nicknames = loadCalendarNicknames()
        val resolvedName = nicknames[nameQuery.lowercase()] ?: nameQuery
        return calendars.firstOrNull {
            it.displayName.contains(resolvedName, ignoreCase = true) || it.accountName.contains(resolvedName, ignoreCase = true)
        }
    }

    /** Surnoms de calendrier enregistres (voir le matcher "surnomme le calendrier ... en ..."), sous forme surnom (minuscule) -> vrai nom. */
    private suspend fun loadCalendarNicknames(): Map<String, String> {
        val body = vault.findByTitleOrFileName(PREF_NOTE_CALENDAR_NICKNAMES)?.body ?: return emptyMap()
        return body.lines().mapNotNull { line ->
            val idx = line.indexOf("=>")
            if (idx < 0) return@mapNotNull null
            val nickname = line.substring(0, idx).trim().lowercase()
            val real = line.substring(idx + 2).trim()
            if (nickname.isBlank() || real.isBlank()) null else nickname to real
        }.toMap()
    }

    private suspend fun saveCalendarNickname(nickname: String, realName: String) {
        val existing = vault.findByTitleOrFileName(PREF_NOTE_CALENDAR_NICKNAMES)
        val lines = existing?.body?.lines()?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        lines.removeAll { it.substringBefore("=>").trim().equals(nickname, ignoreCase = true) }
        lines.add("$nickname => $realName")
        val note = Note(
            fileName = existing?.fileName ?: "${PREF_NOTE_CALENDAR_NICKNAMES.replace(Regex("[\\/:*?\"<>|]"), "-")}.md",
            title = PREF_NOTE_CALENDAR_NICKNAMES,
            body = lines.joinToString("\n"),
            frontmatter = existing?.frontmatter ?: emptyMap(),
            tags = existing?.tags ?: emptySet(),
            links = existing?.links ?: emptySet(),
        )
        vault.saveNote(note)
    }

    /** Plage de dates resolue depuis une phrase (voir [resolvePeriod]). */
    private data class DateRange(val fromMillis: Long, val toMillis: Long, val label: String)

    /**
     * Comprend "planning de demain", "cette semaine", "ce mois", "ce soir",
     * un jour de la semaine ("planning de lundi"), une date explicite
     * ("le 15/03", "le 15 mars"), "dans X jours", "hier"/"avant-hier", etc.
     * et renvoie la plage de temps correspondante -- entierement local/regex,
     * pas de LLM, pour rester instantane comme le reste du CommandRouter.
     *
     * CORRECTIF IMPORTANT (signalement utilisateur repete : "le planning
     * affiche toujours plusieurs jours") : avant, quand AUCUNE periode
     * n'etait reconnue dans le texte, la fonction retombait sur une plage
     * "a partir de maintenant, SANS LIMITE" (Long.MAX_VALUE) -- c'est-a-dire
     * TOUS les evenements a venir, sur des mois. N'importe quelle formulation
     * de "mon planning" non couverte explicitement par une des branches
     * ci-dessous (ex: "c'est quoi mon planning ?", "montre-moi l'agenda")
     * finissait donc systematiquement par afficher un dump multi-jours au
     * lieu d'un jour precis, meme quand l'utilisateur pensait clairement a
     * "aujourd'hui". Comportement corrige pour matcher celui de l'ancienne
     * appli (Newjarvis/CalendarController.getTodayEvents) : le repli par
     * defaut est desormais AUJOURD'HUI, jamais un dump sans limite -- sauf
     * si l'utilisateur demande EXPLICITEMENT "mes prochains evenements/rdv"
     * / "a venir" / "a suivre", auquel cas on renvoie une fenetre bornee a
     * 30 jours (jamais litteralement infinie).
     */
    private fun resolvePeriod(t: String): DateRange {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val normalized = normalizeFrenchNumberWords(t)

        fun ofDay(date: java.time.LocalDate, label: String) = DateRange(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            label,
        )
        fun ofRange(from: java.time.LocalDate, toExclusive: java.time.LocalDate, label: String) = DateRange(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toExclusive.atStartOfDay(zone).toInstant().toEpochMilli(),
            label,
        )
        fun ofTimeWindow(fromHour: Int, toHour: Int, label: String) = DateRange(
            today.atTime(fromHour, 0).atZone(zone).toInstant().toEpochMilli(),
            today.atTime(toHour, 0).atZone(zone).toInstant().toEpochMilli(),
            label,
        )
        fun dayLabel(date: java.time.LocalDate) = date
            .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH))
            .replaceFirstChar { it.uppercase() }

        // --- Date explicite JJ/MM ou JJ/MM/AAAA (priorite haute : un jour
        // precis demande explicitement ne doit jamais retomber sur autre chose).
        Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""").find(t)?.let { m ->
            val day = m.groupValues[1].toIntOrNull()
            val month = m.groupValues[2].toIntOrNull()
            val yearRaw = m.groupValues[3]
            val year = when {
                yearRaw.isBlank() -> today.year
                yearRaw.length <= 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }
            if (day != null && month != null) {
                runCatching { java.time.LocalDate.of(year, month, day) }.getOrNull()?.let { date ->
                    return ofDay(date, dayLabel(date))
                }
            }
        }

        // --- Date explicite "15 mars" / "le 15 mars" (nom de mois en toutes
        // lettres). Si la date tombee est deja passee de plus d'un jour cette
        // annee, on suppose l'annee prochaine (personne ne demande son
        // planning d'une date passee sans le preciser autrement).
        FRENCH_MONTHS.entries.firstOrNull { normalized.contains(it.key) }?.let { (monthWord, monthNum) ->
            Regex("""\b(\d{1,2})\s+$monthWord\b""").find(normalized)?.let { m ->
                val day = m.groupValues[1].toIntOrNull()
                if (day != null) {
                    var date = runCatching { java.time.LocalDate.of(today.year, monthNum, day) }.getOrNull()
                    if (date != null && date.isBefore(today.minusDays(1))) date = date.plusYears(1)
                    if (date != null) return ofDay(date, dayLabel(date))
                }
            }
        }

        // --- "dans X jours" (chiffres ou nombres ecrits en toutes lettres,
        // deja convertis dans [normalized] par normalizeFrenchNumberWords).
        Regex("""dans\s+(\d+)\s+jours?""").find(normalized)?.let { m ->
            val days = m.groupValues[1].toIntOrNull()
            if (days != null && days in 1..365) {
                val date = today.plusDays(days.toLong())
                return ofDay(date, dayLabel(date))
            }
        }

        return when {
            Regex("après.?demain|apres.?demain").containsMatchIn(t) -> ofDay(today.plusDays(2), "Après-demain")
            Regex("""\bdemain\b""").containsMatchIn(t) -> ofDay(today.plusDays(1), "Demain")
            Regex("""\baujourd'?hui\b""").containsMatchIn(t) -> ofDay(today, "Aujourd'hui")
            Regex("avant.?hier").containsMatchIn(t) -> ofDay(today.minusDays(2), "Avant-hier")
            Regex("""\bhier\b""").containsMatchIn(t) -> ofDay(today.minusDays(1), "Hier")
            Regex("ce soir").containsMatchIn(t) -> ofTimeWindow(18, 24, "Ce soir")
            Regex("cet? après.?midi|cet? apres.?midi").containsMatchIn(t) -> ofTimeWindow(12, 18, "Cet après-midi")
            Regex("ce matin").containsMatchIn(t) -> ofTimeWindow(0, 12, "Ce matin")
            Regex("week-?end").containsMatchIn(t) -> {
                val saturday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY))
                ofRange(saturday, saturday.plusDays(2), "Ce week-end")
            }
            Regex("semaine prochaine|la semaine qui vient").containsMatchIn(t) -> {
                val nextMonday = today.plusWeeks(1).with(java.time.DayOfWeek.MONDAY)
                ofRange(nextMonday, nextMonday.plusWeeks(1), "La semaine prochaine")
            }
            Regex("cette semaine").containsMatchIn(t) -> {
                val monday = today.with(java.time.DayOfWeek.MONDAY)
                ofRange(monday, monday.plusWeeks(1), "Cette semaine")
            }
            Regex("mois prochain").containsMatchIn(t) -> {
                val firstNext = today.plusMonths(1).withDayOfMonth(1)
                ofRange(firstNext, firstNext.plusMonths(1), "Le mois prochain")
            }
            Regex("ce mois|mois en cours|mois actuel").containsMatchIn(t) -> {
                val first = today.withDayOfMonth(1)
                ofRange(first, first.plusMonths(1), "Ce mois-ci")
            }
            FRENCH_DAYS_OF_WEEK.keys.any { t.contains(it) } -> {
                val (kw, dow) = FRENCH_DAYS_OF_WEEK.entries.first { t.contains(it.key) }
                val prochain = Regex("prochain|qui vient").containsMatchIn(t)
                var date = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow))
                if (prochain && date == today) date = date.plusWeeks(1)
                ofDay(date, kw.replaceFirstChar { it.uppercase() })
            }
            // Aucune periode reconnue : "mes prochains evenements/rdv a venir"
            // demande explicitement une liste (bornee a 30 jours, jamais
            // litteralement infinie) -- toute autre formulation ambigue de
            // "planning"/"agenda" retombe sur AUJOURD'HUI, jamais un dump
            // multi-jours (voir le commentaire de la fonction ci-dessus).
            Regex("""prochains?\s+(é|e)v(é|e)nements?|prochains?\s+rendez-?vous|[aà]\s+venir|suivants?""").containsMatchIn(t) ->
                DateRange(now.toInstant().toEpochMilli(), today.plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli(), "Prochains événements")
            else -> ofDay(today, "Aujourd'hui")
        }
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
