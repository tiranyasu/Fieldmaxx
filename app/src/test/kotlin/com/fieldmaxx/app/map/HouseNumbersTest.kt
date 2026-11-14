package com.fieldmaxx.app.map

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class HouseNumbersTest {
    private val box = BBox(s = 39.6340, w = -119.6930, n = 39.6380, e = -119.6890)

    @Test fun queryHasHousenumberBboxOutCenter() {
        val q = overpassQuery(box)
        assertTrue("[out:json]" in q, q)
        assertTrue("\"addr:housenumber\"" in q, q)
        assertTrue("node[\"addr:housenumber\"](39.634,-119.693,39.638,-119.689)" in q, q)
        assertTrue("way[\"addr:housenumber\"](39.634,-119.693,39.638,-119.689)" in q, q)
        assertTrue(q.trimEnd().endsWith("out center;"), q)
    }

    @Test fun parsesNodesAndWayCentersSkipsMissing() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":39.6351,"lon":-119.6911,"tags":{"addr:housenumber":"123"}},
              {"type":"way","id":2,"center":{"lat":39.6361,"lon":-119.6921},"tags":{"addr:housenumber":"45B"}},
              {"type":"node","id":3,"lat":39.6371,"lon":-119.6901,"tags":{"name":"no number"}},
              {"type":"node","id":4,"lat":39.6372,"lon":-119.6902,"tags":{"addr:housenumber":"  "}},
              {"type":"way","id":5,"tags":{"addr:housenumber":"9"}},
              {"type":"node","id":6,"tags":{"addr:housenumber":"7"}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(HouseNumber(39.6351, -119.6911, "123"), HouseNumber(39.6361, -119.6921, "45B")),
            parseHouseNumbers(json),
        )
        assertEquals(emptyList(), parseHouseNumbers("""{"elements":[]}"""))
    }

    @Test fun badJsonIsAnIoFailure() {
        assertFailsWith<IOException> { parseHouseNumbers("<html>busy</html>") }
    }

    private val body = """{"elements":[{"type":"node","id":1,"lat":39.6351,"lon":-119.6911,"tags":{"addr:housenumber":"123"}}]}"""

    private fun withServer(block: (MockWebServer, (OkHttpClient) -> HouseNumberLoader) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server) { client -> HouseNumberLoader(client, "Fieldmaxx/9.9 (Android)", server.url("/api/interpreter").toString(), log = {}) }
        } finally {
            server.shutdown()
        }
    }

    @Test fun postsOnlyTheQueryWithAGenericUserAgentAndNoAuth() = withServer { server, loader ->
        server.enqueue(MockResponse().setBody(body))
        // The same kind of client the app builds, but with a session interceptor that must not be on the Overpass client.
        val client = OkHttpClient.Builder().build()
        val numbers = runBlocking { loader(client).load(box) }

        assertEquals(listOf(HouseNumber(39.6351, -119.6911, "123")), numbers)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("Fieldmaxx/9.9 (Android)", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        val form = req.body.readUtf8()
        assertTrue(form.startsWith("data="), form)
        assertTrue(java.net.URLDecoder.decode(form.removePrefix("data="), "UTF-8") == overpassQuery(box), "only the bbox query is sent: $form")
    }

    @Test fun cachesPerBboxRoundedToThreeDecimals() = withServer { server, loader ->
        server.enqueue(MockResponse().setBody(body))
        val l = loader(OkHttpClient())
        runBlocking {
            val first = l.load(box)
            val nearby = l.load(BBox(39.63404, -119.69296, 39.63796, -119.68904)) // same 3-decimal box
            assertEquals(first, nearby)
        }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        assertEquals(emptyList(), runBlocking { l.load(BBox(39.7, -119.7, 39.704, -119.696)) })
        assertEquals(2, server.requestCount)
    }

    @Test fun backsOffOn429AndOn504DoublingToFiveMinutes() = withServer { server, loader ->
        val now = AtomicLong(1_000_000)
        val l = HouseNumberLoader(OkHttpClient(), "Fieldmaxx/9.9 (Android)", server.url("/x").toString(), now::get, log = {})
        server.enqueue(MockResponse().setResponseCode(429))
        assertFailsWith<IOException> { runBlocking { l.load(box) } }
        assertEquals(1, server.requestCount)

        // Inside the 30 s back-off nothing is asked of the service.
        now.addAndGet(29_000)
        assertFailsWith<IOException> { runBlocking { l.load(box) } }
        assertEquals(1, server.requestCount)

        // Then asked again; a 504 doubles the wait to 60 s.
        now.addAndGet(2_000)
        server.enqueue(MockResponse().setResponseCode(504))
        assertFailsWith<IOException> { runBlocking { l.load(box) } }
        assertEquals(2, server.requestCount)
        now.addAndGet(59_000)
        assertFailsWith<IOException> { runBlocking { l.load(box) } }
        assertEquals(2, server.requestCount)

        // The wait caps at 5 minutes however many fail.
        assertEquals(30_000L, backoffMs(0))
        assertEquals(60_000L, backoffMs(1))
        assertEquals(300_000L, backoffMs(4))
        assertEquals(300_000L, backoffMs(40))

        // A good answer ends the back-off.
        now.addAndGet(2_000)
        server.enqueue(MockResponse().setBody(body))
        assertEquals(1, runBlocking { l.load(box) }.size)
    }

    @Test fun otherHttpErrorsFailQuietlyWithoutBackoff() = withServer { server, loader ->
        val l = loader(OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith<IOException> { runBlocking { l.load(box) } }
        server.enqueue(MockResponse().setBody(body))
        assertEquals(1, runBlocking { l.load(box) }.size, "a plain error isn't cached and doesn't start a back-off")
    }

    @Test fun viewBoxIsAroundTheCenterAndShrinksWithZoom() {
        val near = viewBBox(CameraView(39.0, -119.0, 18.0), 400, 800)
        val far = viewBBox(CameraView(39.0, -119.0, 17.0), 400, 800)
        assertTrue(near.s < 39.0 && 39.0 < near.n && near.w < -119.0 && -119.0 < near.e)
        assertTrue((far.n - far.s) > (near.n - near.s) * 1.9)
        assertFalse(near.n - near.s > 0.01)
    }
}
