package com.fieldmaxx.core.model

import com.fieldmaxx.core.FmxJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test fun renameOpRoundTrips() {
        val op: Op = Op.PointRename("o1", "p1", "POLE", "12")
        val text = FmxJson.encodeToString(Op.serializer(), op)
        assertTrue(text.contains("\"kind\":\"point.rename\""), text)
        assertEquals(op, FmxJson.decodeFromString(Op.serializer(), text))
    }

    @Test fun configWithoutTagsDefaults() {
        val json = """{"imageryUrl":"a","labelsUrl":"b","imageryKey":null,"photoQuality":85,"backupDays":7}"""
        assertEquals(POLE_TAGS, FmxJson.decodeFromString(AppConfig.serializer(), json).tags)
    }
}
