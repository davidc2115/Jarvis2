package com.jarvis2.app.ai

import com.jarvis2.app.data.db.MemoryDao
import com.jarvis2.app.data.db.MemoryEntity
import kotlin.math.ln

/**
 * On-device "memory" for speed and continuity, per the brief's "mémoire sur
 * smartphone pour plus de rapidité/fluidité": every note-worthy exchange is
 * stored locally (Room, see data/db) and this class retrieves the most
 * relevant past snippets for the current prompt using TF-IDF cosine
 * similarity over a simple tokenizer.
 *
 * This is deliberately *not* a vector-embedding store: a real embedding
 * model adds real weight (another on-device model + more RAM/CPU) for a
 * gain that matters more once the vault has hundreds of notes. TF-IDF over
 * whitespace/punctuation tokens gets most of the practical benefit — recall
 * of "did we already talk about X" — at near-zero cost, and is a clean
 * drop-in point: swap [score] for cosine similarity over embeddings later
 * without touching any call site.
 */
class MemoryStore(private val memoryDao: MemoryDao) {

    private companion object {
        /**
         * Longueur max d'un souvenir stocke. Sans cette borne, un souvenir issu
         * d'une commande "planning"/"contacts"/"mails" (voir ChatViewModel.
         * sendMessage) stockait le TEXTE COMPLET affiche dans le chat -- un
         * planning de plusieurs semaines groupe par jour peut faire plusieurs
         * milliers de caracteres, alors que ce sont des donnees live (deja
         * re-interrogees a chaque demande) qui n'ont pas besoin d'etre
         * integralement retrouvables par recherche TF-IDF. Les 300 premiers
         * caracteres suffisent a retrouver le contexte ("j'ai demande mon
         * planning de la semaine -> ...") sans faire gonfler indefiniment la
         * table memory (voir aussi MemoryDao.recent, meme motivation).
         */
        const val MAX_REMEMBERED_LENGTH = 300

        /** Nombre de souvenirs recents scannes pour le calcul TF-IDF (voir MemoryDao.recent). */
        const val MAX_SCANNED_MEMORIES = 300
    }

    suspend fun remember(text: String, source: String) {
        val capped = text.take(MAX_REMEMBERED_LENGTH)
        memoryDao.insert(MemoryEntity(text = capped, source = source, timestamp = System.currentTimeMillis()))
    }

    suspend fun relevant(query: String, limit: Int = 5): List<MemoryEntity> {
        val all = memoryDao.recent(MAX_SCANNED_MEMORIES)
        if (all.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        val docFrequency = HashMap<String, Int>()
        val docTerms = all.map { tokenize(it.text) }
        docTerms.forEach { terms -> terms.toSet().forEach { docFrequency[it] = (docFrequency[it] ?: 0) + 1 } }

        fun idf(term: String): Double {
            val df = docFrequency[term] ?: return 0.0
            return ln((all.size + 1).toDouble() / (df + 1)) + 1.0
        }

        return all.indices
            .map { i -> all[i] to score(queryTerms, docTerms[i], ::idf) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun score(queryTerms: List<String>, docTerms: List<String>, idf: (String) -> Double): Double {
        if (docTerms.isEmpty()) return 0.0
        val docTf = docTerms.groupingBy { it }.eachCount()
        var dot = 0.0
        queryTerms.toSet().forEach { term ->
            val tf = (docTf[term] ?: 0).toDouble() / docTerms.size
            dot += tf * idf(term)
        }
        return dot
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
}
