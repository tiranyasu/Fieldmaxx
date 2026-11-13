package com.fieldmaxx.server

import com.fieldmaxx.sharepoint.FakeDestination
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.users.createUser
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

class MutableClock(var nowMs: Long = 1_762_000_000_000) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    override fun millis(): Long = nowMs
}

class TestApp(
    val clock: MutableClock = MutableClock(),
    val destination: FakeDestination = FakeDestination(),
    fileDb: Boolean = false, // a real file avoids shared-cache table locks when a test drives parallel requests
) {
    val ctx: Ctx

    init {
        val dataDir = Files.createTempDirectory("fmx-test")
        dataDir.toFile().deleteOnExit()
        val config = Config(dataDir, 0, "http://localhost", 90, "fake", "test")
        // Db keeps one connection open for its lifetime, so the shared-cache in-memory DB survives.
        val db = Db(
            if (fileDb) "jdbc:sqlite:${dataDir.resolve("test.db")}"
            else "jdbc:sqlite:file:test${counter.incrementAndGet()}?mode=memory&cache=shared",
        )
        ctx = Ctx(config, db, clock, { _ -> destination }, NoopChangeRecorder, NoopDeliveryKick)
    }

    fun run(block: suspend ApplicationTestBuilder.(TestApp) -> Unit) {
        current = ctx
        try {
            runBlocking {
                testApplication {
                    application { fieldmaxx(ctx) }
                    block(this@TestApp)
                }
            }
        } finally {
            ctx.db.close()
        }
    }

    companion object {
        private val counter = AtomicInteger()

        /** The ctx of the TestApp currently inside run(); lets extension helpers like tokenFor reach the DB. */
        @Volatile var current: Ctx? = null
    }
}

const val TEST_PASSWORD = "test-password-123"

/** Seeds [username] (if new) with [TEST_PASSWORD], logs in through the real route and returns the bearer token. */
suspend fun ApplicationTestBuilder.tokenFor(username: String, role: Role = Role.tech): String {
    val ctx = TestApp.current ?: error("tokenFor must run inside TestApp.run")
    runCatching { createUser(ctx, username, username.replaceFirstChar { it.uppercase() }, TEST_PASSWORD, role) }
    val res = client.post("/api/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$TEST_PASSWORD"}""")
    }
    return Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
}
