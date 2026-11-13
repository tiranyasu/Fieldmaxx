package com.fieldmaxx.sharepoint

import com.fieldmaxx.core.naming.PointKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderResolverTest {
    private fun pole(n: String) = PointKey("POLE", "POLE", n, "POLE $n")
    private fun query(layer: String, n: String) = PointKey(layer, layer, n, "Query ${layer.filter { it.isDigit() }}-$n")
    private fun fake(vararg folders: String) = FakeDestination().apply { this.folders.addAll(folders) }

    @Test
    fun a05j9xeTopLevel() {
        val d = fake("1", "2", "3")
        assertEquals(FolderResolution.Found("1"), FolderResolver(d).resolve(pole("1")))
    }

    @Test
    fun categoryMixedCase() {
        val d = fake("POLE", "POLE/POLE_14b")
        assertEquals(FolderResolution.Found("POLE/POLE_14b"), FolderResolver(d).resolve(pole("14b")))
    }

    @Test
    fun nothingCreatesPole33() {
        val d = fake()
        assertEquals(FolderResolution.Created("POLE/POLE_33"), FolderResolver(d).resolve(pole("33")))
        assertTrue("POLE/POLE_33" in d.folders)
    }

    @Test
    fun queryCategoryExists() {
        val d = fake("QUERY 1")
        val r = FolderResolver(d).resolve(query("QUERY 1", "43"))
        assertEquals(FolderResolution.Created("QUERY 1/Query_1-43"), r)
        assertTrue("QUERY 1/Query_1-43" in d.folders)
    }

    @Test
    fun queryNeverMatchesTopLevelPoleFolder() {
        val d = fake("43")
        assertEquals(FolderResolution.Created("QUERY 1/Query_1-43"), FolderResolver(d).resolve(query("QUERY 1", "43")))
    }

    @Test
    fun categoryPreferredOverTop() {
        val d = fake("5", "POLE", "POLE/POLE_5")
        assertEquals(FolderResolution.Found("POLE/POLE_5"), FolderResolver(d).resolve(pole("5")))
    }

    @Test
    fun twoCandidatesAmbiguous() {
        val d = fake("POLE", "POLE/5", "POLE/POLE_5")
        val r = FolderResolver(d).resolve(pole("5"))
        assertEquals(FolderResolution.Ambiguous(listOf("POLE/5", "POLE/POLE_5")), r)
    }

    @Test
    fun existingCategorySpellingReused() {
        val d = fake("Query 3")
        val r = FolderResolver(d).resolve(query("QUERY 3", "7"))
        assertEquals(FolderResolution.Created("Query 3/Query_3-7"), r)
    }

    @Test
    fun listingsCachedPerResolver() {
        val d = fake("1", "2")
        val resolver = FolderResolver(d)
        resolver.resolve(pole("1"))
        resolver.resolve(pole("2"))
        assertEquals(1, d.calls.count { it == "listFolders " })
    }

    @Test
    fun cacheInvalidatedAfterCreatingInsideFolder() {
        val d = fake("POLE")
        val resolver = FolderResolver(d)
        assertEquals(FolderResolution.Created("POLE/POLE_1"), resolver.resolve(pole("1")))
        assertEquals(FolderResolution.Found("POLE/POLE_1"), resolver.resolve(pole("1")))
    }
}
