package com.fieldmaxx.server.exports

import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.auth.requireAdmin
import com.fieldmaxx.server.auth.user
import com.fieldmaxx.server.jobs.requireReadable
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private val XLSX = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

fun Route.exportRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/jobs/{id}") {
            get("/pole-data.csv") {
                requireReadable(ctx, call.user(), call.parameters["id"]!!)
                attachment("pole-data.csv")
                call.respondBytes(poleDataCsv(poleDataRows(ctx, call.parameters["id"]!!)).toByteArray(), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
            }
            get("/pole-data.xlsx") {
                requireReadable(ctx, call.user(), call.parameters["id"]!!)
                attachment("pole-data.xlsx")
                call.respondBytes(poleDataXlsx(poleDataRows(ctx, call.parameters["id"]!!)), XLSX)
            }
            get("/photos.zip") {
                val user = call.requireAdmin()
                val id = call.parameters["id"]!!
                requireReadable(ctx, user, id)
                attachment("photos.zip")
                call.respondOutputStream(ContentType.Application.Zip) { writePhotosZip(ctx, id, this) }
            }
            post("/finish") {
                requireReadable(ctx, call.user(), call.parameters["id"]!!)
                call.respond(finishJob(ctx, call.user(), call.parameters["id"]!!))
            }
        }
    }
}

private fun io.ktor.server.routing.RoutingContext.attachment(name: String) =
    call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
