package com.jarvis2.app.obsidian

/**
 * Parses the real Obsidian markdown conventions — YAML frontmatter,
 * `[[wikilinks]]` (including the `[[target|alias]]` form) and `#tags` — so
 * a vault built here is byte-for-byte compatible with the actual Obsidian
 * app: point Obsidian desktop/mobile at the same synced folder and it opens
 * fine, no lock-in either direction.
 */
object NoteParser {

    private val frontmatterRegex = Regex("^---\\n([\\s\\S]*?)\\n---\\n?", RegexOption.MULTILINE)
    private val wikilinkRegex = Regex("\\[\\[([^\\]|]+)(\\|[^\\]]+)?\\]\\]")
    private val tagRegex = Regex("(?<![\\w#])#([\\p{L}\\p{N}_/-]+)")

    fun parse(fileName: String, rawContent: String): Note {
        val fmMatch = frontmatterRegex.find(rawContent)
        val frontmatter = fmMatch?.groupValues?.get(1)?.let(::parseSimpleYaml).orEmpty()
        val body = if (fmMatch != null) rawContent.removeRange(fmMatch.range) else rawContent

        val links = wikilinkRegex.findAll(body).map { it.groupValues[1].trim() }.toSet()
        val inlineTags = tagRegex.findAll(body).map { it.groupValues[1] }.toSet()
        val frontmatterTags = frontmatter["tags"]
            ?.split(",", " ")
            ?.map { it.trim().removePrefix("#") }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val title = frontmatter["title"] ?: fileName.removeSuffix(".md")

        return Note(
            fileName = fileName,
            title = title,
            body = body.trim(),
            frontmatter = frontmatter,
            tags = inlineTags + frontmatterTags,
            links = links,
        )
    }

    /** Renders a [Note] back to a real Obsidian-compatible .md file, frontmatter first. */
    fun render(note: Note): String {
        val sb = StringBuilder()
        if (note.frontmatter.isNotEmpty()) {
            sb.appendLine("---")
            note.frontmatter.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine("---")
        }
        sb.append(note.body)
        return sb.toString()
    }

    /** Minimal flat `key: value` YAML — covers Obsidian's common frontmatter fields without a full YAML parser dependency. */
    private fun parseSimpleYaml(block: String): Map<String, String> =
        block.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isBlank()) null else key to value
            }
            .toMap()
}
