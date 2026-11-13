package com.fieldmaxx.importer

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** One source of text in a PDF: a single annotation, the form fields of a page, or the page's own text and images. */
data class PdfGroup(val source: GroupSource, val page: Int, val blocks: List<DocBlock>)

/**
 * [images] have ids `pdfimg-<page>-<n>` (page and n are 1-based; n counts images on that page in content-stream order).
 * [pageRenders] have ids `page-<n>` and are PNG.
 */
class PdfExtract(val groups: List<PdfGroup>, val images: List<ExtractedImage>, val pageRenders: List<ExtractedImage>)

private class PlacedText(val y: Float, val x: Float, val width: Float, val fontSize: Float, val text: String)
private class PlacedImage(val top: Float, val png: ByteArray)

private val IGNORED_ANNOTATIONS = setOf("Widget", "Link", "Popup")

fun pdfToGroups(bytes: ByteArray, renderDpi: Float = 144f): PdfExtract {
    try {
        Loader.loadPDF(bytes).use { doc -> return extract(doc, renderDpi) }
    } catch (e: ImportException) {
        throw e
    } catch (e: Exception) {
        throw ImportException("unreadable_pdf", "Could not read this PDF: ${e.message}")
    }
}

private fun extract(doc: PDDocument, renderDpi: Float): PdfExtract {
    val groups = mutableListOf<PdfGroup>()
    val images = mutableListOf<ExtractedImage>()
    val renders = mutableListOf<ExtractedImage>()
    val renderer = PDFRenderer(doc)
    val fieldValues = fieldValuesByPage(doc)

    for (i in 0 until doc.numberOfPages) {
        val pageNo = i + 1
        val page = doc.getPage(i)

        for (ann in page.annotations) {
            if (ann.subtype in IGNORED_ANNOTATIONS) continue
            val paras = paras(ann.contents, pageNo)
            if (paras.isNotEmpty()) groups += PdfGroup(GroupSource.ANNOTATION, pageNo, paras)
        }
        val fields = fieldValues[pageNo].orEmpty()
        if (fields.isNotEmpty()) groups += PdfGroup(GroupSource.FIELD, pageNo, fields)

        val placed = placedImages(page)
        val lines = textLines(doc, pageNo)
        val blocks = mutableListOf<DocBlock>()
        val pendingImages = placed.withIndex().sortedBy { it.value.top }.toMutableList()
        fun flushImagesAbove(y: Float) {
            while (pendingImages.isNotEmpty() && pendingImages.first().value.top <= y) {
                val (idx, im) = pendingImages.removeAt(0)
                val id = "pdfimg-$pageNo-${idx + 1}"
                images += ExtractedImage(id, "image/png", im.png)
                blocks += ImageBlock(id, pageNo)
            }
        }
        for ((y, text) in lines) {
            flushImagesAbove(y)
            blocks += ParaBlock(text, null, pageNo)
        }
        flushImagesAbove(Float.MAX_VALUE)
        if (blocks.isNotEmpty()) groups += PdfGroup(GroupSource.PAGE_TEXT, pageNo, blocks)

        // A page that cannot be rendered loses only its picture; the text already extracted is kept.
        runCatching { png(renderer.renderImageWithDPI(i, renderDpi)) }
            .onSuccess { renders += ExtractedImage("page-$pageNo", "image/png", it) }
    }
    return PdfExtract(groups, images, renders)
}

private fun paras(text: String?, page: Int): List<ParaBlock> =
    text.orEmpty().split(Regex("\r\n|\r|\n")).filter { it.isNotBlank() }.map { ParaBlock(it, null, page) }

private fun fieldValuesByPage(doc: PDDocument): Map<Int, List<ParaBlock>> {
    val form = doc.documentCatalog.acroForm ?: return emptyMap()
    val out = sortedMapOf<Int, MutableList<ParaBlock>>()
    for (field in form.fieldTree) {
        if (field !is PDTerminalField) continue
        val pageNo = (field.widgets.firstNotNullOfOrNull { w -> w.page?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 } } ?: 0) + 1
        val value = try { field.valueAsString } catch (e: Exception) { null }
        val blocks = if (value == null) listOf(ParaBlock("${field.fullyQualifiedName}: unreadable", null, pageNo)) else paras(value, pageNo)
        if (blocks.isNotEmpty()) out.getOrPut(pageNo) { mutableListOf() } += blocks
    }
    return out
}

/** Text lines with their top-down baseline, in reading order. */
private fun textLines(doc: PDDocument, pageNo: Int): List<Pair<Float, String>> {
    val positions = mutableListOf<TextPosition>()
    val stripper = object : PDFTextStripper() {
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            positions += textPositions
        }
    }
    stripper.startPage = pageNo
    stripper.endPage = pageNo
    stripper.getText(doc)

    val sorted = positions.filter { it.unicode.isNotEmpty() }.sortedWith(compareBy({ it.yDirAdj }, { it.xDirAdj }))
    val rows = mutableListOf<MutableList<TextPosition>>()
    for (p in sorted) {
        val row = rows.lastOrNull()
        if (row != null && Math.abs(row[0].yDirAdj - p.yDirAdj) <= 2f) row += p else rows += mutableListOf(p)
    }
    return rows.map { row ->
        row.sortBy { it.xDirAdj }
        val sb = StringBuilder()
        var prevEnd = Float.NaN
        for (p in row) {
            if (!prevEnd.isNaN() && p.xDirAdj - prevEnd > p.fontSizeInPt * 0.2f && !sb.endsWith(" ") && p.unicode != " ") sb.append(' ')
            sb.append(p.unicode)
            prevEnd = p.xDirAdj + p.widthDirAdj
        }
        row[0].yDirAdj to sb.toString().trim()
    }.filter { it.second.isNotEmpty() }
}

private fun placedImages(page: PDPage): List<PlacedImage> {
    val height = page.cropBox.height
    val found = mutableListOf<PlacedImage>()
    val engine = object : PDFStreamEngine() {
        init {
            addOperator(Concatenate(this))
            addOperator(Save(this))
            addOperator(Restore(this))
            addOperator(SetMatrix(this))
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == OperatorName.DRAW_OBJECT && operands.isNotEmpty() && operands[0] is COSName) {
                val xobj = resources.getXObject(operands[0] as COSName)
                if (xobj is PDImageXObject) {
                    val m = graphicsState.currentTransformationMatrix
                    // The image occupies the unit square mapped by the CTM; its top edge is ty + the vertical extent.
                    val top = height - (m.translateY + m.scalingFactorY)
                    // One undecodable image (JPX, odd colour space, corrupt) is skipped; the page render still shows it.
                    try { found += PlacedImage(top, png(xobj.image)) } catch (e: Exception) { }
                } else if (xobj is PDFormXObject) {
                    showForm(xobj)
                }
            } else {
                super.processOperator(operator, operands)
            }
        }
    }
    engine.processPage(page)
    return found
}

private fun png(img: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { check(ImageIO.write(img, "png", it)) { "no PNG writer" } }.toByteArray()
