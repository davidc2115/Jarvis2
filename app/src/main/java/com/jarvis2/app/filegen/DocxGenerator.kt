package com.jarvis2.app.filegen

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-written minimal OOXML (.docx) generator — no Apache POI. POI relies
 * on java.awt for parts of its API surface, which is unreliable/heavy on
 * Android; a .docx is just a zip of well-defined XML parts (ECMA-376), so
 * writing exactly the parts needed for "title + paragraphs" directly keeps
 * this dependency-free and small. Opens cleanly in Word, Google Docs and
 * LibreOffice Writer.
 */
class DocxGenerator(private val context: Context) {

    fun generateFromParagraphs(title: String, paragraphs: List<String>): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(outputDir(context), "jarvis_${stamp}.docx")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypesXml())
            writeEntry(zip, "_rels/.rels", rootRelsXml())
            writeEntry(zip, "word/_rels/document.xml.rels", documentRelsXml())
            writeEntry(zip, "word/styles.xml", stylesXml())
            writeEntry(zip, "word/document.xml", documentXml(title, paragraphs))
        }
        return file
    }

    fun generateFromText(title: String, body: String): File =
        generateFromParagraphs(title, body.split("\n"))

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun contentTypesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun documentRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
            <w:name w:val="Normal"/>
          </w:style>
          <w:style w:type="paragraph" w:styleId="Title">
            <w:name w:val="Title"/>
            <w:basedOn w:val="Normal"/>
            <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
          </w:style>
        </w:styles>
    """.trimIndent()

    private fun documentXml(title: String, paragraphs: List<String>): String {
        val titlePara = """<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t xml:space="preserve">${esc(title)}</w:t></w:r></w:p>"""
        val bodyParas = paragraphs.joinToString("\n") { line ->
            """<w:p><w:r><w:t xml:space="preserve">${esc(line)}</w:t></w:r></w:p>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $titlePara
                $bodyParas
                <w:sectPr/>
              </w:body>
            </w:document>
        """.trimIndent()
    }
}
