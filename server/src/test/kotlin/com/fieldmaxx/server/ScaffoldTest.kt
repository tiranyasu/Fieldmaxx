package com.fieldmaxx.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class ScaffoldTest {
    private fun Db.tables(): Set<String> = read {
        createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    @Test fun migrationsCreateAllTables() {
        val app = TestApp()
        try {
            val expected = setOf(
                "users", "sessions", "login_failures", "jobs", "job_files", "imports", "ref_images", "points",
                "photos", "counters", "settings", "ops_done", "changes", "positions", "events", "app_releases",
            )
            val tables = app.ctx.db.tables()
            assertTrue(tables.containsAll(expected), "missing: ${expected - tables}")
            val fk = app.ctx.db.read {
                createStatement().use { it.executeQuery("PRAGMA foreign_keys").use { rs -> rs.next(); rs.getInt(1) } }
            }
            assertEquals(1, fk)
        } finally { app.ctx.db.close() }
    }

    @Test fun migrationsIdempotent() {
        val url = "jdbc:sqlite:file:idem?mode=memory&cache=shared"
        val first = Db(url)
        try {
            first.tx { createStatement().use { it.execute("INSERT INTO settings(key, value) VALUES ('k','v')") } }
            val second = Db(url) // runs migrations again over the same database
            second.close()
            val count = first.read {
                createStatement().use { it.executeQuery("SELECT COUNT(*) FROM settings").use { rs -> rs.next(); rs.getInt(1) } }
            }
            assertEquals(1, count)
        } finally { first.close() }
    }

    @Test fun versionEndpointReturnsBuild() = TestApp().run {
        assertEquals("""{"build":"test"}""", client.get("/api/version").bodyAsText())
    }

    @Test fun configDefaults() {
        val c = loadConfig(emptyMap())
        assertEquals(Paths.get("./data"), c.dataDir)
        assertEquals(8080, c.port)
        assertEquals("sharepoint", c.destination)
        assertEquals("dev", c.buildId)
        assertEquals(90, c.sessionDays)
        val o = loadConfig(mapOf("PORT" to "9000", "FMX_DESTINATION" to "fake", "FMX_BUILD_ID" to "x", "FMX_DATA_DIR" to "/d", "FMX_PUBLIC_ORIGIN" to "https://f"))
        assertEquals(9000, o.port); assertEquals("fake", o.destination); assertEquals("https://f", o.publicOrigin)
    }

    /**
     * Ledger T32 M4: the server's own logback.xml, not logback's DEBUG default, so `docker compose logs app` shows what the
     * owner should send: INFO and up, and PDFBox (chatty while it reads a job file) only from WARN.
     */
    @Test fun productionLogsAtInfoWithPdfboxAtWarn() {
        assertNotNull(javaClass.getResource("/logback.xml"))
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        assertFalse(root.isDebugEnabled, "no DEBUG")
        assertTrue(root.isInfoEnabled)
        assertTrue(LoggerFactory.getLogger("com.fieldmaxx.server.delivery.DeliveryWorker").isInfoEnabled)
        for (name in listOf("org.apache.pdfbox.pdmodel.font.PDType1Font", "org.apache.fontbox.ttf.TTFParser")) {
            val pdfbox = LoggerFactory.getLogger(name)
            assertFalse(pdfbox.isInfoEnabled, name)
            assertTrue(pdfbox.isWarnEnabled, name)
        }
    }

    private fun fileDb() = Db("jdbc:sqlite:${java.nio.file.Files.createTempDirectory("fmx-db").resolve("t.db")}")

    @Test fun txRollsBackOnException() {
        val db = fileDb()
        try {
            val ex = assertFailsWith<IllegalStateException> {
                db.tx {
                    createStatement().use { it.execute("INSERT INTO settings(key, value) VALUES ('k','v')") }
                    throw IllegalStateException("boom")
                }
            }
            assertEquals("boom", ex.message)
            val n = db.read { createStatement().use { it.executeQuery("SELECT COUNT(*) FROM settings").use { rs -> rs.next(); rs.getInt(1) } } }
            assertEquals(0, n)
        } finally { db.close() }
    }

    @Test fun concurrentWriteTransactionsBothCommit() {
        val db = fileDb()
        try {
            db.tx { createStatement().use { it.execute("INSERT INTO counters(name, value) VALUES ('c', 0)") } }
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = (1..8).map {
                Thread {
                    try {
                        db.tx {
                            val v = createStatement().use { st -> st.executeQuery("SELECT value FROM counters WHERE name='c'").use { rs -> rs.next(); rs.getInt(1) } }
                            Thread.sleep(10)
                            prepareStatement("UPDATE counters SET value = ? WHERE name='c'").use { ps -> ps.setInt(1, v + 1); ps.executeUpdate() }
                        }
                    } catch (e: Throwable) { errors.add(e) }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
            assertTrue(errors.isEmpty(), "errors: $errors")
            val v = db.read { createStatement().use { it.executeQuery("SELECT value FROM counters WHERE name='c'").use { rs -> rs.next(); rs.getInt(1) } } }
            assertEquals(8, v)
        } finally { db.close() }
    }
}
