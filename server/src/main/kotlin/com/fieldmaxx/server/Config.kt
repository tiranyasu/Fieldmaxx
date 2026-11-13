package com.fieldmaxx.server

import java.nio.file.Path
import java.nio.file.Paths

data class Config(
    val dataDir: Path,
    val port: Int,
    val publicOrigin: String,
    val sessionDays: Int,
    val destination: String, // sharepoint|fake
    val buildId: String,
)

fun loadConfig(env: Map<String, String> = System.getenv()): Config {
    val port = env["PORT"]?.toIntOrNull() ?: 8080
    return Config(
        dataDir = Paths.get(env["FMX_DATA_DIR"] ?: "./data"),
        port = port,
        publicOrigin = env["FMX_PUBLIC_ORIGIN"] ?: "http://localhost:$port",
        sessionDays = 90,
        destination = env["FMX_DESTINATION"] ?: "sharepoint",
        buildId = env["FMX_BUILD_ID"] ?: "dev",
    )
}
