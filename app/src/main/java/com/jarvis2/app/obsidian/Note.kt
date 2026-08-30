package com.jarvis2.app.obsidian

/** One vault note, parsed from a real Obsidian-format markdown file. */
data class Note(
    val fileName: String,
    val title: String,
    val body: String,
    val frontmatter: Map<String, String>,
    val tags: Set<String>,
    val links: Set<String>, // note titles this note [[links to]]
)

/** A vault-wide edge, used to build the graph. */
data class NoteLink(val from: String, val to: String)
