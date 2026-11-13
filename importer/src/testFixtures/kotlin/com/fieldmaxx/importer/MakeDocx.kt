package com.fieldmaxx.importer

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [abstractId] lets several lists share one abstractNum; [startOverride] adds a `w:lvlOverride/w:startOverride` on level 0. */
data class DocxList(
    val numId: Int,
    val start: Int,
    val fmt: String /* decimal|upperLetter */,
    val lvlText: String,
    val abstractId: Int = numId,
    val startOverride: Int? = null,
)

sealed interface DocxItem {
    data class Para(val text: String, val numId: Int? = null, val ilvl: Int = 0, val styleNumId: Int? = null) : DocxItem
    class Image(val png: ByteArray) : DocxItem
    data class Table(val cells: List<List<String>>) : DocxItem
}

private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun paraXml(text: String, numId: Int?, ilvl: Int, styleNumId: Int?): String {
    val ppr = StringBuilder()
    if (styleNumId != null) ppr.append("""<w:pStyle w:val="NumberedStyle$styleNumId"/>""")
    if (numId != null) ppr.append("""<w:numPr><w:ilvl w:val="$ilvl"/><w:numId w:val="$numId"/></w:numPr>""")
    val pPr = if (ppr.isEmpty()) "" else "<w:pPr>$ppr</w:pPr>"
    return """<w:p>$pPr<w:r><w:t xml:space="preserve">${esc(text)}</w:t></w:r></w:p>"""
}

/** Builds a minimal but valid .docx: [lists] become numbering definitions, [body] the document content in order. */
fun makeDocx(lists: List<DocxList>, body: List<DocxItem>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun put(name: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        }
        fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
        val head = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

        val bodyXml = StringBuilder()
        val rels = StringBuilder()
        val images = ArrayList<ByteArray>()
        for (item in body) when (item) {
            is DocxItem.Para -> bodyXml.append(paraXml(item.text, item.numId, item.ilvl, item.styleNumId))
            is DocxItem.Image -> {
                images.add(item.png)
                val n = images.size
                rels.append("""<Relationship Id="rIdImg$n" Type="$NS_R/image" Target="media/image$n.png"/>""")
                bodyXml.append(
                    """<w:p><w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">""" +
                        """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
                        """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:blipFill><a:blip r:embed="rIdImg$n"/></pic:blipFill></pic:pic>""" +
                        """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
                )
            }
            is DocxItem.Table -> {
                bodyXml.append("<w:tbl>")
                for (row in item.cells) {
                    bodyXml.append("<w:tr>")
                    for (cell in row) bodyXml.append("<w:tc>${paraXml(cell, null, 0, null)}</w:tc>")
                    bodyXml.append("</w:tr>")
                }
                bodyXml.append("</w:tbl>")
            }
        }

        put(
            "[Content_Types].xml",
            """$head<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>""" +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                """<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>""" +
                """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
        )
        put(
            "_rels/.rels",
            """$head<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$NS_R/officeDocument" Target="word/document.xml"/></Relationships>""",
        )
        put("word/document.xml", """$head<w:document xmlns:w="$NS_W" xmlns:r="$NS_R"><w:body>$bodyXml</w:body></w:document>""")
        val numbering = StringBuilder()
        for (l in lists.distinctBy { it.abstractId }) {
            numbering.append(
                """<w:abstractNum w:abstractNumId="${l.abstractId}"><w:lvl w:ilvl="0"><w:start w:val="${l.start}"/>""" +
                    """<w:numFmt w:val="${l.fmt}"/><w:lvlText w:val="${esc(l.lvlText)}"/></w:lvl></w:abstractNum>""",
            )
        }
        for (l in lists) {
            val ov = l.startOverride?.let { """<w:lvlOverride w:ilvl="0"><w:startOverride w:val="$it"/></w:lvlOverride>""" } ?: ""
            numbering.append("""<w:num w:numId="${l.numId}"><w:abstractNumId w:val="${l.abstractId}"/>$ov</w:num>""")
        }
        put("word/numbering.xml", """$head<w:numbering xmlns:w="$NS_W">$numbering</w:numbering>""")
        val styles = lists.joinToString("") {
            """<w:style w:type="paragraph" w:styleId="NumberedStyle${it.numId}"><w:name w:val="NumberedStyle${it.numId}"/>""" +
                """<w:pPr><w:numPr><w:numId w:val="${it.numId}"/></w:numPr></w:pPr></w:style>"""
        }
        put("word/styles.xml", """$head<w:styles xmlns:w="$NS_W">$styles</w:styles>""")
        put(
            "word/_rels/document.xml.rels",
            """$head<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""",
        )
        images.forEachIndexed { i, png -> put("word/media/image${i + 1}.png", png) }
    }
    return out.toByteArray()
}
