package com.jarvis2.app.obsidian

/**
 * One vault note, parsed from a real Obsidian-format markdown file.
 *
 * [folderPath] is the note's location relative to the vault root ("" for
 * the root itself, "Contacts" or "Contacts/Famille" for a (sub)folder) --
 * this is what lets the vault be organised like a real second brain
 * (dossiers Contacts/Projets/Journal/...) instead of one flat pile of .md
 * files, and is what the graph (obsidian/GraphModel.kt) uses to draw the
 * hub -> dossier -> notes hierarchy.
 */
data class Note(
    val fileName: String,
    val title: String,
    val body: String,
    val frontmatter: Map<String, String>,
    val tags: Set<String>,
    val links: Set<String>, // note titles this note [[links to]]
    val folderPath: String = "",
)

/** A vault-wide edge, used to build the graph. */
data class NoteLink(val from: String, val to: String)
