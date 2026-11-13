package com.fieldmaxx.server

import com.fieldmaxx.core.model.Role
import com.fieldmaxx.server.release.publishApk
import com.fieldmaxx.server.users.createUser

/**
 * `create-admin <username> "<Display Name>"` with the password in FMX_ADMIN_PASSWORD.
 * Also `publish-apk <path> <versionCode> <versionName>`. Returns [CliResult.NotACommand] to start the server; otherwise
 * whether the command succeeded (main exits non-zero on [CliResult.Failed] so scripts can tell).
 */
enum class CliResult { NotACommand, Done, Failed }

fun runCli(args: Array<String>, ctx: Ctx, env: Map<String, String> = System.getenv(), out: (String) -> Unit = ::println): CliResult {
    if (args.firstOrNull() == "publish-apk") {
        val code = args.getOrNull(2)?.toIntOrNull()
        if (args.size != 4 || code == null) {
            out("Usage: publish-apk <path> <versionCode> <versionName>")
            return CliResult.Failed
        }
        try {
            val r = publishApk(ctx, java.nio.file.Paths.get(args[1]), code, args[3])
            out("Published ${r.versionName} (code ${r.versionCode}) at ${r.url}")
            return CliResult.Done
        } catch (e: Exception) {
            out("Could not publish: ${e.message}")
            return CliResult.Failed
        }
    }
    if (args.firstOrNull() != "create-admin") return CliResult.NotACommand
    val password = env["FMX_ADMIN_PASSWORD"]
    if (args.size != 3 || password.isNullOrEmpty()) {
        out("Usage: FMX_ADMIN_PASSWORD=... create-admin <username> \"<Display Name>\"")
        return CliResult.Failed
    }
    try {
        val user = createUser(ctx, args[1], args[2], password, Role.admin)
        out("Created admin '${user.username}'")
        return CliResult.Done
    } catch (e: Exception) {
        out("Could not create admin: ${e.message}")
        return CliResult.Failed
    }
}
