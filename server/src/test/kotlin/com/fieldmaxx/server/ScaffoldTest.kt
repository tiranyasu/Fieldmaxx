package com.fieldmaxx.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
}
