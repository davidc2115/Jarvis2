package com.jarvis2.app.ai

import org.json.JSONObject

/** Une commande d'action parsee depuis un bloc [JARVIS_CMD:{...}] (voir [JarvisCommandParser]). */
data class ParsedCommand(val action: String, val params: Map<String, String>)

/**
 * Extrait un bloc `[JARVIS_CMD:{...}]` d'une reponse texte de l'IA cloud
 * (voir CloudAiClient + CLOUD_SYSTEM_PROMPT dans ChatViewModel.kt), et
 * renvoie le texte "propre" (bloc retire, jamais affiche tel quel a
 * l'utilisateur) ainsi que la commande parsee le cas echeant. Format repris
 * tel quel de l'ancienne Newjarvis (voir ApiClient.kt de ce depot) pour
 * rester un protocole simple et deja eprouve, plutot que d'inventer un
 * nouveau format JSON.
 *
 * Volontairement tolerant : un JSON mal forme a l'interieur du bloc ne fait
 * pas planter le parsing, la commande est simplement absente (null) et le
 * texte affiche a l'utilisateur reste correct.
 */
object JarvisCommandParser {
    private val MARKER = Regex("""\[JARVIS_CMD:(\{.*?})]""", RegexOption.DOT_MATCHES_ALL)

    /** [raw] -> (texte affichable sans le bloc de commande, commande parsee ou null). */
    fun parse(raw: String): Pair<String, ParsedCommand?> {
        val match = MARKER.find(raw) ?: return raw.trim() to null
        val cleanText = raw.removeRange(match.range).trim()
        val command = runCatching {
            val json = JSONObject(match.groupValues[1])
            val action = json.getString("action")
            val params = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                if (key != "action") params[key] = json.get(key).toString()
            }
            ParsedCommand(action, params)
        }.getOrNull()
        return cleanText to command
    }
}
