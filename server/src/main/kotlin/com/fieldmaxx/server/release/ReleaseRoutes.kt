package com.fieldmaxx.server.release

import com.fieldmaxx.core.FmxJson
import com.fieldmaxx.core.model.AppRelease
import com.fieldmaxx.server.Ctx
import com.fieldmaxx.server.HttpError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.builtins.nullable

val APK_TYPE: ContentType = ContentType("application", "vnd.android.package-archive")
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val VERSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")

private data class ReleaseRow(val code: Int, val name: String, val filePath: String, val sha256: String)

private fun Ctx.latestRow(): ReleaseRow? = db.read {
    createStatement().use { st ->
        st.executeQuery("SELECT version_code, version_name, file_path, sha256 FROM app_releases ORDER BY version_code DESC LIMIT 1").use {
            if (it.next()) ReleaseRow(it.getInt(1), it.getString(2), it.getString(3), it.getString(4)) else null
        }
    }
}

private fun apkFileName(versionName: String) = "fieldmaxx-$versionName.apk"

/** The newest published release (highest versionCode), or null. */
fun latestRelease(ctx: Ctx): AppRelease? = ctx.latestRow()?.let {
    AppRelease(it.code, it.name, "${ctx.config.publicOrigin.trimEnd('/')}/app/${apkFileName(it.name)}", it.sha256)
}

/** Copies [source] into `<dataDir>/apk/` and records it. Throws IllegalArgumentException on bad input or a repeated version. */
fun publishApk(ctx: Ctx, source: java.nio.file.Path, versionCode: Int, versionName: String): AppRelease {
    require(Files.isRegularFile(source)) { "No such file: $source" }
    val magic = Files.newInputStream(source).use { it.readNBytes(4) }
    require(magic.isNotEmpty()) { "The file is empty" }
    require(magic.contentEquals(ZIP_MAGIC)) { "That is not an APK (it does not start with the ZIP header PK\\x03\\x04)" }
    require(versionCode > 0) { "versionCode must be a positive number" }
    require(VERSION_NAME.matches(versionName)) { "versionName may only use letters, digits, '.', '_' and '-'" }
    val dir = ctx.config.dataDir.resolve("apk")
    Files.createDirectories(dir)
    val rel = "apk/${apkFileName(versionName)}"
    val target = ctx.config.dataDir.resolve(rel)
    require(!Files.exists(target)) { "Version name $versionName is already published" }
    val tmp = Files.createTempFile(dir, "upload-", ".part")
    val sha = try {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
            }
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        md.digest().joinToString("") { "%02x".format(it) }
    } finally {
        Files.deleteIfExists(tmp)
    }
    try {
        ctx.db.tx {
            val exists = prepareStatement("SELECT 1 FROM app_releases WHERE version_code = ?").use { ps ->
                ps.setInt(1, versionCode); ps.executeQuery().use { it.next() }
            }
            require(!exists) { "Version code $versionCode is already published" }
            prepareStatement("INSERT INTO app_releases(version_code, version_name, file_path, sha256, created_at) VALUES (?,?,?,?,?)").use {
                it.setInt(1, versionCode); it.setString(2, versionName); it.setString(3, rel); it.setString(4, sha)
                it.setLong(5, ctx.clock.millis()); it.executeUpdate()
            }
        }
    } catch (e: Throwable) {
        runCatching { Files.deleteIfExists(target) }
        throw e
    }
    return AppRelease(versionCode, versionName, "${ctx.config.publicOrigin.trimEnd('/')}/app/${apkFileName(versionName)}", sha)
}

/** `/api/app/latest` for signed-in phones. */
fun Route.releaseApiRoutes(ctx: Ctx) {
    authenticate("bearer") {
        get("/app/latest") { call.respondText(FmxJson.encodeToString(AppRelease.serializer().nullable, latestRelease(ctx)), ContentType.Application.Json) }
    }
}

/** The public side: the APK file and the download page. Mounted at the root, outside `/api`. */
fun Route.releasePublicRoutes(ctx: Ctx) {
    get("/") {
        call.respondText(downloadPage(latestRelease(ctx)), ContentType.Text.Html)
    }
    get("/app/{file}") {
        // The URL text is only a key: the path to serve always comes from the database.
        val file = call.parameters["file"].orEmpty()
        val rel = ctx.db.read {
            prepareStatement("SELECT version_name, file_path FROM app_releases").use { ps ->
                ps.executeQuery().use { rs ->
                    var found: String? = null
                    while (rs.next()) if (apkFileName(rs.getString(1)) == file) found = rs.getString(2)
                    found
                }
            }
        } ?: throw HttpError(HttpStatusCode.NotFound, "No such version")
        val path = ctx.config.dataDir.resolve(rel)
        if (!Files.isRegularFile(path)) throw HttpError(HttpStatusCode.NotFound, "No such version")
        call.respond(LocalFileContent(path.toFile(), APK_TYPE))
    }
}
