package com.fieldmaxx.server.jobs

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.ImportPreview
import com.fieldmaxx.core.model.RefImageInfo
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.importer.ImportException
import com.fieldmaxx.importer.importFile
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.auth.user
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

/**
 * Reads the file, stores it under `imports/<id>/` and its reference images under `refs/`, and returns the preview for
 * the check screen. Reference image ids are prefixed with the import id so they are unique across the server.
 */
fun storeImport(ctx: Ctx, userId: String, fileName: String, bytes: ByteArray): ImportPreview {
    val r = try {
        importFile(fileName, bytes)
    } catch (e: ImportException) {
        throw if (e.code == "unsupported_type") HttpError(HttpStatusCode.UnsupportedMediaType, "Only PDF and Word (.docx) files")
        else HttpError(HttpStatusCode.BadRequest, e.message ?: "That file could not be read")
    }
    val importId = UUID.randomUUID().toString()
    val ext = r.kind
    val rel = "imports/$importId/source.$ext"
    Files.createDirectories(ctx.path(rel).parent)
    Files.write(ctx.path(rel), bytes)
    Files.createDirectories(ctx.path("refs"))

    fun scoped(id: String) = "$importId-$id"
    val preview = ImportPreview(
        importId = importId, fileName = fileName, kind = r.kind, layerSuggestion = r.layerSuggestion, notes = r.notes,
        points = r.points.map { p -> p.copy(refImageIds = p.refImageIds.map(::scoped)) },
        unread = r.unread,
        refImages = r.refImages.map { RefImageInfo(scoped(it.id), it.kind, it.page) },
    )
    val kinds = r.refImages.associateBy { it.id }
    ctx.db.tx {
        for (img in r.images) {
            val info = kinds[img.id] ?: continue
            val relImg = "refs/${scoped(img.id)}.${if (img.mime == "image/jpeg") "jpg" else "png"}"
            Files.write(ctx.path(relImg), img.bytes)
            prepareStatement("INSERT INTO ref_images(id, job_id, import_id, kind, page, stored_path, mime) VALUES (?,NULL,?,?,?,?,?)").use {
                it.setString(1, scoped(img.id)); it.setString(2, importId); it.setString(3, info.kind)
                if (info.page == null) it.setNull(4, java.sql.Types.INTEGER) else it.setInt(4, info.page!!)
                it.setString(5, relImg); it.setString(6, img.mime); it.executeUpdate()
            }
        }
        prepareStatement("INSERT INTO imports(id, user_id, file_name, kind, stored_path, result_json, created_at) VALUES (?,?,?,?,?,?,?)").use {
            it.setString(1, importId); it.setString(2, userId); it.setString(3, fileName); it.setString(4, r.kind)
            it.setString(5, rel); it.setString(6, FmxJson.encodeToString(ImportPreview.serializer(), preview))
            it.setLong(7, ctx.clock.millis()); it.executeUpdate()
        }
    }
    return preview
}

/** The stored reference image whose id matches and whose import/job column equals [owner]. */
internal fun refImage(ctx: Ctx, column: String, owner: String, refId: String, user: User): Pair<ByteArray, String> {
    val row = ctx.db.read {
        prepareStatement(
            "SELECT r.stored_path, r.mime FROM ref_images r LEFT JOIN jobs j ON j.id = r.job_id " +
                "WHERE r.id = ? AND r.$column = ? AND (j.deleted_at IS NULL OR ?)",
        ).use { ps ->
            ps.setString(1, refId); ps.setString(2, owner); ps.setBoolean(3, user.role == Role.admin)
            ps.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        }
    } ?: throw HttpError(HttpStatusCode.NotFound, "Image not found")
    return Files.readAllBytes(ctx.path(row.first)) to row.second
}

fun Route.importRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/imports") {
            post {
                var name: String? = null
                var bytes: ByteArray? = null
                var tooLarge = false
                try { call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES + 1).forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file" && bytes == null && !tooLarge) {
                        name = part.originalFileName
                        val read = part.provider().readRemaining(MAX_UPLOAD_BYTES + 1).readByteArray()
                        if (read.size > MAX_UPLOAD_BYTES) tooLarge = true else bytes = read
                    }
                    part.dispose()
                } } catch (e: java.io.IOException) {
                    if (e.message?.contains("exceeds limit") == true) tooLarge = true else throw e // ktor's own multipart cap
                }
                if (tooLarge) throw HttpError(HttpStatusCode.PayloadTooLarge, "File is too large (max 50 MB)")
                val fileName = name?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Choose a file to upload")
                val data = bytes ?: throw IllegalArgumentException("Choose a file to upload")
                val userId = call.user().id
                call.respond(withContext(Dispatchers.IO) { storeImport(ctx, userId, fileName, data) })
            }
            get("/{importId}/ref/{refId}") {
                val (bytes, mime) = refImage(ctx, "import_id", call.parameters["importId"]!!, call.parameters["refId"]!!, call.user())
                call.respondBytes(bytes, ContentType.parse(mime))
            }
        }
    }
}
