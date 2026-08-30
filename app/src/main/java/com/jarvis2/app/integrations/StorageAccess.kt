package com.jarvis2.app.integrations

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Broader storage access (browsing/reading/writing folders the user picks,
 * e.g. an existing Obsidian vault folder or a "export here" target) goes
 * through the Storage Access Framework rather than broad filesystem
 * permissions, per current Android policy (MANAGE_EXTERNAL_STORAGE is
 * reserved for file-manager-class apps and would get this app rejected
 * from Play review). The user grants one folder at a time via
 * ACTION_OPEN_DOCUMENT_TREE (triggered from ui/settings/SettingsScreen.kt);
 * the returned URI is persisted with takePersistableUriPermission so access
 * survives app restarts.
 */
class StorageAccess(private val context: Context) {

    fun persistTreePermission(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    fun openTree(treeUri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> =
        openTree(treeUri)?.listFiles()?.filter { it.isFile && it.name?.endsWith(".md") == true }.orEmpty()
}
