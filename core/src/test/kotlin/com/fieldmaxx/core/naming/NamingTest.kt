package com.fieldmaxx.core.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamingTest {
    private val pole1 = PointKey("POLE", "POLE", "1", "POLE 1")
    private val pole14b = PointKey("POLE", "POLE", "14B", "POLE 14B")
    private val q43 = PointKey("QUERY 1", "QUERY 1", "43", "Query 1-43")

    @Test fun labels() { assertEquals("POLE 14B", pointLabel("POLE", "POLE", "14B")); assertEquals("Query 1-25A", pointLabel("QUERY 1", "QUERY 1", "25A")) }
    @Test fun folderNames() { assertEquals("POLE_14B", defaultFolderName("POLE 14B")); assertEquals("Query_1-43", defaultFolderName("Query 1-43")) }
    @Test fun categories() { assertTrue(categoryMatches("QUERY 1", "Query 1")); assertTrue(categoryMatches("Query_1", "QUERY 1")); assertTrue(categoryMatches("POLES", "POLE")); assertFalse(categoryMatches("Query 3", "QUERY 1")) }
    @Test fun inCategory() { listOf("1", "POLE_1", "Pole 1", "pole1", "P1").forEach { assertTrue(pointFolderMatches(it, pole1, Where.CATEGORY), it) }; assertTrue(pointFolderMatches("POLE_14b", pole14b, Where.CATEGORY)); assertFalse(pointFolderMatches("10", pole1, Where.CATEGORY)) }
    @Test fun topLevel() { assertTrue(pointFolderMatches("1", pole1, Where.TOP)); assertFalse(pointFolderMatches("43", q43, Where.TOP)); assertTrue(pointFolderMatches("Query 1-43", q43, Where.TOP)); assertTrue(pointFolderMatches("QUERY_1-43", q43, Where.CATEGORY)); assertTrue(pointFolderMatches("43", q43, Where.CATEGORY)) }
    @Test fun fileName() = assertEquals("POLE_1_39.636130_-119.691104_6018.jpg", photoFileName("POLE_1", 39.63613, -119.691104, 6018))
    @Test fun codes() { assertEquals("P", markerCode("POLE")); assertEquals("MH", markerCode("MH")); assertEquals("Q", markerCode("QUERY 1")); assertEquals("ST", markerCode("Street images")) }
    @Test fun addedPoles() { assertEquals("14B", suggestAddedPoleNum(listOf("14"), "14")); assertEquals("14C", suggestAddedPoleNum(listOf("14", "14B"), "14")); assertEquals("14C", suggestAddedPoleNum(listOf("14", "14b"), "14")) }
    @Test fun nextNumber() { assertEquals("11", nextNum(listOf("1", "2", "10"))); assertEquals("1", nextNum(emptyList())) }
}
