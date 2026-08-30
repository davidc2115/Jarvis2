package com.jarvis2.app.obsidian

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.integrations.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val VAULT_URI_KEY = stringPreferencesKey("obsidian_vault_uri")

/**
 * Owns the Obsidian vault: a plain folder of `.md` files, either the app's
 * own private `obsidian_vault/` folder (default — works with zero setup) or
 * a folder the user picked via SAF in Settings, e.g. one already synced by
 * Syncthing/Obsidian Sync so the same vault is shared with the desktop app.
 */
class VaultRepository(
    private val context: Context,
    private val storageAccess: StorageAccess,
    private val settings: SettingsDataStore,
) {

    private val defaultVaultDir: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("obsidian_vault").apply { mkdirs() }

    suspend fun setExternalVaultUri(uri: Uri) {
        storageAccess.persistTreePermission(uri)
        settings.set(VAULT_URI_KEY, uri.toString())
    }

    suspend fun clearExternalVault() = settings.remove(VAULT_URI_KEY)

    private suspend fun externalUri(): Uri? = settings.get(VAULT_URI_KEY)?.let(Uri::parse)

    suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        val external = externalUri()
        if (external != null) {
            storageAccess.listMarkdownFiles(external).mapNotNull { doc ->
                val name = doc.name ?: return@mapNotNull null
                val text = context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.readText() ?: return@mapNotNull null
                NoteParser.parse(name, text)
            }
        } else {
            defaultVaultDir.listFiles { f -> f.extension == "md" }?.map { f ->
                NoteParser.parse(f.name, f.readText())
            }.orEmpty()
        }
    }

    suspend fun saveNote(note: Note): Unit = withContext(Dispatchers.IO) {
        val content = NoteParser.render(note)
        val external = externalUri()
        if (external != null) {
            val tree = storageAccess.openTree(external) ?: return@withContext
            val existing = tree.findFile(note.fileName)
            val doc = existing ?: tree.createFile("text/markdown", note.fileName) ?: return@withContext
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(content.toByteArray()) }
        } else {
            File(defaultVaultDir, note.fileName).writeText(content)
        }
    }

    suspend fun deleteNote(fileName: String): Unit = withContext(Dispatchers.IO) {
        val external = externalUri()
        if (external != null) {
            storageAccess.openTree(external)?.findFile(fileName)?.delete()
        } else {
            File(defaultVaultDir, fileName).delete()
        }
    }

    suspend fun createNote(title: String, body: String = "", tags: Set<String> = emptySet()): Note {
        val fileName = "${title.replace(Regex("[\\\\/:*?\"<>|]"), "-")}.md"
        val frontmatter = if (tags.isEmpty()) emptyMap() else mapOf("tags" to tags.joinToString(", "))
        val note = Note(fileName, title, body, frontmatter, tags, links = emptySet())
        saveNote(note)
        return note
    }
}
