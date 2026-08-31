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
 *
 * Notes can live directly at the vault root or inside (sub)folders --
 * "Contacts", "Contacts/Famille", "Projets", etc, see [Note.folderPath] --
 * so the vault reads as a real organised second brain rather than one flat
 * pile of files, matching how the app's own graph (GraphModel.kt) draws a
 * hub -> dossier -> notes hierarchy.
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

    /** Resolves the on-disk folder for [folderPath] in the default (private) vault, creating it if needed. */
    private fun resolveDefaultDir(folderPath: String): File =
        if (folderPath.isBlank()) defaultVaultDir else File(defaultVaultDir, folderPath).apply { mkdirs() }

    /** Vault-relative folder for a java.io.File that lives somewhere under [defaultVaultDir]. */
    private fun relativeFolderOf(file: File): String =
        file.parentFile?.path?.removePrefix(defaultVaultDir.path)?.trim('/', '\\').orEmpty()

    suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        val external = externalUri()
        if (external != null) {
            storageAccess.listMarkdownFilesRecursive(external).mapNotNull { (folderPath, doc) ->
                val name = doc.name ?: return@mapNotNull null
                val text = context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.readText() ?: return@mapNotNull null
                NoteParser.parse(name, text).copy(folderPath = folderPath)
            }
        } else {
            defaultVaultDir.walkTopDown().filter { it.isFile && it.extension == "md" }.map { f ->
                NoteParser.parse(f.name, f.readText()).copy(folderPath = relativeFolderOf(f))
            }.toList()
        }
    }

    /** Every distinct (sub)folder currently in the vault, e.g. ["Contacts", "Contacts/Famille", "Projets"]. */
    suspend fun listFolders(): List<String> = listNotes().mapNotNull { it.folderPath.takeIf { p -> p.isNotBlank() } }.distinct().sorted()

    /**
     * Creates an empty (sub)folder in the vault -- "Contacts", "Projets",
     * "Contacts/Famille", etc. A no-op (but not an error) if it already
     * exists, so chat commands like "crée un dossier Projets" stay
     * idempotent.
     */
    suspend fun createFolder(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        val external = externalUri()
        if (external != null) {
            storageAccess.ensureFolder(external, folderPath) != null
        } else {
            resolveDefaultDir(folderPath).exists()
        }
    }

    suspend fun saveNote(note: Note): Unit = withContext(Dispatchers.IO) {
        val content = NoteParser.render(note)
        val external = externalUri()
        if (external != null) {
            val dir = storageAccess.ensureFolder(external, note.folderPath) ?: return@withContext
            val existing = dir.findFile(note.fileName)
            val doc = existing ?: dir.createFile("text/markdown", note.fileName) ?: return@withContext
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(content.toByteArray()) }
        } else {
            File(resolveDefaultDir(note.folderPath), note.fileName).writeText(content)
        }
    }

    suspend fun deleteNote(note: Note): Unit = withContext(Dispatchers.IO) {
        val external = externalUri()
        if (external != null) {
            storageAccess.ensureFolder(external, note.folderPath)?.findFile(note.fileName)?.delete()
        } else {
            File(resolveDefaultDir(note.folderPath), note.fileName).delete()
        }
    }

    /**
     * Creates a note, optionally inside a (sub)folder ("Contacts",
     * "Contacts/Famille", ...) -- root by default, unchanged behaviour for
     * every existing caller that doesn't pass [folderPath].
     */
    suspend fun createNote(title: String, body: String = "", tags: Set<String> = emptySet(), folderPath: String = ""): Note {
        val fileName = "${title.replace(Regex("[\\\\/:*?\"<>|]"), "-")}.md"
        val frontmatter = if (tags.isEmpty()) emptyMap() else mapOf("tags" to tags.joinToString(", "))
        val note = Note(fileName, title, body, frontmatter, tags, links = emptySet(), folderPath = folderPath)
        saveNote(note)
        return note
    }

    /**
     * Trouve une note par titre OU nom de fichier, insensible a la casse et
     * a l'extension ".md" -- pour que les commandes du chat ("supprime la
     * note Courses", "renomme Courses en Liste de courses") n'aient pas
     * besoin de connaitre le nom de fichier exact. Cherche dans tout le
     * vault, dossiers inclus.
     */
    suspend fun findByTitleOrFileName(query: String): Note? {
        val target = query.trim().removeSuffix(".md").lowercase()
        return listNotes().find {
            it.fileName.removeSuffix(".md").lowercase() == target || it.title.trim().lowercase() == target
        }
    }

    /**
     * Renomme une note en changeant son titre (et donc son nom de fichier),
     * en gardant le meme dossier. Implemente comme sauvegarde-sous-nouveau-
     * nom + suppression de l'ancien plutot qu'un vrai rename SAF, pour un
     * comportement identique que le vault soit le dossier prive par defaut
     * ou un arbre externe (DocumentFile.renameTo() a des contraintes
     * differentes selon le provider). Retourne false si la note source est
     * introuvable.
     */
    suspend fun renameNote(oldFileName: String, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val existing = listNotes().find { it.fileName == oldFileName } ?: return@withContext false
        val newFileName = "${newTitle.replace(Regex("[\\\\/:*?\"<>|]"), "-")}.md"
        if (newFileName.equals(existing.fileName, ignoreCase = true)) return@withContext true
        val updatedFrontmatter = if (existing.frontmatter.containsKey("title")) {
            existing.frontmatter + ("title" to newTitle)
        } else {
            existing.frontmatter
        }
        saveNote(existing.copy(fileName = newFileName, title = newTitle, frontmatter = updatedFrontmatter))
        deleteNote(existing)
        true
    }

    /**
     * Réécrit le corps d'une note en insérant automatiquement des
     * `[[wikilinks]]` vers les autres notes du vault dont le titre apparaît
     * tel quel dans le texte (première occurrence seulement, et seulement
     * si ce n'est pas déjà un lien) -- le "second brain" se construit tout
     * seul au fil des notes que Jarvis crée, sans que l'utilisateur ait à
     * taper `[[...]]` lui-même. Volontairement *pas* utilisé sur les
     * modifications manuelles depuis l'écran Vault (ui/vault/VaultScreen.kt)
     * : quand l'utilisateur édite son propre texte, on ne le réécrit pas
     * dans son dos.
     */
    suspend fun autoLink(body: String, excludeTitle: String? = null): String {
        val titles = listNotes().map { it.title }.filter { it.isNotBlank() && !it.equals(excludeTitle, ignoreCase = true) }.distinct()
            .sortedByDescending { it.length } // longer titles first, so "Marie Dupont" wins over "Marie"
        var result = body
        for (title in titles) {
            val already = Regex("\\[\\[\\Q$title\\E(\\||\\])", RegexOption.IGNORE_CASE).containsMatchIn(result)
            if (already) continue
            val pattern = Regex("(?<!\\[\\[)\\b\\Q$title\\E\\b(?!\\]\\])", RegexOption.IGNORE_CASE)
            val match = pattern.find(result) ?: continue
            result = result.replaceRange(match.range, "[[$title]]")
        }
        return result
    }
}
