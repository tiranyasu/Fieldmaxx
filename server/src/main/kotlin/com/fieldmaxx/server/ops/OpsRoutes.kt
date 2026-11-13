package com.fieldmaxx.server.ops

import com.fieldmaxx.core.model.Op
import com.fieldmaxx.core.model.OpResult
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.auth.user
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable data class OpsRequest(val ops: List<Op>)
@Serializable data class OpsResponse(val results: List<OpResult>)

fun Route.opsRoutes(ctx: Ctx) {
    authenticate("bearer") {
        post("/ops") {
            call.respond(OpsResponse(applyOps(ctx, call.user(), call.receive<OpsRequest>().ops)))
        }
    }
}
