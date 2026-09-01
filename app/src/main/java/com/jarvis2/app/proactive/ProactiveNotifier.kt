package com.jarvis2.app.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Canaux + envoi des notifications proactives de Jarvis (axe #242 --
 * "rappels/alertes contextuels"). Avant ce fichier, l'app n'avait AUCUNE
 * infrastructure de NotificationChannel nulle part (verifie par grep sur
 * tout le module) : les seules notifications systeme etaient celles
 * deleguees a d'autres apps (Horloge via AlarmController). Un rappel avant
 * un evenement ou un briefing du matin doivent au contraire venir de Jarvis
 * lui-meme, d'ou ce petit helper centralise plutot que de dupliquer la
 * creation de canal dans chaque Worker.
 *
 * Deux canaux distincts (et non un seul generique) pour que l'utilisateur
 * puisse desactiver l'un sans l'autre depuis les reglages systeme Android
 * (ex: garder les rappels d'evenements mais couper le briefing du matin).
 */
object ProactiveNotifier {

    const val CHANNEL_REMINDERS = "jarvis_proactive_reminders"
    const val CHANNEL_BRIEFING = "jarvis_proactive_briefing"

    private const val NOTIF_ID_BRIEFING = 9001
    // Les rappels d'evenements utilisent un id derive de l'id d'evenement
    // calendrier (voir ProactiveReminderWorker) pour eviter les collisions
    // entre plusieurs rappels actifs en meme temps.

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Rappels JARVIS",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerte quelques minutes avant un evenement de votre agenda."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BRIEFING,
                "Briefing du matin JARVIS",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Resume quotidien de votre journee, envoye le matin."
            },
        )
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notifyReminder(context: Context, notificationId: Int, title: String, text: String) {
        if (!hasPostPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun notifyBriefing(context: Context, title: String, text: String) {
        if (!hasPostPermission(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_BRIEFING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_BRIEFING, notification)
    }
}
