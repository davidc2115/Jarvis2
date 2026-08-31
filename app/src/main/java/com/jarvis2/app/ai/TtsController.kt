package com.jarvis2.app.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lecture a voix haute des reponses de Jarvis (Android TextToSpeech, moteur
 * systeme -- aucun modele a telecharger). Cette classe est entierement
 * nouvelle dans Jarvis2 : il n'existait jusqu'ici AUCUN code de synthese
 * vocale ni de reconnaissance vocale dans ce module (verifie -- aucune
 * trace de TextToSpeech/SpeechRecognizer/RecognizerIntent nulle part), alors
 * que ces deux fonctionnalites existaient dans l'ancienne appli avant la
 * reecriture complete (#182) : elles n'avaient simplement jamais ete
 * reportees depuis. Voir ui/chat/ChatScreen.kt pour le bouton micro (cote
 * reconnaissance) qui utilise l'intent systeme RecognizerIntent plutot
 * qu'une classe dediee, car un simple ActivityResultLauncher suffit.
 */
class TtsController(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.language = Locale.FRENCH
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
