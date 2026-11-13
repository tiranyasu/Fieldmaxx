package com.fieldmaxx.server

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.release.publishApk
import com.fieldmaxx.server.users.createUser

/**
 * `create-admin <username> "<Display Name>"` with the password in FMX_ADMIN_PASSWORD.
 * Returns true when [args] was a CLI command (handled, success or not); false means start the server.
 */
fun runCli(args: Array<String>, ctx: Ctx, env: Map<String, String> = System.getenv(), out: (String) -> Unit = ::println): Boolean {
    if (args.firstOrNull() == "publish-apk") {
        val code = args.getOrNull(2)?.toIntOrNull()
        if (args.size != 4 || code == null) {
            out("Usage: publish-apk <path> <versionCode> <versionName>")
            return true
        }
        try {
            val r = publishApk(ctx, java.nio.file.Paths.get(args[1]), code, args[3])
            out("Published ${r.versionName} (code ${r.versionCode}) at ${r.url}")
        } catch (e: Exception) {
            out("Could not publish: ${e.message}")
        }
        return true
    }
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
