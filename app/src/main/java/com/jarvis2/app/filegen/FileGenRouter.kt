package com.jarvis2.app.filegen

import android.content.Context
import java.io.File

/** Where every generator writes its output: Context.getExternalFilesDir(null)/jarvis_files, mirrored in file_paths.xml. */
internal fun outputDir(context: Context): File =
    (context.getExternalFilesDir(null) ?: context.filesDir).resolve("jarvis_files").apply { mkdirs() }

/** Bag of all file generators, handed to [com.jarvis2.app.ai.CommandRouter] and the Files screen. */
class FileGenRouter(
    val pdf: PdfGenerator,
    val zip: ZipGenerator,
    val kml: KmlGenerator,
    val docx: DocxGenerator,
    val xlsx: XlsxGenerator,
)
