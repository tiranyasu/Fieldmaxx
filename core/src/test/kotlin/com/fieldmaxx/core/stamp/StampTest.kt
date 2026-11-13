package com.fieldmaxx.core.stamp

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
