package com.fieldmaxx.server.delivery

import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.jobs.path
import com.fieldmaxx.server.photos.backupStillNeeded
import com.fieldmaxx.server.photos.regenerateMissingThumbs
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

const val MIN_BACKUP_DAYS = 3
/** `settings` key of the admin's "Keep backups for N days". */
const val BACKUP_DAYS_SETTING = "backupDays"
const val DISK_WARN_PCT = 80
private const val DAY_MS = 24L * 3600 * 1000
private val log = LoggerFactory.getLogger("com.fieldmaxx.server.delivery.Retention")

/** The admin's "Keep backups for N days" (default and minimum 3; a lower stored value counts as 3). */
fun backupDays(ctx: Ctx): Int {
    val stored = ctx.db.read {
        prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
            ps.setString(1, BACKUP_DAYS_SETTING); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }
    return (stored?.trim()?.toIntOrNull() ?: MIN_BACKUP_DAYS).coerceAtLeast(MIN_BACKUP_DAYS)
}

/** Purgeable: confirmed and older than the retention time, or removed without ever reaching OneDrive. */
private const val PURGEABLE =
    "backup_path IS NOT NULL AND backup_purged_at IS NULL AND " +
        "((confirmed_at IS NOT NULL AND backup_received_at <= ?) OR (status = 'removed' AND confirmed_at IS NULL))"

/**
 * Deletes server copies that are no longer needed: a photo confirmed in OneDrive whose copy arrived more than
 * [backupDays] ago, and a removed photo that never reached OneDrive. Never one an undelivered reuse copy still takes
 * its image from, and never an unconfirmed photo's copy otherwise. Thumbnails stay. Returns how many were deleted.
 */
fun purgeBackups(ctx: Ctx): Int {
    val now = ctx.clock.millis()
    val cutoff = now - backupDays(ctx) * DAY_MS
    val ids = ctx.db.read {
        prepareStatement("SELECT id FROM photos WHERE $PURGEABLE").use { ps ->
            ps.setLong(1, cutoff); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
    var purged = 0
    for (id in ids) {
        // Checked again inside the write: a reuse made since the listing may need this copy.
        val rel = ctx.db.tx {
            val rel = prepareStatement("SELECT backup_path FROM photos WHERE id = ? AND $PURGEABLE").use { ps ->
                ps.setString(1, id); ps.setLong(2, cutoff); ps.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
            if (rel == null || backupStillNeeded(id)) return@tx null
            prepareStatement("UPDATE photos SET backup_path = NULL, backup_purged_at = ? WHERE id = ?").use {
                it.setLong(1, now); it.setString(2, id); it.executeUpdate()
            }
            rel
        } ?: continue
        runCatching { Files.deleteIfExists(ctx.path(rel)) }.onFailure { log.warn("Could not delete backup {}: {}", rel, it.message) }
        purged++
    }
    return purged
}

/** Percent of the disk holding [dir] that is in use (0–100). */
fun diskUsedPct(dir: Path): Int {
    val store = Files.getFileStore(dir)
    val total = store.totalSpace
    if (total <= 0) return 0
    return ((total - store.usableSpace) * 100 / total).toInt().coerceIn(0, 100)
}

data class RetentionRun(val purged: Int, val thumbsMade: Int, val diskUsedPct: Int, val diskWarning: Boolean)

/**
 * The hourly backup upkeep: purge what retention allows, retry missing thumbnails, and warn when the disk is over 80%
 * full. A full disk never makes it delete anything early; the admin sees the figure in Delivery health.
 */
fun backupMaintenance(ctx: Ctx, usedPct: Int = diskUsedPct(ctx.config.dataDir)): RetentionRun {
    val warn = usedPct > DISK_WARN_PCT
    if (warn) log.warn("Disk is {}% full (over {}%). Backups are still kept for their full time: add disk space.", usedPct, DISK_WARN_PCT)
    val purged = purgeBackups(ctx)
    val thumbs = regenerateMissingThumbs(ctx)
    return RetentionRun(purged, thumbs, usedPct, warn)
}
