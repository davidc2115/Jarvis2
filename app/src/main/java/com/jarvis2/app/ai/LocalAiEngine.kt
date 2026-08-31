package com.jarvis2.app.ai

/** One exchange in the running conversation, used as context for the model. */
data class Turn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** Capability flags an engine can report so the UI/router can adapt. */
data class EngineInfo(
    val id: String,
    val displayName: String,
    val isFullyLocal: Boolean,
    val isReady: Boolean,
    val notes: String = "",
)

/**
 * Common contract for any on-device generation backend. Jarvis2 never talks
 * to a cloud LLM: every implementation of this interface must run entirely
 * on-device. The only network calls anywhere in the app are the explicit,
 * user-triggered [com.jarvis2.app.ai.WebSearchTool] fallback for factual
 * lookups the local model can't answer — never for the model itself.
 */
interface LocalAiEngine {

    suspend fun prepare(): Result<Unit>

    fun info(): EngineInfo

    /**
     * Generate a full reply to [prompt] given the recent [history]. Returns
     * the complete text — streaming is exposed separately via
     * [generateStreaming] for engines that support it (both fall back to a
     * single emission when the underlying SDK has no token streaming).
     */
    suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String>

    /** Streaming variant: emits growing partial text, last emission is final. */
    fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): kotlinx.coroutines.flow.Flow<String>

    fun release()
}

const val JARVIS_SYSTEM_PROMPT = """
Tu es Jarvis, l'assistant vocal/texte 100% local du smartphone de l'utilisateur,
inspiré de l'IA d'Iron Man : précis, concis, légèrement formel, jamais bavard
pour rien. Tu peux déclencher des actions réelles sur le téléphone (torche,
Bluetooth, agenda, contacts, fichiers, GPS...) via des commandes que
l'application interprète séparément — quand l'utilisateur te demande une
action, confirme-la brièvement plutôt que de décrire comment tu la ferais.
Si tu ne sais vraiment pas répondre à une question factuelle, dis-le
clairement plutôt que d'inventer : l'application proposera alors une
recherche web à l'utilisateur.
Tu n'as JAMAIS accès aux vrais contacts, numéros de téléphone ou emails de
l'utilisateur (seule l'application, via ses propres commandes, y a accès) :
si on te demande un numéro, un email ou toute coordonnée d'une personne,
NE JAMAIS EN INVENTER UN (jamais de numéro ni d'adresse du type
"nom@exemple.com") -- dis simplement que tu n'y as pas accès directement et
que la commande dédiée (ex: "numéro de X") s'en charge.
"""
