package com.fieldmaxx.core.stamp

import com.fieldmaxx.core.model.PoleData
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StampTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val base = StampMeta(
        jobName = "A05J9XE", pointLabel = "POLE 1",
        lat = 39.63613, lon = -119.691104,
        accuracyM = 4.877, altitudeM = 1350.26, headingDeg = 47.0,
        takenAtMs = LocalDateTime.parse("2026-11-05T13:35:47").atZone(zone).toInstant().toEpochMilli(),
        zone = zone, gpsStale = false, fromGallery = false,
    )

    @Test fun full() = assertEquals(
        listOf("A05J9XE · POLE 1", "39.636130, -119.691104", "±16 ft · alt 4430 ft", "NE 47°", "11/05/26 13:35:47"),
        stampLines(base),
    )

    @Test fun noGps() = assertEquals(
        listOf("A05J9XE · POLE 1", "No GPS", "Compass n/a", "11/05/26 13:35:47"),
        stampLines(base.copy(lat = null, lon = null, accuracyM = null, altitudeM = null, headingDeg = null)),
    )

    @Test fun noAltitude() = assertEquals("±16 ft", stampLines(base.copy(altitudeM = null))[2])

    @Test fun staleAndGallery() {
        val l = stampLines(base.copy(gpsStale = true, fromGallery = true))
        assertEquals("39.636130, -119.691104 (GPS stale)", l[1])
        assertEquals("11/05/26 13:35:47 (from gallery)", l[4])
    }

    @Test fun compass() {
        assertEquals("N", compass8(0.0))
        assertEquals("N", compass8(22.4))
        assertEquals("NE", compass8(22.5))
        assertEquals("N", compass8(359.0))
        assertEquals("S", compass8(200.0))
    }

    @Test fun layout() = assertEquals(StampLayout(84, 84, 3496, 105, 21), stampLayout(3000, 4000, 5))

    @Test fun extraLineOrder() = assertEquals(
        "Grounding · No fiber · HOA 23.5 · lean",
        stampExtraLine("POLE", PoleData(lineHeightFt = 23.5, tags = listOf("Grounding", "No fiber")), "lean"),
    )

    @Test fun extraLineHeightFormat() = assertEquals("HOA 24", stampExtraLine("POLE", PoleData(lineHeightFt = 24.0), ""))

    @Test fun extraLineNonPoleHasNoHoa() =
        assertEquals("N/A", stampExtraLine("PED", PoleData(lineHeightFt = 5.0, tags = listOf("N/A")), ""))

    @Test fun extraLineEmptyIsNull() = assertNull(stampExtraLine("POLE", null, "  "))

    @Test fun extraAppendedLast() {
        assertEquals(stampLines(base) + "N/A", stampLines(base.copy(extra = "N/A")))
        assertEquals(5, stampLines(base).size)
    }

    @Test fun longExtraWraps() {
        val words = (1..20).map { "word$it" }
        val extra = words.joinToString(" ")
        assertTrue(extra.length >= 120)
        val lines = stampLines(base.copy(extra = extra)).drop(5)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= STAMP_LINE_MAX }, lines.toString())
        assertEquals(words, lines.joinToString(" ").split(" "))
    }
}
