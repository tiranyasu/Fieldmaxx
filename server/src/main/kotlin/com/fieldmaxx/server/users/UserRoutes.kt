package com.fieldmaxx.server.users

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.core.model.User
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.auth.requireAdmin
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class CreateUserRequest(val username: String, val displayName: String, val password: String, val role: Role = Role.tech)
@Serializable data class UpdateUserRequest(
    val displayName: String? = null, val role: Role? = null, val disabled: Boolean? = null, val password: String? = null,
)
@Serializable data class UsersBody(val users: List<User>)

fun Route.userRoutes(ctx: Ctx) {
    authenticate("bearer") {
        route("/admin/users") {
            get {
                call.requireAdmin()
                call.respond(UsersBody(listUsers(ctx)))
            }
            post {
                call.requireAdmin()
                val r = call.receive<CreateUserRequest>()
                call.respond(HttpStatusCode.Created, createUser(ctx, r.username, r.displayName, r.password, r.role))
            }
            patch("/{id}") {
                call.requireAdmin()
                val r = call.receive<UpdateUserRequest>()
                call.respond(updateUser(ctx, call.parameters["id"]!!, r.displayName, r.role, r.disabled, r.password))
            }
        }
    }
}
