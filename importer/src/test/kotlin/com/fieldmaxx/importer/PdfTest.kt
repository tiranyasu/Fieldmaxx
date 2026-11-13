package com.fieldmaxx.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfTest {
    @Test fun freeTextIsItsOwnGroup() {
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(freeText = listOf(AERIAL)))))
        val g = x.groups.single { it.source == GroupSource.ANNOTATION }
        assertEquals(1, g.page)
        assertTrue(g.blocks.filterIsInstance<ParaBlock>().size >= 7)
    }

    @Test fun pageTextTopToBottom() {
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(lines = listOf(Triple("second", 50f, 600f), Triple("first", 50f, 700f))))))
        val g = x.groups.single { it.source == GroupSource.PAGE_TEXT }
        assertEquals(listOf("first", "second"), g.blocks.filterIsInstance<ParaBlock>().map { it.text })
    }

    @Test fun imageBetweenLines() {
        val img = PdfImageSpec(solidPng(), 50f, 500f, 40f, 40f)
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(lines = listOf(Triple("top", 50f, 700f), Triple("bottom", 50f, 400f)), images = listOf(img)))))
        val g = x.groups.single { it.source == GroupSource.PAGE_TEXT }
        assertEquals(listOf("ParaBlock", "ImageBlock", "ParaBlock"), g.blocks.map { it::class.simpleName })
        assertEquals(1, x.images.size)
        assertEquals((g.blocks[1] as ImageBlock).imageId, x.images[0].id)
        assertEquals("pdfimg-1-1", x.images[0].id)
    }

    @Test fun pngRenderPerPage() {
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(), PdfPageSpec())))
        assertEquals(listOf("page-1", "page-2"), x.pageRenders.map { it.id })
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        x.pageRenders.forEach { assertTrue(it.bytes.take(4) == sig.toList()); assertEquals("image/png", it.mime) }
    }

    @Test fun garbage() {
        val e = assertFailsWith<ImportException> { pdfToGroups(byteArrayOf(1, 2, 3, 4)) }
        assertEquals("unreadable_pdf", e.code)
    }

    @Test fun fieldValuesBecomeFieldGroups() {
        val x = pdfToGroups(makePdf(listOf(
            PdfPageSpec(),
            PdfPageSpec(fields = listOf("blank" to "", "pole" to "39.636130,-119.691104")),
        )))
        val g = x.groups.single { it.source == GroupSource.FIELD }
        assertEquals(2, g.page)
        assertEquals(listOf("39.636130,-119.691104"), g.blocks.filterIsInstance<ParaBlock>().map { it.text })
    }

    @Test fun acroFormWithZeroFieldsIsFine() {
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(lines = listOf(Triple("hello", 50f, 700f)))), emptyAcroForm = true))
        assertTrue(x.groups.none { it.source == GroupSource.FIELD })
        assertEquals(1, x.groups.size)
    }

    // The fixture draws an image whose JPEG stream is garbage, so PDFBox throws while decoding it.
    @Test fun unreadableImageDoesNotAbortImport() {
        val bad = PdfImageSpec(solidPng(), 50f, 500f, 40f, 40f, corrupt = true)
        val good = PdfImageSpec(solidPng(), 50f, 300f, 40f, 40f)
        val x = pdfToGroups(makePdf(listOf(PdfPageSpec(
            lines = listOf(Triple("top", 50f, 700f)), images = listOf(bad, good), freeText = listOf("POLE 1 = 39.1,-119.1"),
        ))))
        assertEquals(1, x.images.size)
        assertEquals(1, x.groups.count { it.source == GroupSource.ANNOTATION })
        assertTrue(x.groups.single { it.source == GroupSource.PAGE_TEXT }.blocks.any { it is ParaBlock && it.text == "top" })
        assertEquals(listOf("page-1"), x.pageRenders.map { it.id })
    }
}
