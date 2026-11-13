package com.fieldmaxx.importer

import com.fieldmaxx.core.model.ImportFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QueryTest {
    @Test fun layers() {
        assertEquals("QUERY 1", layerFromFileName("QUERY_1_-_A05K51V.docx"))
        assertEquals("QUERY 2", layerFromFileName("query 2.pdf"))
        assertEquals("A05J9XE", layerFromFileName("A05J9XE.pdf"))
    }

    @Test fun replicaMatchesReader() = assertEquals(QUERY_BLOCKS, docxToBlocks(makeDocx(QUERY_LISTS, QUERY_BODY)).blocks)

    @Test fun replica26() {
        val r = extractQueryItems(QUERY_BLOCKS, "QUERY 1")
        assertEquals((1..24).map { "$it" } + listOf("25A", "25B"), r.points.map { it.num })
        assertEquals("Query 1-1", r.points[0].label)
        assertEquals("Query 1-25B", r.points[25].label)
    }

    @Test fun descriptions() {
        val p = extractQueryItems(QUERY_BLOCKS, "QUERY 1").points
        assertEquals("VERIFY HH", p[0].description)
        assertEquals("NO STREET VIEW PROVIDE ALL UTILITY DETAILS", p[11].description)
        assertEquals("PROVIDE WIDE ANGLE IMAGES FROM POLE 5 TO POLE 4", p[13].description)
        assertEquals("PLEASE TRY TO GET ACCESS TO COLLECT DATA FOR POLE 11,12 & 13", p[18].description)
    }

    @Test fun coordsOnNextParagraph() = assertNotNull(extractQueryItems(QUERY_BLOCKS, "QUERY 1").points[13].lat)

    @Test fun replicaHasNoLooseEnds() {
        val r = extractQueryItems(QUERY_BLOCKS, "QUERY 1")
        assertEquals(emptyList(), r.notes)
        assertEquals(emptyList(), r.unattachedImageIds)
        assertEquals(emptyList(), r.points.filter { it.flags.isNotEmpty() })
    }

    @Test fun imagesAttach() {
        val p = extractQueryItems(QUERY_BLOCKS, "QUERY 1").points
        assertEquals(1, p[0].refImageIds.size)
        assertEquals(2, p[22].refImageIds.size)
        assertEquals(0, p[15].refImageIds.size)
        assertEquals(p[24].refImageIds, p[25].refImageIds)
    }

    @Test fun noCoordinatesFlagged() {
        val p = extractQueryItems(listOf(ParaBlock("CHECK SOMETHING", "1.", null)), "QUERY 1").points.single()
        assertNull(p.lat)
        assertEquals(listOf(ImportFlag.no_coordinates), p.flags)
    }

    @Test fun unnumberedContinues() {
        val p = extractQueryItems(listOf(ParaBlock("A – 39.1234,-119.1234", "1.", null), ParaBlock("B – 39.2234,-119.2234", null, null)), "QUERY 1").points
        assertEquals("2", p[1].num)
        assertEquals(listOf(ImportFlag.unlabelled), p[1].flags)
    }

    @Test fun textExtendsDescription() {
        val p = extractQueryItems(listOf(ParaBlock("POLE DATA", "1.", null), ParaBlock("near the creek", null, null), ParaBlock("39.1234,-119.1234", null, null)), "QUERY 1").points.single()
        assertEquals("POLE DATA near the creek", p.description)
        assertEquals(39.1234, p.lat)
    }

    @Test fun orphansAreKept() {
        val r = extractQueryItems(listOf(ImageBlock("img-1", null), ParaBlock("39.1234,-119.1234", null, null), ParaBlock("stray words", null, null)), "QUERY 1")
        assertEquals(listOf("img-1"), r.unattachedImageIds)
        assertEquals(1, r.points.size)
        assertEquals(listOf("stray words"), r.notes)
    }
}
