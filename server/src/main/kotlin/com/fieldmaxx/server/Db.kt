package com.fieldmaxx.server

import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite access: one short-lived connection per call (WAL, foreign keys on). A connection is also held open for the
 * lifetime of the Db so that shared-cache in-memory databases survive between calls.
 */
class Db(private val url: String) : AutoCloseable {
    private val keepAlive: Connection = open()

    init {
        migrate(keepAlive)
    }

    private fun open(): Connection {
        val c = DriverManager.getConnection(url)
        c.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA busy_timeout = 5000")
            it.execute("PRAGMA journal_mode = WAL")
        }
        return c
    }

    fun <T> tx(block: Connection.() -> T): T = open().use { c ->
        c.autoCommit = false
        try {
            val r = c.block()
            c.commit()
            r
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        }
    }

    fun <T> read(block: Connection.() -> T): T = open().use { it.block() }

    override fun close() = keepAlive.close()

    private fun migrate(c: Connection) {
        c.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL)")
        }
        var n = 1
        while (true) {
            val prefix = "%03d_".format(n)
            val name = MIGRATIONS.firstOrNull { it.startsWith(prefix) } ?: break
            val done = c.prepareStatement("SELECT 1 FROM schema_migrations WHERE name = ?").use { ps ->
                ps.setString(1, name); ps.executeQuery().use { it.next() }
            }
            if (!done) {
                val sql = Db::class.java.getResourceAsStream("/migrations/$name")!!.bufferedReader().readText()
                c.autoCommit = false
                try {
                    sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
                        c.createStatement().use { it.execute(s) }
                    }
                    c.prepareStatement("INSERT INTO schema_migrations(name, applied_at) VALUES (?, ?)").use { ps ->
                        ps.setString(1, name); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate()
                    }
                    c.commit()
                } catch (e: Throwable) {
                    c.rollback(); throw e
                } finally {
                    c.autoCommit = true
                }
            }
            n++
        }
    }

    companion object {
        /** Migration files in order; add new files here. */
        private val MIGRATIONS = listOf("001_init.sql")
    }
}
