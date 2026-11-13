package com.fieldmaxx.server

import com.fieldmaxx.sharepoint.FakeDestination
import io.ktor.server.testing.ApplicationTestBuilder
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
) {
    val ctx: Ctx

    init {
        val dataDir = Files.createTempDirectory("fmx-test")
        dataDir.toFile().deleteOnExit()
        val config = Config(dataDir, 0, "http://localhost", 90, "fake", "test")
        // Db keeps one connection open for its lifetime, so the shared-cache in-memory DB survives.
        val db = Db("jdbc:sqlite:file:test${counter.incrementAndGet()}?mode=memory&cache=shared")
        ctx = Ctx(config, db, clock, { _ -> destination }, NoopChangeRecorder, NoopDeliveryKick)
    }

    fun run(block: suspend ApplicationTestBuilder.(TestApp) -> Unit) {
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
    }
}
