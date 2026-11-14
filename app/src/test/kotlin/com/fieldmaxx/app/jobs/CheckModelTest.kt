package com.fieldmaxx.app.jobs

import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.UnreadLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A point as the server's reader returns it: `POLE 1` is type POLE, number 1, on the PDF's page 1. */
internal fun draft(label: String, lat: Double? = 39.636130, lon: Double? = -119.691104, flags: List<ImportFlag> = emptyList()): DraftPoint {
    val type = label.substringBeforeLast(' ')
    val num = label.substringAfterLast(' ')
    return DraftPoint(
        layer = type, type = type, num = num, label = label, lat = lat, lon = lon, description = "", flags = flags,
        sourcePage = 1, refImageIds = listOf("imp1-page-1"), rawText = label,
    )
}

internal fun noCoordinates(label: String) = draft(label, lat = null, lon = null, flags = listOf(ImportFlag.no_coordinates))

internal fun preview(
    points: List<DraftPoint>,
    unread: List<UnreadLine> = emptyList(),
    importId: String = "imp1",
    fileName: String = "A05J9XE.pdf",
    layer: String = "POLE",
    notes: String = "NOTE FOR AERIAL PATH",
) = ImportPreview(
    importId = importId, fileName = fileName, kind = fileName.substringAfterLast('.'), layerSuggestion = layer, notes = notes,
    points = points, unread = unread, refImages = listOf(RefImageInfo("$importId-page-1", "page", 1)),
)

class CheckModelTest {
    private val unreadPole = UnreadLine("POLE 9 = 39.63,abc", 1, "type and number without readable coordinates")

    @Test fun summaryPerType() {
        val points = (1..7).map { draft("POLE $it") } + draft("MH 10") + noCoordinates("HH 2")

        assertEquals(listOf("Found 7 POLE points", "Found 1 MH point", "Found 1 HH point", "1 needs coordinates"), summary(points))
        assertEquals(listOf("Found 2 QUERY 1 points", "2 need coordinates"), summary(listOf(noCoordinates("QUERY 1 1"), noCoordinates("QUERY 1 2"))))
        assertEquals(emptyList(), summary(emptyList()))
    }

    @Test fun saveDisabledUntilNoCoordinatesFixedOrRemoved() {
        val s = checkStateOf(listOf(preview(listOf(draft("POLE 1"), noCoordinates("POLE 2")))))
        assertFalse(canSave(s))

        val fixed = editPoint(s, "imp1", 1, label = "POLE 2", coords = "39.63610091, -119.68529727", description = "Behind the gate")
        assertTrue(canSave(fixed))
        val pole2 = pointsOf(fixed, "imp1")[1]
        assertEquals(39.63610091, pole2.lat)
        assertEquals(-119.68529727, pole2.lon)
        assertEquals("Behind the gate", pole2.description)
        assertEquals(emptyList(), pole2.flags, "it has coordinates now")

        val removed = removePoint(s, "imp1", 1)
        assertTrue(canSave(removed))
        assertEquals(listOf("POLE 1"), toJobImports(removed).single().points.map { it.label })

        val e = assertFailsWith<IllegalArgumentException> { editPoint(s, "imp1", 1, "POLE 2", "39.63, abc", "") }
        assertEquals("Coordinates not recognised", e.message)
        assertFalse(canSave(editPoint(s, "imp1", 1, "POLE 2", "", "Still unknown")), "blank coordinates leave it without")
    }

    @Test fun fixUnreadAddsPoint() {
        val s = checkStateOf(listOf(preview(listOf(draft("POLE 8")), unread = listOf(unreadPole))))
        assertFalse(canSave(s))

        val e = assertFailsWith<IllegalArgumentException> { fixUnread(s, "imp1", unreadPole, "POLE 9", "39.63,abc") }
        assertEquals("Coordinates not recognised", e.message)

        val fixed = fixUnread(s, "imp1", unreadPole, "POLE 9", "39.6361, -119.6912")
        val expected = DraftPoint(
            layer = "POLE", type = "POLE", num = "9", label = "POLE 9", lat = 39.6361, lon = -119.6912, description = "",
            flags = emptyList(), sourcePage = 1, refImageIds = listOf("imp1-page-1"), rawText = unreadPole.text,
        )
        assertEquals(listOf(draft("POLE 8"), expected), fixed.edits.getValue("imp1"))
        assertEquals(emptyList(), fixed.previews.single().unread, "a fixed line is done")
        assertTrue(canSave(fixed))
        assertEquals(listOf("POLE 8", "POLE 9"), toJobImports(fixed).single().points.map { it.label })

        // Swapped coordinates are taken, and flagged.
        val swapped = fixUnread(s, "imp1", unreadPole, "pole 9", "-119.6912, 39.6361").edits.getValue("imp1").last()
        assertEquals(Triple(39.6361, -119.6912, listOf(ImportFlag.swapped)), Triple(swapped.lat, swapped.lon, swapped.flags))
        assertEquals("POLE 9", swapped.label)
    }

