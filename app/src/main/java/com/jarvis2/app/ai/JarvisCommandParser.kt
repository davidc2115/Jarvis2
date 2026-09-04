package com.jarvis2.app.ai

import org.json.JSONObject

/** Une commande d'action parsee depuis un bloc [JARVIS_CMD:{...}] (voir [JarvisCommandParser]). */
data class ParsedCommand(val action: String, val params: Map<String, String>)

/**
 * Extrait le ou les blocs `[JARVIS_CMD:{...}]` d'une reponse texte de l'IA
 * cloud (voir CloudAiClient + ChatViewModel.buildCloudSystemPrompt), et
 * renvoie le texte "propre" (blocs retires, jamais affiches tel quels a
 * l'utilisateur) ainsi que la liste des commandes parsees.
 *
 * Portage depuis Newjarvis (davidc2115/Newjarvis, JarvisCommandParser.kt) --
 * fusion task #2/#3, suite au signalement "pas de comprehension correct".
 * Newjarvis avait deja identifie et corrige un BUG REEL que la version
 * precedente de ce fichier reproduisait telle quelle : l'ancien regex non
 * gourmand `\[JARVIS_CMD:(\{.*?})]` s'arrete au TOUT PREMIER `]` rencontre
 * -- qui est souvent celui d'un tableau JSON INTERNE au payload lui-meme
 * (ex: un futur `create_note`/`save_contact_profile` avec un parametre
 * tableau). Le JSON capture etait alors tronque en plein milieu ("JSON
 * invalide"), ce qui faisait silencieusement echouer l'action ENTIERE sans
 * qu'aucune erreur visible n'explique pourquoi -- exactement le genre de
 * "comprehension incorrecte" remontee. [findJarvisCommands] compte les
 * crochets imbriques (en ignorant ceux a l'interieur des chaines JSON,
 * echappements compris) pour trouver le VRAI crochet fermant du bloc, quel
 * que soit son contenu interne.
 *
 * Autre difference avec l'ancienne version : plusieurs blocs
 * `[JARVIS_CMD:...]` dans une seule reponse sont maintenant tous parses
 * (pas seulement le premier) -- utile des que l'IA cloud doit enchainer
 * plusieurs actions en une seule reponse (ex: creer une fiche contact ET
 * memoriser un fait).
 *
 * Reste volontairement tolerant : un JSON mal forme a l'interieur d'un bloc
 * ne fait pas planter le parsing -- CETTE commande est simplement absente
 * du resultat, les autres commandes valides et le texte affiche restent
 * corrects.
 */
object JarvisCommandParser {

    private data class JarvisCmdMatch(val payload: String, val fullStart: Int, val fullEnd: Int)

    private const val MARKER = "[JARVIS_CMD:"

    /**
     * Trouve tous les blocs `[JARVIS_CMD:...]` de [text] en comptant les
     * crochets imbriques plutot qu'avec un regex non-gourmand -- voir la
     * doc de classe pour le bug reel que ça corrige. Un bloc dont le
     * crochet fermant n'est jamais trouve (reponse tronquee par le
     * fournisseur cloud, ex: max_tokens atteint en plein milieu du JSON)
     * est simplement abandonne, jamais une exception.
     */
    private fun findJarvisCommands(text: String): List<JarvisCmdMatch> {
        val results = mutableListOf<JarvisCmdMatch>()
        var searchFrom = 0
        while (true) {
            val start = text.indexOf(MARKER, searchFrom)
            if (start < 0) break
            val contentStart = start + MARKER.length
            var depth = 1 // le crochet ouvrant du marker lui-meme
            var inString = false
            var escaped = false
            var i = contentStart
            var end = -1
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) end = i
                        }
                    }
                }
                if (end != -1) break
                i++
            }
            if (end == -1) break // bloc non termine (reponse tronquee) -- on abandonne, pas de crash
            results.add(JarvisCmdMatch(text.substring(contentStart, end), start, end + 1))
            searchFrom = end + 1
        }
        return results
    }

    /** [raw] -> (texte affichable sans les blocs de commande, commandes parsees dans l'ordre d'apparition). */
    fun parse(raw: String): Pair<String, List<ParsedCommand>> {
        val matches = findJarvisCommands(raw)
        if (matches.isEmpty()) return raw.trim() to emptyList()

        val commands = matches.mapNotNull { match ->
            runCatching {
                val json = JSONObject(match.payload.trim())
                val action = json.getString("action")
                val params = mutableMapOf<String, String>()
                json.keys().forEach { key ->
                    if (key != "action") params[key] = json.get(key).toString()
                }
                ParsedCommand(action, params)
            }.getOrNull()
        }

        // Retire tous les blocs matches du texte affichable, en partant de la
        // fin pour ne pas invalider les indices des matches precedents.
        var cleanText = raw
        matches.sortedByDescending { it.fullStart }.forEach { match ->
            cleanText = cleanText.removeRange(match.fullStart, match.fullEnd)
        }
        return cleanText.trim() to commands
    }
}
