package com.fieldmaxx.importer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup

/** [lines] are (text, x, y) in PDF points, y measured from the page bottom. */
data class PdfPageSpec(
    val lines: List<Triple<String, Float, Float>> = emptyList(),
    val images: List<PdfImageSpec> = emptyList(),
    val freeText: List<String> = emptyList(),
    /** (field name, value) text fields; an empty value leaves the field blank. */
    val fields: List<Pair<String, String>> = emptyList(),
)

/** [corrupt] draws an image whose JPEG (DCT) stream is garbage, so decoding it fails. */
class PdfImageSpec(val png: ByteArray, val x: Float, val y: Float, val w: Float, val h: Float, val corrupt: Boolean = false)

/** A real, decodable [w] x [h] PNG filled with one colour. */
fun solidPng(w: Int = 8, h: Int = 8, rgb: Int = 0x2563eb): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, rgb)
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}

/** [emptyAcroForm] adds an AcroForm with no fields even when no page asks for fields. */
fun makePdf(pages: List<PdfPageSpec>, emptyAcroForm: Boolean = false): ByteArray {
    PDDocument().use { doc ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        var form: PDAcroForm? = null
        fun acroForm(): PDAcroForm = form ?: PDAcroForm(doc).also {
            it.defaultResources = PDResources().also { r -> r.put(COSName.getPDFName("Helv"), font) }
            it.defaultAppearance = "/Helv 0 Tf 0 g"
            doc.documentCatalog.acroForm = it
            form = it
        }
        if (emptyAcroForm) acroForm()
        for (spec in pages) {
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                for ((text, x, y) in spec.lines) {
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(x, y)
                    cs.showText(text)
                    cs.endText()
                }
                for (im in spec.images) {
                    val xo = if (im.corrupt) {
                        PDImageXObject(doc, ByteArrayInputStream(ByteArray(32) { 7 }), COSName.DCT_DECODE, 4, 4, 8, PDDeviceRGB.INSTANCE)
                    } else PDImageXObject.createFromByteArray(doc, im.png, "img")
                    cs.drawImage(xo, im.x, im.y, im.w, im.h)
                }
            }
            for ((name, value) in spec.fields) {
                val f = acroForm()
                val field = PDTextField(f)
                field.partialName = name
                f.fields.add(field)
                val w = field.widgets[0]
                w.rectangle = PDRectangle(50f, 100f, 200f, 20f)
                w.page = page
                page.annotations.add(w)
                if (value.isNotEmpty()) field.value = value
            }
            for (text in spec.freeText) {
                val ann = PDAnnotationMarkup()
                ann.cosObject.setName(COSName.SUBTYPE, "FreeText")
                ann.rectangle = PDRectangle(50f, 50f, 400f, 300f)
                ann.contents = text
                page.annotations.add(ann)
            }
        }
        return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
}
