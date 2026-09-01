package com.jarvis2.app.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Lecture a voix haute des reponses de Jarvis (Android TextToSpeech, moteur
 * systeme -- aucun modele a telecharger). Cette classe est entierement
 * nouvelle dans Jarvis2 : il n'existait jusqu'ici AUCUN code de synthese
 * vocale ni de reconnaissance vocale dans ce module (verifie -- aucune
 * trace de TextToSpeech/SpeechRecognizer/RecognizerIntent nulle part), alors
 * que ces deux fonctionnalites existaient dans l'ancienne appli avant la
 * reecriture complete (#182) : elles n'avaient simplement jamais ete
 * reportees depuis. Voir ui/chat/ChatScreen.kt pour le bouton micro "un
 * coup" (dictee ponctuelle) et ai/VoiceModeController.kt pour le mode vocal
 * mains-libres en continu (ecoute permanente + coupure de parole).
 */
class TtsController(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) selectNaturalVoice()
    }

    /**
     * Notifie qui parle actuellement (true = Jarvis parle, false = silence)
     * -- utilise par VoiceModeController pour la coupure de parole
     * ("barge-in") : si l'utilisateur commence a parler pendant que Jarvis
     * parle, on doit le savoir pour interrompre tts.stop() immediatement.
     */
    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    init {
        tts.language = Locale.FRENCH
        // Debit et hauteur proches d'une voix humaine normale -- les valeurs
        // par defaut du moteur systeme sonnent souvent plus mecaniques a 1.0f
        // pile ; un tout petit peu plus vite (1.02) et un pitch neutre (1.0)
        // evitent l'effet "robot" sans que ça devienne difficile a comprendre.
        tts.setSpeechRate(1.02f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeakingStateChanged?.invoke(true)
            }

            override fun onDone(utteranceId: String?) {
                onSpeakingStateChanged?.invoke(false)
            }

            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                onSpeakingStateChanged?.invoke(false)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                onSpeakingStateChanged?.invoke(false)
            }
        })
    }

    /**
     * Choisit la meilleure voix francaise disponible sur l'appareil (la plus
     * haute qualite annoncee par le moteur TTS systeme) plutot que de garder
     * la voix par defaut, souvent la plus "robotique" des voix installees --
     * c'est ce que demande l'utilisateur ("parler comme une vraie personne,
     * pas comme un robot"). On evite si possible les voix necessitant une
     * connexion reseau (pour ne pas perdre la voix des que le telephone est
     * hors ligne), sauf si aucune voix locale de bonne qualite n'existe.
     */
    private fun selectNaturalVoice() {
        val frenchVoices = tts.voices?.filter { it.locale.language == "fr" } ?: return
        if (frenchVoices.isEmpty()) return
        val offlineBest = frenchVoices.filterNot { it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
        val best = offlineBest ?: frenchVoices.maxByOrNull { it.quality }
        best?.let { runCatching { tts.voice = it } }
    }

    fun speak(text: String) {
        if (!ready) return
        val clean = stripForSpeech(text)
        if (clean.isBlank()) return
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
    }

    fun stop() = tts.stop()

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /**
         * Retire markdown (**gras**, *italique*, `code`, # titres, puces
         * "- "/"• ", liens [texte](url) -> texte) et emojis avant lecture --
         * sinon le moteur TTS prononce les symboles/codes a voix haute
         * ("etoile etoile", etc., meme bug deja rencontre et corrige une
         * fois dans l'ancienne appli). Iteration par code point (pas par
         * caractere UTF-16) pour gerer correctement les emojis hors du plan
         * multilingue de base (paires de substitution).
         */
        fun stripForSpeech(text: String): String {
            var t = text
            t = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(t) { it.groupValues[1] }
            t = Regex("[*_`#]+").replace(t, "")
            t = Regex("(?m)^[•\\-]\\s+").replace(t, "")

            val sb = StringBuilder(t.length)
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                val charCount = Character.charCount(cp)
                val isEmojiRange = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF ||
                    cp in 0x2190..0x21FF || cp in 0x1F1E6..0x1F1FF || cp == 0xFE0F || cp == 0x200D
                if (!isEmojiRange) sb.appendCodePoint(cp)
                i += charCount
            }
            return Regex("\\s+").replace(sb.toString(), " ").trim()
        }
    }
}
