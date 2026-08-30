package com.jarvis2.app.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import com.jarvis2.app.obsidian.Graph
import com.jarvis2.app.obsidian.GraphNode
import com.jarvis2.app.ui.theme.JarvisCyan
import com.jarvis2.app.ui.theme.JarvisCyanDim
import com.jarvis2.app.ui.theme.JarvisGold
import com.jarvis2.app.ui.theme.JarvisTextSecondary
import kotlin.math.max

/**
 * Interactive Obsidian-style graph view: every note is a node, every
 * `[[wikilink]]` an edge — pan (drag empty space), zoom (pinch), and drag
 * individual nodes to rearrange them, exactly like Obsidian's own graph
 * view. Pure Compose Canvas — no WebView, no external graphing library.
 */
@Composable
fun GraphView(
    graph: Graph,
    modifier: Modifier = Modifier,
    onNodeClick: (GraphNode) -> Unit = {},
) {
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(0.7f) }
    var draggedNodeId by remember { mutableStateOf<String?>(null) }
    val nodesById = remember(graph) { graph.nodes.associateBy { it.id } }

    Canvas(
        modifier = modifier
            .pointerInput(graph) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    if (draggedNodeId == null) {
                        pan += panChange
                        zoom = (zoom * zoomChange).coerceIn(0.2f, 4f)
                    }
                }
            }
            .pointerInput(graph) {
                detectDragGestures(
                    onDragStart = { start ->
                        val worldPoint = (start - pan) / zoom
                        draggedNodeId = graph.nodes.minByOrNull { (it.position - worldPoint).getDistance() }
                            ?.takeIf { (it.position - worldPoint).getDistance() < 40f }
                            ?.id
                    },
                    onDragEnd = { draggedNodeId = null },
                    onDragCancel = { draggedNodeId = null },
                ) { change, dragAmount ->
                    change.consume()
                    val id = draggedNodeId
                    if (id != null) {
                        nodesById[id]?.let { it.position += dragAmount / zoom }
                    } else {
                        pan += dragAmount
                    }
                }
            },
    ) {
        withTransformCanvas(pan, zoom) {
            // Edges first, so nodes render on top.
            graph.edges.forEach { edge ->
                val from = nodesById[edge.fromId] ?: return@forEach
                val to = nodesById[edge.toId] ?: return@forEach
                drawLine(
                    color = JarvisCyanDim,
                    start = from.position,
                    end = to.position,
                    strokeWidth = 1.5f,
                )
            }
            graph.nodes.forEach { node ->
                val radius = 6f + max(node.degree, 0) * 2.5f
                drawCircle(
                    color = if (node.degree > 0) JarvisCyan else JarvisTextSecondary,
                    radius = radius,
                    center = node.position,
                )
                drawCircle(
                    color = JarvisGold,
                    radius = radius,
                    center = node.position,
                    style = Stroke(width = 1f),
                )
            }
        }
    }
}

/** Applies pan+zoom to a DrawScope block without needing an extra Compose dependency for matrix transforms. */
private inline fun DrawScope.withTransformCanvas(
    pan: Offset,
    zoom: Float,
    block: DrawScope.() -> Unit,
) {
    translate(left = pan.x, top = pan.y) {
        scale(scale = zoom, pivot = Offset.Zero) {
            block()
        }
    }
}
