package com.fieldmaxx.server

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.auth.verifyPassword
import com.fieldmaxx.server.users.createUser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {
    private val day = 86_400_000L

    private suspend fun HttpClient.json(path: String, body: String, token: String? = null, patch: Boolean = false): HttpResponse {
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }
        return if (patch) patch(path, setup) else post(path, setup)
    }

    private suspend fun HttpClient.login(user: String, pw: String) =
        json("/api/auth/login", """{"username":"$user","password":"$pw"}""")

    private suspend fun HttpClient.me(token: String) = get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test fun passwordsHashAndVerify() {
        val h = com.fieldmaxx.server.auth.hashPassword("correct horse")
        assertTrue(h.startsWith("\$argon2id\$"))
        assertTrue(verifyPassword(h, "correct horse"))
        assertFalse(verifyPassword(h, "wrong"))
        assertNotEquals(h, com.fieldmaxx.server.auth.hashPassword("correct horse"))
    }

    @Test fun loginReturnsTokenCaseInsensitiveUsername() = TestApp().run { app ->
        createUser(app.ctx, "Maria", "Maria R", "secret-pass-1", Role.tech)
        val res = client.login("mArIa", "secret-pass-1")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.obj()
        val token = body.getValue("token").jsonPrimitive.content
        assertTrue(token.length >= 43)
        assertEquals("Maria R", body.getValue("user").jsonObject.getValue("displayName").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.me(token).status)
    }

    @Test fun wrongPassword401WithMessage() = TestApp().run { app ->
        createUser(app.ctx, "maria", "Maria", "secret-pass-1", Role.tech)
        val res = client.login("maria", "nope-nope")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("Wrong username or password", res.obj().getValue("error").jsonPrimitive.content)
        assertEquals("Wrong username or password", client.login("ghost", "whatever1").obj().getValue("error").jsonPrimitive.content)
    }

    @Test fun eleventhFailure429() = TestApp().run { app ->
        createUser(app.ctx, "maria", "Maria", "secret-pass-1", Role.tech)
        repeat(10) { assertEquals(HttpStatusCode.Unauthorized, client.login("maria", "bad-bad-bad").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("maria", "secret-pass-1").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.login("MARIA", "secret-pass-1").status)
        app.clock.nowMs += 16 * 60_000
        assertEquals(HttpStatusCode.OK, client.login("maria", "secret-pass-1").status)
    }

    @Test fun successClearsFailures() = TestApp().run { app ->
        createUser(app.ctx, "maria", "Maria", "secret-pass-1", Role.tech)
        repeat(9) { client.login("maria", "bad-bad-bad") }
        assertEquals(HttpStatusCode.OK, client.login("maria", "secret-pass-1").status)
        repeat(9) { assertEquals(HttpStatusCode.Unauthorized, client.login("maria", "bad-bad-bad").status) }
    }

    @Test fun disabledUser403() = TestApp().run { app ->
        val u = createUser(app.ctx, "maria", "Maria", "secret-pass-1", Role.tech)
        val token = client.login("maria", "secret-pass-1").obj().getValue("token").jsonPrimitive.content
        val admin = tokenFor("boss", Role.admin)
        assertEquals(HttpStatusCode.OK, client.json("/api/admin/users/${u.id}", """{"disabled":true}""", admin, patch = true).status)
        assertEquals(HttpStatusCode.Forbidden, client.login("maria", "secret-pass-1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(token).status)
    }

    @Test fun sessionSlidesAfter89Days() = TestApp().run { app ->
        val token = tokenFor("maria")
        app.clock.nowMs += 89 * day
        assertEquals(HttpStatusCode.OK, client.me(token).status)
        app.clock.nowMs += 89 * day
        assertEquals(HttpStatusCode.OK, client.me(token).status)
    }

    @Test fun sessionExpiresAfter91IdleDays() = TestApp().run { app ->
        val token = tokenFor("maria")
        app.clock.nowMs += 91 * day
        assertEquals(HttpStatusCode.Unauthorized, client.me(token).status)
    }

    @Test fun unauthenticated401HasJsonError() = TestApp().run { _ ->
        val none = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertEquals("Sign in required", none.obj().getValue("error").jsonPrimitive.content)
        val bad = client.me("garbage-token")
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals("Sign in required", bad.obj().getValue("error").jsonPrimitive.content)
    }

    @Test fun parallelLoginsCannotExceedLockout() = TestApp(fileDb = true).run { app ->
        createUser(app.ctx, "maria", "Maria", "secret-pass-1", Role.tech)
        val statuses = kotlinx.coroutines.coroutineScope { val scope = this
            (1..20).map { scope.async(kotlinx.coroutines.Dispatchers.Default) { client.login("maria", "bad-bad-bad").status } }
                .map { it.await() }
        }
        assertEquals(10, statuses.count { it == HttpStatusCode.Unauthorized })
        assertEquals(10, statuses.count { it == HttpStatusCode.TooManyRequests })
        assertEquals(HttpStatusCode.TooManyRequests, client.login("maria", "secret-pass-1").status)
    }

    @Test fun noTokenIs401AndLogoutEndsSession() = TestApp().run { _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me").status)
        val token = tokenFor("maria")
        assertEquals(HttpStatusCode.NoContent, client.json("/api/auth/logout", "{}", token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(token).status)
    }

    @Test fun techCannotListUsers() = TestApp().run { _ ->
        val token = tokenFor("maria")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/users") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.json("/api/admin/users", """{"username":"x","displayName":"X","password":"password-1"}""", token).status)
    }

    @Test fun adminCreatesResetsDisables() = TestApp().run { _ ->
        val admin = tokenFor("boss", Role.admin)
        val created = client.json("/api/admin/users", """{"username":"Joe","displayName":"Joe T","password":"first-pass-1","role":"tech"}""", admin)
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.obj().getValue("id").jsonPrimitive.content
        val joeToken = client.login("joe", "first-pass-1").obj().getValue("token").jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, client.json("/api/admin/users/$id", """{"password":"second-pass-2","displayName":"Joseph"}""", admin, patch = true).status)
        assertEquals(HttpStatusCode.Unauthorized, client.me(joeToken).status) // reset signs the user out
        assertEquals(HttpStatusCode.Unauthorized, client.login("joe", "first-pass-1").status)
        assertEquals(HttpStatusCode.OK, client.login("joe", "second-pass-2").status)

        assertEquals(HttpStatusCode.OK, client.json("/api/admin/users/$id", """{"role":"admin","disabled":true}""", admin, patch = true).status)
        val list = client.get("/api/admin/users") { header(HttpHeaders.Authorization, "Bearer $admin") }.obj().getValue("users").jsonArray
        val joe = list.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }
        assertEquals("Joseph", joe.getValue("displayName").jsonPrimitive.content)
        assertEquals("admin", joe.getValue("role").jsonPrimitive.content)
        assertEquals("true", joe.getValue("disabled").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.json("/api/admin/users/nope", """{"disabled":true}""", admin, patch = true).status)
    }

    @Test fun duplicateUsername409() = TestApp().run { _ ->
        val admin = tokenFor("boss", Role.admin)
        val body = """{"username":"joe","displayName":"Joe","password":"first-pass-1"}"""
        assertEquals(HttpStatusCode.Created, client.json("/api/admin/users", body, admin).status)
        assertEquals(HttpStatusCode.Conflict, client.json("/api/admin/users", body.replace("joe", "JOE"), admin).status)
    }

    @Test fun changePasswordNeedsCurrent() = TestApp().run { _ ->
        val token = tokenFor("maria")
        val bad = client.json("/api/auth/password", """{"current":"not-it-at-all","next":"brand-new-pass"}""", token)
        assertEquals(HttpStatusCode.Forbidden, bad.status)
        assertEquals(HttpStatusCode.OK, client.login("maria", TEST_PASSWORD).status)
        assertEquals(HttpStatusCode.NoContent, client.json("/api/auth/password", """{"current":"$TEST_PASSWORD","next":"brand-new-pass"}""", token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.login("maria", TEST_PASSWORD).status)
        assertEquals(HttpStatusCode.OK, client.login("maria", "brand-new-pass").status)
        assertEquals(HttpStatusCode.OK, client.me(token).status) // the caller stays signed in
    }

    @Test fun cliCreatesAdmin() {
        val app = TestApp()
        try {
            val out = mutableListOf<String>()
            assertEquals(CliResult.NotACommand, runCli(arrayOf(), app.ctx, emptyMap(), out::add))
            assertEquals(CliResult.Done, runCli(arrayOf("create-admin", "owner", "The Owner"), app.ctx, mapOf("FMX_ADMIN_PASSWORD" to "owner-pass-123"), out::add))
            val (role, hash) = app.ctx.db.read {
                createStatement().use { st -> st.executeQuery("SELECT role, password_hash FROM users WHERE username='owner'").use { it.next(); it.getString(1) to it.getString(2) } }
            }
            assertEquals("admin", role)
            assertTrue(verifyPassword(hash, "owner-pass-123"))
            // no password in the environment: nothing is created
            assertEquals(CliResult.Failed, runCli(arrayOf("create-admin", "other", "Other"), app.ctx, emptyMap(), out::add))
            assertTrue(out.last().startsWith("Usage"))
        } finally { app.ctx.db.close() }
    }
}
