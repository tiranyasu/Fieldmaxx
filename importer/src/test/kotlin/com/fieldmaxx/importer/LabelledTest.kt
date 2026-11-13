package com.fieldmaxx.importer

import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelledTest {
    private fun p(label: String, lat: Double, lon: Double) =
        DraftPoint("POLE", "POLE", label.substringAfter(' '), label, lat, lon, "", emptyList(), 1, emptyList(), "")

    @Test fun aerialReplica() { val r = extractLabelledPoints(TextGroup(GroupSource.ANNOTATION, 1, AERIAL)); assertEquals((1..7).map { "POLE $it" }, r.points.map { it.label }); with(r.points[4]) { assertEquals("POLE", layer); assertEquals("5", num); assertEquals(39.5000000005, lat); assertEquals(-119.500000005, lon); assertEquals(1, sourcePage) }; assertTrue(r.unlabelled.isEmpty()); assertTrue(r.leftoverText.isBlank()) }
    @Test fun suffixUppercased() = assertEquals("POLE 14B", extractLabelledPoints(TextGroup(GroupSource.DOCX, null, "POLE 14b = 39.1234,-119.1234")).points.single().label)
    @Test fun mixedCaseColonDms() = assertEquals("POLE 3", extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 2, "Pole 3: 39°38'09.9\"N 119°41'27.8\"W")).points.single().label)
    @Test fun trailingDescription() = assertEquals("existing 40ft", extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 1, "MH 2 = 39.1234,-119.1234 existing 40ft\nMH 3 = 39.2234,-119.2234")).points[0].description)
    @Test fun unlabelledReturned() = assertEquals(1, extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 1, "see 39.1234,-119.1234")).unlabelled.size)
    @Test fun sanity() { val out = addSanityFlags(listOf(p("POLE 1", 39.1, -119.1), p("POLE 2", 39.11, 119.11), p("POLE 3", 39.12, -119.12), p("POLE 4", 41.5, -119.1), p("POLE 1", 39.2, -119.2))); assertContains(out[1].flags, ImportFlag.sign_mismatch); assertContains(out[3].flags, ImportFlag.far_from_job); assertContains(out[0].flags, ImportFlag.duplicate_label); assertContains(out[4].flags, ImportFlag.duplicate_label); assertTrue(out[2].flags.isEmpty()) }
}
