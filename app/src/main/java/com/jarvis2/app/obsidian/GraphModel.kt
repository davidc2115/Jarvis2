package com.jarvis2.app.obsidian

import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** "🧠 Jarvis" is a virtual hub node (not a real note) that folders and root notes hang off, so the toile always has a visible center. */
const val GRAPH_HUB_ID = "🧠 Jarvis"

/** Prefixes a folder path so it can't collide with any real note title, and reads clearly as a folder in the toile. */
fun folderNodeId(folderPath: String): String = "📁 $folderPath"

data class GraphNode(
    val id: String, // note title, or a folder/hub id (see [folderNodeId] / [GRAPH_HUB_ID])
    var position: Offset,
    var velocity: Offset = Offset.Zero,
    val degree: Int, // number of connections, drives node radius
    val isFolder: Boolean = false,
    val isHub: Boolean = false,
    /** Text shown under the node -- the folder path (without the icon prefix) or note title, for [isFolder]/plain nodes. Blank for the hub, which is labelled by [id] directly. */
    val label: String = "",
)

data class GraphEdge(val fromId: String, val toId: String)

data class Graph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

/**
 * Builds the full node/link graph of the vault — "vue toile de chaque nœud,
 * point, lien" — now hierarchical, not flat: a hub node ("🧠 Jarvis") at the
 * center, one node per (sub)folder hanging off the hub, and every note
 * hanging off its folder (or straight off the hub if it lives at the vault
 * root), *in addition to* the existing `[[wikilink]]` edges between notes
 * themselves. [relax] (force-directed layout) turns this edge set into a
 * real hub → dossier → notes star layout on its own -- no bespoke geometry
 * needed, the hub/folder attraction edges naturally pull everything into
 * place while repulsion keeps siblings apart.
 */
fun buildGraph(notes: List<Note>, seed: Long = 42L): Graph {
    val random = Random(seed)
    val titles = notes.map { it.title }.toSet()

    val linkEdges = notes.flatMap { note ->
        note.links.filter { it in titles && it != note.title }.map { GraphEdge(note.title, it) }
    }

    val folders = notes.mapNotNull { it.folderPath.takeIf { p -> p.isNotBlank() } }.distinct().sorted()
    val hierarchyEdges = buildList {
        if (notes.isNotEmpty()) {
            folders.forEach { add(GraphEdge(GRAPH_HUB_ID, folderNodeId(it))) }
            notes.forEach { note ->
                val parent = if (note.folderPath.isBlank()) GRAPH_HUB_ID else folderNodeId(note.folderPath)
                add(GraphEdge(parent, note.title))
            }
        }
    }

    val edges = (hierarchyEdges + linkEdges).distinct()

    val degree = HashMap<String, Int>()
    edges.forEach { e ->
        degree[e.fromId] = (degree[e.fromId] ?: 0) + 1
        degree[e.toId] = (degree[e.toId] ?: 0) + 1
    }

    fun randomPos() = Offset(random.nextFloat() * 800f, random.nextFloat() * 800f)

    val nodes = buildList {
        if (notes.isNotEmpty()) {
            add(GraphNode(id = GRAPH_HUB_ID, position = Offset(500f, 500f), degree = degree[GRAPH_HUB_ID] ?: 0, isHub = true, label = GRAPH_HUB_ID))
        }
        folders.forEach { f ->
            val id = folderNodeId(f)
            add(GraphNode(id = id, position = randomPos(), degree = degree[id] ?: 0, isFolder = true, label = "📁 $f"))
        }
        notes.forEach { note ->
            add(GraphNode(id = note.title, position = randomPos(), degree = degree[note.title] ?: 0, label = note.title))
        }
    }

    return Graph(nodes, edges)
}

/**
 * Small force-directed layout (Fruchterman-Reingold-ish): nodes repel each
 * other, edges pull their endpoints together, everything is pulled gently
 * toward the center so isolated notes don't drift off-screen. Runs a fixed
 * number of relaxation steps up front (see GraphView.kt), then the user can
 * still drag nodes by hand.
 */
fun relax(graph: Graph, iterations: Int = 150, width: Float = 1000f, height: Float = 1000f): Graph {
    val nodesById = graph.nodes.associateBy { it.id }
    val k = kotlin.math.sqrt((width * height) / max(graph.nodes.size, 1))

    repeat(iterations) {
        val displacement = HashMap<String, Offset>()
        graph.nodes.forEach { displacement[it.id] = Offset.Zero }

        // Repulsion between every pair — fine for the note counts a personal vault realistically has (low hundreds).
        for (i in graph.nodes.indices) {
            for (j in i + 1 until graph.nodes.size) {
                val a = graph.nodes[i]
                val b = graph.nodes[j]
                val delta = a.position - b.position
                val dist = max(delta.getDistance(), 0.01f)
                val force = (k * k) / dist
                val dir = delta / dist
                displacement[a.id] = displacement[a.id]!! + dir * force
                displacement[b.id] = displacement[b.id]!! - dir * force
            }
        }

        // Attraction along edges.
        graph.edges.forEach { edge ->
            val a = nodesById[edge.fromId] ?: return@forEach
            val b = nodesById[edge.toId] ?: return@forEach
            val delta = a.position - b.position
            val dist = max(delta.getDistance(), 0.01f)
            val force = (dist * dist) / k
            val dir = delta / dist
            displacement[a.id] = displacement[a.id]!! - dir * force
            displacement[b.id] = displacement[b.id]!! + dir * force
        }

        val center = Offset(width / 2f, height / 2f)
        graph.nodes.forEach { node ->
            val toCenter = (center - node.position) * 0.01f
            val d = (displacement[node.id] ?: Offset.Zero) + toCenter
            val limited = limit(d, 30f)
            node.position = Offset(
                x = min(max(node.position.x + limited.x, 0f), width),
                y = min(max(node.position.y + limited.y, 0f), height),
            )
        }
    }
    return graph
}

private fun limit(offset: Offset, max: Float): Offset {
    val dist = offset.getDistance()
    return if (dist > max && dist > 0f) offset * (max / dist) else offset
}
