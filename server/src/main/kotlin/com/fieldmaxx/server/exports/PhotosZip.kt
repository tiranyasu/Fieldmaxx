package com.fieldmaxx.server.exports

import com.fieldmaxx.core.naming.defaultFolderName
import com.fieldmaxx.core.naming.isQueryLayer
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.jobs.path
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class ZipPhoto(val fileName: String, val backupPath: String, val folder: String)

/**
 * Streams the job's backed-up photos laid out like the Field Notes folder: the point's folder (or the one the folder
 * resolver would create) and the photo's own file name. Photos whose backup is gone are left out. JPEGs are already
 * compressed, so entries are written at level 0, one file at a time.
 */
fun writePhotosZip(ctx: Ctx, jobId: String, out: OutputStream) {
    val photos = ctx.db.read {
        prepareStatement(
            "SELECT ph.file_name, ph.backup_path, p.folder_path, p.layer, p.type, p.label FROM photos ph JOIN points p ON p.id = ph.point_id " +
                "WHERE ph.job_id = ? AND ph.status != 'removed' AND ph.backup_path IS NOT NULL AND ph.backup_purged_at IS NULL ORDER BY ph.created_at, ph.id",
        ).use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val folder = rs.getString(3)?.takeIf { it.isNotBlank() }
                            ?: "${if (isQueryLayer(rs.getString(4))) rs.getString(4).trim() else rs.getString(5)}/${defaultFolderName(rs.getString(6))}"
                        add(ZipPhoto(rs.getString(1), rs.getString(2), folder))
                    }
                }
            }
        }
    }
    val used = HashSet<String>()
    ZipOutputStream(out).use { zip ->
        zip.setLevel(0)
        for (p in photos) {
            val file = ctx.path(p.backupPath)
            if (!Files.exists(file)) continue
            val name = "${p.folder.trim('/')}/${p.fileName}"
            if (!used.add(name)) continue
            zip.putNextEntry(ZipEntry(name))
            Files.newInputStream(file).use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }
    }
}
