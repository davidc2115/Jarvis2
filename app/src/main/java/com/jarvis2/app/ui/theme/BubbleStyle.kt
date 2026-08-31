package com.jarvis2.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Presets partages entre ui/chat/ChatScreen.kt (rendu des bulles) et
 * ui/settings/SettingsScreen.kt (selection par l'utilisateur), pour garder
 * une seule source de verite entre le nom de preset stocke dans DataStore
 * (voir ui/settings/SettingsViewModel.kt : BUBBLE_SHAPE/BUBBLE_*_COLOR) et
 * son rendu visuel reel.
 */
object BubbleStyle {

    val shapeOptions = listOf("rounded", "square", "pill")
    val colorOptions = listOf("cyan", "gold", "red", "violet", "green")

    fun shapeLabel(id: String): String = when (id) {
        "square" -> "Carrée"
        "pill" -> "Pilule"
        else -> "Arrondie"
    }

    fun colorLabel(id: String): String = when (id) {
        "gold" -> "Or"
        "red" -> "Rouge"
        "violet" -> "Violet"
        "green" -> "Vert"
        else -> "Cyan"
    }

    fun color(id: String): Color = when (id) {
        "gold" -> JarvisGold
        "red" -> JarvisRed
        "violet" -> JarvisViolet
        "green" -> JarvisGreen
        else -> JarvisCyan
    }

    /** [cornerRadius] utilisé seulement pour "rounded" ; "pill" et "square" ont une forme fixe. */
    fun shape(id: String, cornerRadius: Int = 12) = when (id) {
        "square" -> RoundedCornerShape(2.dp)
        "pill" -> RoundedCornerShape(percent = 50)
        else -> RoundedCornerShape(cornerRadius.dp)
    }
}
