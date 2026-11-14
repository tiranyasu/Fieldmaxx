package com.fieldmaxx.app

import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.net.AuthInterceptor
import com.fieldmaxx.app.net.InMemoryTokenVault
import com.fieldmaxx.app.net.OkHttpApi
import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ApiTest {
    @get:Rule val server = MockWebServer()
    @get:Rule val tmp = TemporaryFolder()

    private val vault = InMemoryTokenVault()
    private val baseUrl = { server.url("/").toString() }
    private val api = OkHttpApi(OkHttpClient.Builder().addInterceptor(AuthInterceptor(vault, baseUrl)).build(), baseUrl)

    private val alice = User("u1", "alice", "Alice Tech", Role.tech, disabled = false)
    private val aliceJson = FmxJson.encodeToString(User.serializer(), alice)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun bearerHeaderSent() = runTest {
        vault.save("tok-1")
        server.enqueue(json("""{"user":$aliceJson}"""))

        assertEquals(alice, api.me())
        val request = server.takeRequest()
        assertEquals("GET /api/auth/me", "${request.method} ${request.path}")
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
    }

    @Test fun unauthorizedMapsToApiException() = runTest {
        server.enqueue(json("""{"error":"Sign in required"}""", code = 401))

        val e = assertFailsWith<ApiException> { api.jobs(include = "finished") }
        assertEquals(401, e.status)
        assertEquals("Sign in required", e.message)
        assertEquals("/api/jobs?include=finished", server.takeRequest().path)
    }

    @Test fun opsSerialiseWithKind() = runTest {
        val ops = listOf(Op.PointUpdate("o1", "p1", comment = "Leaning"), Op.PointMove("o2", "p2", 39.636130, -119.691104))
        server.enqueue(json("""{"results":[{"opId":"o1","ok":true},{"opId":"o2","ok":false,"error":"Point not found"}]}"""))

        assertEquals(listOf(OpResult("o1", true), OpResult("o2", false, "Point not found")), api.ops(ops))
        val request = server.takeRequest()
        assertEquals("POST /api/ops", "${request.method} ${request.path}")
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray
        assertEquals(listOf("point.update", "point.move"), sent.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
        assertEquals(ops, FmxJson.decodeFromJsonElement(ListSerializer(Op.serializer()), sent)) // as the server reads them
    }

    @Test fun loginReturnsTokenAndUser() = runTest {
        server.enqueue(json("""{"token":"tok-2","user":$aliceJson}"""))

        assertEquals("tok-2" to alice, api.login("alice", "secret pw"))
        val request = server.takeRequest()
        assertEquals("POST /api/auth/login", "${request.method} ${request.path}")
        assertEquals("""{"username":"alice","password":"secret pw"}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun errorWithoutJsonBodyKeepsStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val e = assertFailsWith<ApiException> { api.config() }
        assertEquals(502, e.status)
        assertEquals("Server error (HTTP 502)", e.message)
    }

    @Test fun backupWrongOffsetReturnsServerPosition() = runTest {
        server.enqueue(json("""{"offset":4,"complete":false}"""))
        server.enqueue(json("""{"error":"Wrong offset","offset":4,"complete":false}""", code = 409))

        assertEquals(4L to false, api.backupChunk("ph1", 0, byteArrayOf(1, 2, 3, 4)))
        assertEquals(4L to false, api.backupChunk("ph1", 0, byteArrayOf(1, 2, 3, 4)))
        val request = server.takeRequest()
        assertEquals("PUT /api/photos/ph1/backup?offset=0", "${request.method} ${request.path}")
        assertContentEquals(byteArrayOf(1, 2, 3, 4), request.body.readByteArray())
    }

    @Test fun noPublishedReleaseIsNull() = runTest {
        server.enqueue(json("null"))

        assertNull(api.latestRelease())
        assertEquals("/api/app/latest", server.takeRequest().path)
    }

    @Test fun importUploadsTheFileAsMultipart() = runTest {
        val preview = ImportPreview("imp1", "A05J9XE.pdf", "pdf", "POLE", "", emptyList(), emptyList(), emptyList())
        server.enqueue(json(FmxJson.encodeToString(ImportPreview.serializer(), preview)))

        assertEquals(preview, api.importFile("A05J9XE.pdf", "%PDF-1.7".toByteArray()))
        val request = server.takeRequest()
        assertEquals("POST /api/imports", "${request.method} ${request.path}")
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue("Content-Disposition: form-data; name=\"file\"; filename=\"A05J9XE.pdf\"" in body, body)
        assertTrue("%PDF-1.7" in body, body)
    }

    @Test fun downloadKeepsOnlyCompleteFiles() = runTest {
        val to = File(tmp.root, "job/source.pdf")
        server.enqueue(MockResponse().setBody("x".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody("the file"))

        assertFailsWith<IOException> { api.download("/api/jobs/j1/files/f1", to) }
        assertFalse(to.exists())
        api.download("/api/jobs/j1/files/f1", to)
        assertEquals("the file", to.readText())
        assertEquals(listOf("source.pdf"), to.parentFile!!.list()!!.toList()) // no partial file left behind
        assertEquals("/api/jobs/j1/files/f1", server.takeRequest().path)
    }

    @Test fun tokenNeverSentToOtherHosts() = runTest {
        vault.save("tok-1")
        MockWebServer().use { other ->
            other.enqueue(MockResponse().setBody("apk"))

            api.download(other.url("/app/fieldmaxx-1.2.apk").toString(), File(tmp.root, "update.apk"))
            assertNull(other.takeRequest().getHeader("Authorization"))
        }
    }
}
