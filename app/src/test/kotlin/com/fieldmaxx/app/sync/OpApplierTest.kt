package com.fieldmaxx.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fieldmaxx.app.data.AppDatabase
import com.fieldmaxx.app.data.toEntity
import com.fieldmaxx.app.data.toPoint
import com.fieldmaxx.core.model.Op
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpApplierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val applier = OpApplier(db) { 99 }

    @AfterTest fun tearDown() = db.close()

    private fun seed(vararg points: com.fieldmaxx.core.model.Point) = runBlocking {
        db.jobs().upsert(job("j1").toEntity(1, 1))
        points.forEach { db.points().upsert(it.toEntity(seq = 1)) }
    }

    private fun stored(id: String) = runBlocking { db.points().get(id)!!.toPoint() }

    @Test fun renameAppliesLocally() {
        seed(point("p1").copy(folderPath = "POLE/POLE_1", folderCandidates = listOf("a", "b")))
        runBlocking { applier.applyLocally(Op.PointRename("o1", "p1", "PED", "7"), "u1", 500) }
        val p = stored("p1")
        assertEquals("PED", p.type)
        assertEquals("7", p.num)
        assertEquals("PED 7", p.label)
        assertEquals("POLE", p.layer, "the layer stays")
        assertNull(p.folderPath)
        assertNull(p.folderCandidates)
        assertEquals(500, p.updatedAt)
    }

    @Test fun renameKeepsQueryLabelAndRefusesBlank() {
        seed(point("q1").copy(layer = "QUERY 1", type = "QUERY 1", label = "Query 1-1"))
        runBlocking { applier.applyLocally(Op.PointRename("o1", "q1", "QUERY 1", "43"), "u1", 500) }
        assertEquals("Query 1-43", stored("q1").label)
        runBlocking { applier.applyLocally(Op.PointRename("o2", "q1", "  ", "5"), "u1", 600) }
        runBlocking { applier.applyLocally(Op.PointRename("o3", "q1", "PED", " "), "u1", 600) }
        assertEquals("43", stored("q1").num, "blank text changes nothing")
    }
}
