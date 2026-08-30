package com.jarvis2.app.obsidian

import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class GraphNode(
    val id: String, // note title
    var position: Offset,
    var velocity: Offset = Offset.Zero,
    val degree: Int, // number of connections, drives node radius
)

data class GraphEdge(val fromId: String, val toId: String)

data class Graph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

/** Builds the full node/link graph of every note in the vault — "vue toile de chaque nœud, point, lien". */
fun buildGraph(notes: List<Note>, seed: Long = 42L): Graph {
    val random = Random(seed)
    val titles = notes.map { it.title }.toSet()

    val edges = notes.flatMap { note ->
        note.links.filter { it in titles && it != note.title }.map { GraphEdge(note.title, it) }
    }.distinct()

    val degree = HashMap<String, Int>()
    edges.forEach { e ->
        degree[e.fromId] = (degree[e.fromId] ?: 0) + 1
        degree[e.toId] = (degree[e.toId] ?: 0) + 1
    }

    val nodes = notes.map { note ->
        GraphNode(
            id = note.title,
            position = Offset(random.nextFloat() * 800f, random.nextFloat() * 800f),
            degree = degree[note.title] ?: 0,
        )
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
