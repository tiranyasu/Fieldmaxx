package com.fieldmaxx.server.jobs

import com.fieldmaxx.core.model.CreateJobInput
import com.fieldmaxx.core.model.JobImportInput
import com.fieldmaxx.core.model.Point
import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import com.fieldmaxx.server.auth.user
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.nio.file.Files
import kotlinx.serialization.Serializable

@Serializable data class CreateJobResponse(val job: com.fieldmaxx.core.model.Job, val skippedDuplicates: List<String>)
@Serializable data class AddImportResponse(val points: List<Point>, val skippedDuplicates: List<String>)
@Serializable data class UpdateJobRequest(val name: String? = null, val fieldNotesUrl: String? = null, val notes: String? = null)

/** A deleted job is gone for techs (404); admins can still read it, e.g. before restoring. */
private fun requireReadable(ctx: Ctx, user: User, jobId: String) {
    val deleted = ctx.db.read {
        prepareStatement("SELECT deleted_at FROM jobs WHERE id = ?").use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) != null else throw HttpError(HttpStatusCode.NotFound, "Job not found") }
        }
    }
    if (deleted && user.role != Role.admin) throw HttpError(HttpStatusCode.NotFound, "Job not found")
}

fun Route.jobRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/jobs") {
            get {
                val include = call.request.queryParameters["include"].orEmpty().split(',').map { it.trim() }
                call.respond(listJobs(ctx, call.user(), "finished" in include, "deleted" in include))
            }
            post {
                val (job, skipped) = createJob(ctx, call.user(), call.receive<CreateJobInput>())
                call.respond(HttpStatusCode.Created, CreateJobResponse(job, skipped))
            }
            route("/{id}") {
                get("/snapshot") {
                    requireReadable(ctx, call.user(), call.parameters["id"]!!)
                    call.respond(jobSnapshot(ctx, call.parameters["id"]!!))
                }
                patch {
                    val r = call.receive<UpdateJobRequest>()
                    call.respond(updateJob(ctx, call.parameters["id"]!!, r.name, r.fieldNotesUrl, r.notes))
                }
                delete {
                    deleteJob(ctx, call.user(), call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/restore") {
                    restoreJob(ctx, call.user(), call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/imports") {
                    val (points, skipped) = addImportToJob(ctx, call.user(), call.parameters["id"]!!, call.receive<JobImportInput>())
                    call.respond(HttpStatusCode.Created, AddImportResponse(points, skipped))
                }
                get("/ref/{refId}") {
                    val (bytes, mime) = refImage(ctx, "job_id", call.parameters["id"]!!, call.parameters["refId"]!!, call.user())
                    call.respondBytes(bytes, ContentType.parse(mime))
                }
                get("/files/{fileId}") {
                    requireReadable(ctx, call.user(), call.parameters["id"]!!)
                    val row = ctx.db.read {
                        prepareStatement("SELECT original_name, stored_path, kind FROM job_files WHERE id = ? AND job_id = ?").use { ps ->
                            ps.setString(1, call.parameters["fileId"]!!); ps.setString(2, call.parameters["id"]!!)
                            ps.executeQuery().use { if (it.next()) Triple(it.getString(1), it.getString(2), it.getString(3)) else null }
                        }
                    } ?: throw HttpError(HttpStatusCode.NotFound, "File not found")
                    val type = if (row.third == "pdf") ContentType.Application.Pdf
                    else ContentType.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, row.first).toString())
                    call.respondBytes(Files.readAllBytes(ctx.path(row.second)), type)
                }
            }
        }
    }
}
