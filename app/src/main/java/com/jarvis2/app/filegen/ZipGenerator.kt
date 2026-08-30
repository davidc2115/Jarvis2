package com.jarvis2.app.filegen

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips a folder (or list of files) into a single archive using plain java.util.zip — no extra dependency. */
class ZipGenerator(private val context: Context) {

    fun zipFiles(files: List<File>, archiveName: String): File {
        val target = File(outputDir(context), if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip")
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            files.forEach { file -> addToZip(zos, file, file.name) }
        }
        return target
    }

    fun zipDirectory(directory: File, archiveName: String): File {
        val target = File(outputDir(context), if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip")
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val relativePath = file.relativeTo(directory).path
                addToZip(zos, file, relativePath)
            }
        }
        return target
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