    @Test fun ignoreUnreadEnablesSave() {
        val s = checkStateOf(listOf(preview(listOf(draft("POLE 8")), unread = listOf(unreadPole))))
        assertFalse(canSave(s))

        val ignored = ignoreUnread(s, "imp1", unreadPole)
        assertTrue(canSave(ignored))
        assertTrue(isIgnored(ignored, "imp1", unreadPole))
        assertEquals(listOf(unreadPole), ignored.previews.single().unread, "an ignored line stays listed")
        assertEquals(listOf("POLE 8"), toJobImports(ignored).single().points.map { it.label })

        assertFalse(canSave(ignoreUnread(ignored, "imp1", unreadPole, ignored = false)), "undo brings the line back")
    }

    @Test fun flagBadgesText() {
        assertEquals(
            listOf("Possibly swapped", "Sign differs", "Far from the rest", "Duplicate label", "No label", "No coordinates"),
            listOf(
                ImportFlag.swapped, ImportFlag.sign_mismatch, ImportFlag.far_from_job, ImportFlag.duplicate_label,
                ImportFlag.unlabelled, ImportFlag.no_coordinates,
            ).map(::flagText),
        )
    }

    @Test fun importsCarryLayerNotesAndEveryFile() {
        val poles = preview(listOf(draft("POLE 1"), draft("MH 10")))
        val query = preview(
            listOf(draft("Query 1-14").copy(layer = "QUERY 1", type = "QUERY 1", num = "14")),
            importId = "imp2", fileName = "QUERY_1_-_A05K51V.docx", layer = "QUERY 1", notes = "",
        )
        val s = setLayer(checkStateOf(listOf(poles, query)), "imp2", "QUERY 2")

        val imports = toJobImports(s)
        assertEquals(listOf("imp1", "imp2"), imports.map { it.importId })
        assertEquals(listOf("POLE", "QUERY 2"), imports.map { it.layer })
        assertEquals(listOf("NOTE FOR AERIAL PATH", ""), imports.map { it.notes })
        assertEquals(listOf("POLE 1", "MH 10"), imports[0].points.map { it.label }, "a pole file keeps its labels")
        val q = imports[1].points.single()
        assertEquals(listOf("QUERY 2", "QUERY 2", "14", "Query 2-14"), listOf(q.layer, q.type, q.num, q.label), "query points follow the layer")
        assertEquals(listOf("Found 1 QUERY 2 point"), summary(pointsOf(s, "imp2")))
    }

    @Test fun labelsAreReadLikeTheFile() {
        val s = checkStateOf(listOf(preview(listOf(draft("POLE 1")))))

        val edited = pointsOf(editPoint(s, "imp1", 0, "pole 14b", "39.636130, -119.691104", ""), "imp1").single()
        assertEquals(listOf("POLE", "POLE", "14B", "POLE 14B"), listOf(edited.layer, edited.type, edited.num, edited.label))
        val query = pointsOf(editPoint(s, "imp1", 0, "query 3-25A", "39.636130, -119.691104", ""), "imp1").single()
        assertEquals(listOf("QUERY 3", "QUERY 3", "25A", "Query 3-25A"), listOf(query.layer, query.type, query.num, query.label))

        val e = assertFailsWith<IllegalArgumentException> { editPoint(s, "imp1", 0, "corner pole", "39.636130, -119.691104", "") }
        assertEquals("Enter a label like POLE 9", e.message)
        assertEquals("POLE 9", suggestedLabel(unreadPole))
        assertEquals("A05J9XE", jobNameFor("A05J9XE.pdf"))
        assertEquals("QUERY_1_-_A05K51V", jobNameFor("QUERY_1_-_A05K51V.docx"))
    }

    @Test fun editKeepsUntouchedCoordinatesExact() {
        val p = draft("POLE 1", lat = 39.63609452036168, lon = -119.69104360818118, flags = listOf(ImportFlag.far_from_job))
        val s = checkStateOf(listOf(preview(listOf(p))))

        val edited = pointsOf(editPoint(s, "imp1", 0, "POLE 1", coordinatesText(p), "Leaning"), "imp1").single()
        assertEquals(p.copy(description = "Leaning"), edited, "same coordinates, same flags")
        assertEquals("40.000, -119.000", coordinatesText(draft("POLE 2", lat = 40.0, lon = -119.0)), "always readable back")
    }
}
