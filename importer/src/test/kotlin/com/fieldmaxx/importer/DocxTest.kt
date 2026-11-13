package com.fieldmaxx.importer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DocxTest {
    private val dec = DocxList(1, 1, "decimal", "%1.")
    private fun labels(x: DocxExtract) = x.blocks.filterIsInstance<ParaBlock>().map { it.listLabel }

    @Test fun decimalList() {
        val x = docxToBlocks(makeDocx(listOf(dec), listOf("a", "b", "c").map { DocxItem.Para(it, numId = 1) }))
        assertEquals(listOf("1.", "2.", "3."), labels(x))
    }

    @Test fun startsAt14() {
        val x = docxToBlocks(makeDocx(listOf(DocxList(1, 14, "decimal", "%1.")), listOf("a", "b").map { DocxItem.Para(it, numId = 1) }))
        assertEquals(listOf("14.", "15."), labels(x))
    }

    @Test fun upperLetter() {
        val x = docxToBlocks(makeDocx(listOf(DocxList(2, 1, "upperLetter", "%1-")), listOf("a", "b").map { DocxItem.Para(it, numId = 2) }))
        assertEquals(listOf("A-", "B-"), labels(x))
    }

    @Test fun typedNumberStaysText() {
        val p = docxToBlocks(makeDocx(emptyList(), listOf(DocxItem.Para("12. NO STREET VIEW")))).blocks.single() as ParaBlock
        assertNull(p.listLabel)
        assertEquals("12. NO STREET VIEW", p.text)
    }

    @Test fun imagesInOrderWithBytes() {
        val png = byteArrayOf(1, 2, 3, 4)
        val x = docxToBlocks(makeDocx(emptyList(), listOf(DocxItem.Para("one"), DocxItem.Image(png), DocxItem.Para("two"))))
        assertEquals(listOf("ParaBlock", "ImageBlock", "ParaBlock"), x.blocks.map { it::class.simpleName })
        val img = x.images.single()
        assertContentEquals(png, img.bytes)
        assertEquals("image/png", img.mime)
        assertEquals(img.id, (x.blocks[1] as ImageBlock).imageId)
    }

    @Test fun numberingFromStyle() {
        val x = docxToBlocks(makeDocx(listOf(dec), listOf(DocxItem.Para("a", styleNumId = 1))))
        assertEquals(listOf("1."), labels(x))
    }

    @Test fun tableCellsInOrder() {
        val x = docxToBlocks(makeDocx(emptyList(), listOf(DocxItem.Para("before"), DocxItem.Table(listOf(listOf("c1", "c2"))), DocxItem.Para("after"))))
        assertEquals(listOf("before", "c1", "c2", "after"), x.blocks.map { (it as ParaBlock).text })
    }

    @Test fun twoNumsSharingAbstractContinue() {
        val lists = listOf(DocxList(1, 1, "decimal", "%1."), DocxList(2, 1, "decimal", "%1.", abstractId = 1))
        val x = docxToBlocks(makeDocx(lists, listOf(1, 1, 2).map { DocxItem.Para("p", numId = it) }))
        assertEquals(listOf("1.", "2.", "3."), labels(x))
    }

    @Test fun startOverrideRestarts() {
        val lists = listOf(DocxList(1, 1, "decimal", "%1."), DocxList(2, 1, "decimal", "%1.", abstractId = 1, startOverride = 1))
        val x = docxToBlocks(makeDocx(lists, listOf(1, 1, 2, 2).map { DocxItem.Para("p", numId = it) }))
        assertEquals(listOf("1.", "2.", "1.", "2."), labels(x))
    }

    @Test fun garbage() {
        assertEquals("unreadable_docx", assertFailsWith<ImportException> { docxToBlocks("not a zip".toByteArray()) }.code)
    }

    @Test fun renderMultiLevelAndFormats() {
        assertEquals("1.c", renderListLabel("%1.%2", listOf(1 to "decimal", 3 to "lowerLetter")))
        assertEquals("AA", renderListLabel("%1", listOf(27 to "upperLetter")))
        assertEquals("iv)", renderListLabel("%1)", listOf(4 to "lowerRoman")))
        assertEquals("XIV", renderListLabel("%1", listOf(14 to "upperRoman")))
    }
}
