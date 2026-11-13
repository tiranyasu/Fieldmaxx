package com.fieldmaxx.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test fun decimalPairNoSpace() { val m = findCoordinatePairs("39.63609452036168,-119.69104360818118").single(); assertEquals(39.63609452036168, m.lat); assertEquals(-119.69104360818118, m.lon); assertEquals(CoordFormat.DECIMAL, m.format) }
    @Test fun spaceAfterComma() = assertEquals(-119.675338878, findCoordinatePairs("39.6361531972, -119.675338878").single().lon)
    @Test fun splitAfterMinusAcrossLineBreak() = assertEquals(-119.68173217216633, findCoordinatePairs(normalizeText("39.63612682461489,-\r119.68173217216633")).single().lon)
    @Test fun labelDashIsNotJoinedToNextLine() = assertEquals(39.1234, findCoordinatePairs(normalizeText("POLE 1 -\n39.1234,-119.1234")).single().lat)
    @Test fun labelTabCr() = assertEquals("POLE 1 =\n39.6,-119.6", normalizeText("POLE 1 = \t\r39.6,-119.6"))
    @Test fun dms() { val m = findCoordinatePairs("39°38'09.9\"N 119°41'27.8\"W").single(); assertEquals(39.636083, m.lat, 1e-5); assertEquals(-119.691056, m.lon, 1e-5); assertEquals(CoordFormat.DMS, m.format) }
    @Test fun swapped() { val m = findCoordinatePairs("-119.69104,39.63609").single(); assertEquals(39.63609, m.lat); assertEquals(-119.69104, m.lon); assertTrue(m.swapped) }
    @Test fun rejects() { assertTrue(findCoordinatePairs("1.5, 2.5").isEmpty()); assertTrue(findCoordinatePairs("123.4567, 200.1234").isEmpty()) }
    @Test fun enDashAndOrder() { val ms = findCoordinatePairs("VERIFY HH – 39.35535679, -119.81288376 and 39.1234,-119.5678"); assertEquals(listOf(39.35535679, 39.1234), ms.map { it.lat }); assertEquals("VERIFY HH – ".length, ms[0].start) }
    @Test fun unicodeMinusAndNbsp() {
        assertEquals(-119.6912, findCoordinatePairs(normalizeText("39.6361 ,\u2212119.6912")).single().lon)
        assertEquals(-119.6912, findCoordinatePairs(normalizeText("39.6361,\u00A0\u202F-119.6912")).single().lon)
        assertEquals("A \u2013 B", normalizeText("A \u2013 B"))
    }
    @Test fun distanceAndFeet() { assertEquals(85.7, distanceMeters(LatLon(39.636, -119.691), LatLon(39.636, -119.690)), 0.5); assertEquals(16.0, metersToFeet(4.877), 0.5) }
}
