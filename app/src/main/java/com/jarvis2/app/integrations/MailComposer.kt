package com.jarvis2.app.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Composing/sending mail from a third-party app without the user's mail
 * account credentials is intentionally not possible on Android (no API for
 * it — that's a deliberate anti-spam boundary, not a gap in this app). The
 * correct, OS-sanctioned pattern is an ACTION_SEND(TO) intent that hands off
 * to whichever mail app the user has installed, prefilled with subject/body
 * and optional attachments; the user hits send themselves.
 */
class MailComposer(private val context: Context) {

    fun composeMail(to: String? = null, subject: String, body: String, attachment: File? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (attachment != null) "*/*" else "message/rfc822"
            if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (attachment != null) {
                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", attachment)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Envoyer avec…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
