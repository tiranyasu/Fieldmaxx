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
}
