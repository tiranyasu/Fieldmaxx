package com.fieldmaxx.server

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.users.createUser

/**
 * `create-admin <username> "<Display Name>"` with the password in FMX_ADMIN_PASSWORD.
 * Returns true when [args] was a CLI command (handled, success or not); false means start the server.
 */
fun runCli(args: Array<String>, ctx: Ctx, env: Map<String, String> = System.getenv(), out: (String) -> Unit = ::println): Boolean {
    if (args.firstOrNull() != "create-admin") return false
    val password = env["FMX_ADMIN_PASSWORD"]
    if (args.size != 3 || password.isNullOrEmpty()) {
        out("Usage: FMX_ADMIN_PASSWORD=... create-admin <username> \"<Display Name>\"")
        return true
    }
    try {
        val user = createUser(ctx, args[1], args[2], password, Role.admin)
        out("Created admin '${user.username}'")
    } catch (e: Exception) {
        out("Could not create admin: ${e.message}")
    }
    return true
}
