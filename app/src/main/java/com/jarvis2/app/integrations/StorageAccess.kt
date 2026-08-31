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

    /**
     * Same as [listMarkdownFiles] but walks into subfolders too (Contacts/,
     * Projets/, etc. -- see obsidian/VaultRepository.kt), so a vault
     * organised in folders is fully readable, not just its root. Each
     * result pairs the file with its folder path relative to the vault
     * root ("" for the root itself).
     */
    fun listMarkdownFilesRecursive(treeUri: Uri): List<Pair<String, DocumentFile>> {
        val root = openTree(treeUri) ?: return emptyList()
        val out = mutableListOf<Pair<String, DocumentFile>>()
        fun walk(dir: DocumentFile, relativePath: String) {
            dir.listFiles().forEach { entry ->
                when {
                    entry.isDirectory -> {
                        val childPath = if (relativePath.isEmpty()) entry.name.orEmpty() else "$relativePath/${entry.name}"
                        walk(entry, childPath)
                    }
                    entry.isFile && entry.name?.endsWith(".md") == true -> out.add(relativePath to entry)
                }
            }
        }
        walk(root, "")
        return out
    }

    /**
     * Walks (creating as needed) the chain of subfolders described by
     * [folderPath] ("Contacts", "Contacts/Famille", ...) starting from the
     * vault root, and returns the leaf folder -- e.g. so a note can be
     * written straight into "Contacts/" without the caller worrying about
     * whether that folder already exists in this external SAF tree.
     * Returns the root itself when [folderPath] is blank.
     */
    fun ensureFolder(treeUri: Uri, folderPath: String): DocumentFile? {
        var dir = openTree(treeUri) ?: return null
        if (folderPath.isBlank()) return dir
        folderPath.split("/").filter { it.isNotBlank() }.forEach { segment ->
            dir = dir.findFile(segment)?.takeIf { it.isDirectory } ?: dir.createDirectory(segment) ?: return null
        }
        return dir
    }
}
