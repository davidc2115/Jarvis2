package com.jarvis2.app.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Etat affichable du mode vocal (voir ui/chat/ChatScreen.kt pour l'indicateur visuel). */
enum class VoiceState { OFF, LISTENING, SPEAKING }

/**
 * Mode vocal mains-libres : contrairement au bouton micro "un coup" de
 * ChatScreen.kt (RecognizerIntent.ACTION_RECOGNIZE_SPEECH lance depuis un
 * ActivityResultLauncher, un aller-retour puis fin), ce controleur garde le
 * micro ouvert EN CONTINU via l'API bas niveau SpeechRecognizer et relance
 * automatiquement une nouvelle session d'ecoute apres chaque resultat/erreur
 * -- "reste en mode vocal" tant que l'utilisateur n'a pas explicitement
 * appuye sur le bouton pour l'arreter, au lieu de devoir rappuyer sur le
 * micro a chaque tour de conversation.
 *
 * Coupure de parole ("barge-in") : si du texte est reconnu (meme partiel)
 * PENDANT que Jarvis est en train de parler (voir TtsController.
 * onSpeakingStateChanged), on interrompt immediatement la voix (tts.stop())
 * pour laisser la parole a l'utilisateur -- exactement le comportement
 * demande ("si je parle il s'arrête pour écouter ma demande").
 *
 * Limite connue et assumee : sans annulation d'echo dediee (le telephone
 * n'a pas forcement l'AEC materiel/logiciel actif entre le haut-parleur et
 * le micro pour cette combinaison TTS+SpeechRecognizer), le micro peut dans
 * de rares cas capter la propre voix de Jarvis et la traiter comme une
 * coupure de parole. C'est un compromis assume plutot que de bloquer toute
 * ecoute pendant que Jarvis parle (ce qui rendrait la coupure de parole
 * demandee par l'utilisateur impossible).
 *
 * SpeechRecognizer doit etre cree/utilise depuis le thread principal
 * (contrainte Android) -- toutes les operations passent donc par un Handler
 * lie au main looper, meme si le controleur est sollicite depuis une
 * coroutine (ViewModel).
 */
class VoiceModeController(
    private val context: Context,
    private val tts: TtsController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile private var active = false

    @Volatile private var currentlySpeaking = false

    private val _state = MutableStateFlow(VoiceState.OFF)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Appele avec le texte final reconnu -- l'appelant (ChatViewModel) l'envoie comme un message normal. */
    var onFinalSpeech: ((String) -> Unit)? = null

    init {
        tts.onSpeakingStateChanged = { speaking ->
            currentlySpeaking = speaking
            if (active) {
                _state.value = if (speaking) VoiceState.SPEAKING else VoiceState.LISTENING
            }
        }
    }

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Demarre le mode vocal mains-libres. Renvoie false (sans rien faire) si la permission micro manque ou si aucun moteur de reconnaissance n'est dispo. */
    fun start(): Boolean {
        if (active) return true
        if (!hasMicPermission() || !isRecognitionAvailable()) return false
        active = true
        mainHandler.post { setupAndListen() }
        return true
    }

    fun stop() {
        active = false
        mainHandler.post {
            recognizer?.setRecognitionListener(null)
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            _state.value = VoiceState.OFF
        }
    }

    private fun setupAndListen() {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        startListening()
    }

    private fun startListening() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = if (currentlySpeaking) VoiceState.SPEAKING else VoiceState.LISTENING
        runCatching { recognizer?.startListening(intent) }
    }

    /** Relance une nouvelle session d'ecoute apres un court delai (evite une boucle d'appels trop rapide en cas d'erreur repetee). */
    private fun restartListening(delayMs: Long = 300) {
        if (!active) return
        mainHandler.postDelayed({ startListening() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            if (currentlySpeaking) tts.stop()
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            restartListening()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                onFinalSpeech?.invoke(text)
            }
            // Reste en mode vocal : relance systematiquement l'ecoute, que la
            // phrase ait ete comprise ou non, plutot que d'exiger un nouvel
            // appui sur le micro pour chaque tour.
            restartListening(delayMs = 400)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank() && currentlySpeaking) {
                tts.stop()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
