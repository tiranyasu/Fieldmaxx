package com.fieldmaxx.core.model

import com.fieldmaxx.core.FmxJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonTest {
    @Test fun pointMoveRoundTrips() {
        val op: Op = Op.PointMove("o1", "p1", 39.6, -119.7)
        val text = FmxJson.encodeToString(Op.serializer(), op)
        assertTrue(text.contains("\"kind\":\"point.move\""), text)
        assertEquals(op, FmxJson.decodeFromString(Op.serializer(), text))
    }

    @Test fun helloEncoding() {
        val text = FmxJson.encodeToString(ServerMessage.serializer(), ServerMessage.Hello(5))
        assertEquals("""{"kind":"hello","seq":5}""", text)
    }
}
