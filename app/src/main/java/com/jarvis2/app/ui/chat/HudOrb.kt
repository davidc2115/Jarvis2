package com.jarvis2.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis2.app.ai.VoiceState
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisGold
import com.jarvis2.app.ui.theme.JarvisViolet
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Fond HUD anime (coeur lumineux + anneaux + particules en orbite), inspire du
 * style "Neural Core"/"Toile Obsidian cosmos" de la premiere version de
 * l'appli -- demande explicite de l'utilisateur ("un veritable systeme
 * JARVIS/Ironman comme la premiere version, mais en local"). Contrairement a
 * l'ancien orb (ecran dedie/VaultGraphActivity), celui-ci vit EN PERMANENCE
 * derriere le chat, comme fond ambiant semi-transparent -- c'est ce qui donne
 * l'impression d'un "assistant vivant" plutot qu'un simple ecran de
 * messages, sans pour autant gener la lecture (voir alpha dans ChatScreen).
 *
 * Reagit en direct a l'etat reel de JARVIS (pas juste decoratif) :
 *  - OFF/idle, ne reflechit pas : respiration lente cyan (au repos).
 *  - thinking (isThinking) : particules qui accelerent en orbite, teinte
 *    violette -- le moteur IA local travaille.
 *  - LISTENING (mode vocal) : anneau or qui pulse au rythme d'une "ecoute".
 *  - SPEAKING (mode vocal, Jarvis parle) : anneau or, pulsation plus rapide
 *    et amplitude plus marquee -- effet "voix qui sort".
 *
 * Implementation deliberement legere (Canvas + trigonometrie, pas de
 * dependance graphique lourde) pour rester fluide sur un telephone milieu de
 * gamme, coherent avec le reste de l'appli 100% locale.
 */
@Composable
fun HudOrbBackground(
    voiceState: VoiceState,
    thinking: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val transition = rememberInfiniteTransition(label = "hud-orb")

    // Respiration lente du coeur (toujours active, meme au repos) : donne
    // l'impression que JARVIS est "vivant" meme sans activite.
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    // Rotation continue des particules -- vitesse variable selon l'etat
    // (plus rapide quand JARVIS reflechit/parle, ce qui donne un retour
    // visuel immediat sur ce qu'il est en train de faire).
    val rotationPeriodMs = when {
        thinking -> 2200
        voiceState == VoiceState.SPEAKING -> 1800
        voiceState == VoiceState.LISTENING -> 3000
        else -> 9000
    }
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(rotationPeriodMs, easing = LinearEasing)),
        label = "rotation",
    )

    // Pulsation de l'anneau exterieur -- marquee seulement en mode vocal
    // (ecoute/parole), quasi imperceptible au repos.
    val pulsePeriodMs = if (voiceState == VoiceState.SPEAKING) 500 else 1100
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(pulsePeriodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val coreColor = when {
        voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING -> JarvisGold
        thinking -> JarvisViolet
        else -> JarvisCyan
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height * 0.32f)
        val baseRadius = min(size.width, size.height) * 0.22f

        // Coeur lumineux : plusieurs cercles concentriques degrades pour un
        // effet de lueur (glow) sans dependre d'un shader/blur natif.
        val glowLayers = 5
        for (layer in glowLayers downTo 1) {
            val t = layer / glowLayers.toFloat()
            val r = baseRadius * (0.4f + 0.6f * t) * (0.92f + 0.08f * breathe)
            drawCircle(
                color = coreColor.copy(alpha = (0.22f * (1f - t) + 0.03f) * alpha),
                radius = r,
                center = center,
            )
        }
        drawCircle(color = coreColor.copy(alpha = 0.85f * alpha), radius = baseRadius * 0.12f, center = center)

        // Anneau exterieur pulsant (mode vocal) -- rayon et opacite varient
        // avec [pulse] pour simuler un "battement" au rythme de la voix.
        val ringRadius = baseRadius * (1.6f + 0.25f * pulse)
        drawCircle(
            color = coreColor.copy(alpha = (0.35f - 0.15f * pulse) * alpha),
            radius = ringRadius,
            center = center,
            style = Stroke(width = 2f),
        )

        // Anneau fixe fin (toujours visible, repere visuel de l'orbite).
        drawCircle(
            color = JarvisCyan.copy(alpha = 0.18f * alpha),
            radius = baseRadius * 2.1f,
            center = center,
            style = Stroke(width = 1f),
        )

        // Particules en orbite -- nombre et vitesse refletent l'activite
        // (plus de particules visibles = JARVIS "occupe").
        val particleCount = if (thinking) 10 else 6
        for (i in 0 until particleCount) {
            val angle = Math.toRadians((rotation + i * (360f / particleCount)).toDouble())
            val orbitRadius = baseRadius * (2.1f + 0.15f * (i % 3))
            val px = center.x + (cos(angle) * orbitRadius).toFloat()
            val py = center.y + (sin(angle) * orbitRadius).toFloat()
            drawCircle(
                color = coreColor.copy(alpha = 0.55f * alpha),
                radius = 2.2f,
                center = Offset(px, py),
            )
        }
    }
}
