package com.jarvis2.app.ui.graph

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.toArgb
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

    val labelPaint = remember {
        Paint().apply {
            color = JarvisTextSecondary.toArgb()
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
    }
    val hubLabelPaint = remember { Paint(labelPaint).apply { color = JarvisGold.toArgb(); textSize = 30f; typeface = Typeface.DEFAULT_BOLD } }
    val folderLabelPaint = remember { Paint(labelPaint).apply { color = JarvisGold.toArgb() } }

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
            // Hub et dossiers d'abord (dessinés "en dessous"), puis les notes -- voir
            // obsidian/GraphModel.kt pour la hierarchie hub -> dossier -> notes.
            graph.nodes.filter { it.isHub }.forEach { node -> drawHubNode(node) }
            graph.nodes.filter { it.isFolder }.forEach { node -> drawFolderNode(node) }
            graph.nodes.filter { !it.isHub && !it.isFolder }.forEach { node -> drawNoteNode(node) }

            graph.nodes.forEach { node ->
                val paint = when {
                    node.isHub -> hubLabelPaint
                    node.isFolder -> folderLabelPaint
                    else -> labelPaint
                }
                val radius = nodeRadius(node)
                drawContext.canvas.nativeCanvas.drawText(node.label, node.position.x, node.position.y + radius + 26f, paint)
            }
        }
    }
}

private fun nodeRadius(node: GraphNode): Float = when {
    node.isHub -> 22f
    node.isFolder -> 12f + max(node.degree, 0) * 1.5f
    else -> 6f + max(node.degree, 0) * 2.5f
}

private fun DrawScope.drawHubNode(node: GraphNode) {
    val radius = nodeRadius(node)
    drawCircle(color = JarvisGold, radius = radius, center = node.position)
    drawCircle(color = JarvisCyan, radius = radius, center = node.position, style = Stroke(width = 2f))
}

/** Folders are drawn as squares (not circles) so they read as a distinct "📁" shape in the toile, per the folder icon this graph is meant to show -- see [com.jarvis2.app.obsidian.folderNodeId]. */
private fun DrawScope.drawFolderNode(node: GraphNode) {
    val radius = nodeRadius(node)
    val topLeft = Offset(node.position.x - radius, node.position.y - radius)
    val size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
    drawRect(color = JarvisGold.copy(alpha = 0.25f), topLeft = topLeft, size = size)
    drawRect(color = JarvisGold, topLeft = topLeft, size = size, style = Stroke(width = 2f))
}

private fun DrawScope.drawNoteNode(node: GraphNode) {
    val radius = nodeRadius(node)
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
