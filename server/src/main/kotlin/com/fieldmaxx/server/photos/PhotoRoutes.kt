package com.fieldmaxx.server.photos

import com.fieldmaxx.core.model.PhotoRegistration
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.auth.user
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

@Serializable data class BackupProgress(val offset: Long, val complete: Boolean)
@Serializable data class BackupConflict(val error: String, val offset: Long, val complete: Boolean)

private fun ApplicationCall.queryDouble(name: String): Double =
    request.queryParameters[name]?.toDoubleOrNull() ?: throw HttpError(HttpStatusCode.BadRequest, "Missing or bad $name")

/** At most [MAX_CHUNK_BYTES] + 1 bytes are read, so an oversized request never fills memory. */
private suspend fun ApplicationCall.receiveChunk(): ByteArray {
    val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declared != null && declared > MAX_CHUNK_BYTES) throw HttpError(HttpStatusCode.PayloadTooLarge, "Chunks are at most 2 MB")
    return receiveChannel().readRemaining((MAX_CHUNK_BYTES + 1).toLong()).readByteArray()
}

fun Route.photoRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/photos") {
            post("/register") { call.respond(registerPhoto(ctx, call.user(), call.receive<PhotoRegistration>())) }
            get("/nearby") {
                call.respond(
                    nearbyPhotos(
                        ctx, call.queryDouble("lat"), call.queryDouble("lon"),
                        call.request.queryParameters["radiusM"]?.toDoubleOrNull() ?: DEFAULT_NEARBY_RADIUS_M,
                        call.request.queryParameters["excludePointId"],
                    ),
                )
            }
            route("/{id}") {
                get { call.respond(getPhoto(ctx, call.parameters["id"]!!)) }
                post("/rename") {
                    val from = call.request.queryParameters["from"]?.takeIf { it.isNotBlank() }
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Missing from: the name the photo leaves")
                    call.respond(renamePhoto(ctx, call.user(), call.parameters["id"]!!, from))
                }
                put("/backup") {
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Missing or bad offset")
                    val bytes = call.receiveChunk()
                    try {
                        val (off, complete) = appendBackupChunk(ctx, call.user(), call.parameters["id"]!!, offset, bytes)
                        call.respond(BackupProgress(off, complete))
                    } catch (e: BackupOffsetConflict) {
                        call.respond(HttpStatusCode.Conflict, BackupConflict("Wrong offset", e.current, e.complete))
                    }
                }
                get("/thumb") {
                    val bytes = photoFile(ctx, call.parameters["id"]!!, thumb = true) ?: throw HttpError(HttpStatusCode.NotFound, "No thumbnail")
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }
                get("/file") {
                    val bytes = photoFile(ctx, call.parameters["id"]!!, thumb = false) ?: throw HttpError(HttpStatusCode.NotFound, "No server copy")
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }
            }
        }
        get("/jobs/{id}/photos") {
            val user = call.user()
            val jobId = call.parameters["id"]!!
            val deleted = ctx.db.read {
                prepareStatement("SELECT deleted_at FROM jobs WHERE id = ?").use { ps ->
                    ps.setString(1, jobId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) != null else throw HttpError(HttpStatusCode.NotFound, "Job not found") }
                }
            }
            if (deleted && user.role != Role.admin) throw HttpError(HttpStatusCode.NotFound, "Job not found")
            call.respond(jobPhotos(ctx, jobId))
        }
    }
}
