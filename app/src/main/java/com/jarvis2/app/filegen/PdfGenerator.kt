package com.jarvis2.app.filegen

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Real, dependency-free PDF generation via the Android framework's PdfDocument. */
class PdfGenerator(private val context: Context) {

    private val pageWidth = 595 // A4 @ 72dpi
    private val pageHeight = 842
    private val margin = 48f

    fun generateFromText(title: String, body: String): File {
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 12f }
        val lineHeight = 16f

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        canvas.drawText(title, margin, y, titlePaint)
        y += lineHeight * 2

        wrapText(body, bodyPaint, pageWidth - margin * 2).forEach { line ->
            if (y > pageHeight - margin) {
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }
            canvas.drawText(line, margin, y, bodyPaint)
            y += lineHeight
        }
        document.finishPage(page)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(outputDir(context), "jarvis_${stamp}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var current = StringBuilder()
            paragraph.split(" ").forEach { word ->
                val candidate = if (current.isEmpty()) word else "${current} $word"
                if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            lines.add(current.toString())
        }
        return lines
    }
}
