package com.fieldmaxx.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fieldmaxx.app.net.Api
import com.fieldmaxx.app.net.ApiException
import com.fieldmaxx.app.ui.FmxColors
import com.fieldmaxx.core.model.AppRelease
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val APK_TYPE = "application/vnd.android.package-archive"

/** Where updates are downloaded: under cacheDir/shared/, the folder the app's FileProvider serves (R69). */
private const val UPDATE_DIR = "shared/update"

/** The server's APK names (release/ReleaseRoutes.kt): `fieldmaxx-<versionName>.apk`, one path segment, nothing to escape. */
private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

private const val NOT_OUR_RELEASE = "This update isn't one our server published, so it wasn't downloaded."
internal const val DAMAGED = "The download didn't match the published update and was deleted. Tap to try again."
internal const val UPDATE_NEEDS_SIGNAL = "Can't reach the server. The update downloads once there is signal."
internal const val ALLOW_INSTALLS = "Allow installs from Fieldmaxx, then come back: the installer opens."
private const val NO_INSTALLER = "This phone has no app installer to open the update."

/** The download wasn't the release the server published, or the release can't be checked: nothing is installed. */
class UpdateRejectedException(message: String) : Exception(message)

/** How far [UpdateInstaller.downloadAndInstall] got. */
enum class InstallStep {
    /** Android's "Install unknown apps" page for Fieldmaxx is open: the installer comes once that is allowed. */
    ALLOW_INSTALLS,

    /** Android's installer has the verified APK. */
    INSTALLER_OPENED,
}

object UpdateTags {
    const val BANNER = "update.banner"
    const val STATUS = "update.status"
}

/**
 * Downloads a published update and hands it to Android's installer (spec §6.11, R69). Security rules:
 * - The APK only ever comes from our own server's release file endpoint, `/app/<name>`, on the server this app talks to;
 *   [AppRelease.url] gives only the file name, which must be a plain `.apk` name.
 * - Only a file whose SHA-256 matches [AppRelease.sha256] is ever handed to the installer. A mismatch deletes the file
 *   and installs nothing ([UpdateRejectedException]); a release without a proper SHA-256 is refused before downloading.
 * - The file lives in `cacheDir/shared/update/` and goes to the installer only through the app's FileProvider.
 *
 * Without the "Install unknown apps" permission ([installsAllowed]), Android's page to allow it opens first; the verified
 * download is kept, so the next call (on return, say) checks it again and opens the installer without downloading again.
 */
class UpdateInstaller(
    private val context: Context,
    private val api: Api,
    private val installsAllowed: () -> Boolean = { context.packageManager.canRequestPackageInstalls() },
) {
    /** Whether Android lets this app open the installer now. */
    fun canInstall(): Boolean = installsAllowed()

    /**
     * Throws [UpdateRejectedException] for a release that isn't checkable or a download that doesn't match it, and
     * [IOException] or [ApiException] when the download fails (nothing is kept then).
     */
    suspend fun downloadAndInstall(r: AppRelease): InstallStep {
        val name = releaseFileName(r.url)
        if (name == null || !SHA256_HEX.matches(r.sha256)) throw UpdateRejectedException(NOT_OUR_RELEASE)
        val apk = File(context.cacheDir, "$UPDATE_DIR/fieldmaxx-${r.versionCode}.apk")
        withContext(Dispatchers.IO) {
            // Older updates go; a verified download kept from the last call stays.
            apk.parentFile?.listFiles()?.filter { it != apk }?.forEach { it.deleteRecursively() }
            if (apk.isFile && sha256(apk).equals(r.sha256, ignoreCase = true)) return@withContext
            apk.delete()
        }
        if (!withContext(Dispatchers.IO) { apk.isFile }) {
            api.download("/app/$name", apk) // relative: our server, its release file endpoint
            val matches = withContext(Dispatchers.IO) { sha256(apk).equals(r.sha256, ignoreCase = true) }
            if (!matches) {
                withContext(Dispatchers.IO) { apk.delete() }
                throw UpdateRejectedException(DAMAGED)
            }
        }
        if (!installsAllowed()) {
            try {
                start(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return InstallStep.ALLOW_INSTALLS
            } catch (e: ActivityNotFoundException) {
                // No such page on this phone: the installer itself asks.
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return InstallStep.INSTALLER_OPENED
    }

    private fun start(intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** The last path segment of [url] when it is a plain APK name, else null. */
private fun releaseFileName(url: String): String? =
    url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { APK_NAME.matches(it) }

private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * "Update available — tap to install" while [updates] has a newer release (spec §6.11); nothing otherwise. A tap
 * downloads, checks and opens Android's installer. When Android first asks to allow installs from Fieldmaxx, coming
 * back with that allowed goes on to the installer without another tap.
 */
@Composable
fun UpdateBanner(updates: StateFlow<AppRelease?>, installer: UpdateInstaller, modifier: Modifier = Modifier) {
    val release by updates.collectAsState(Dispatchers.Main.immediate)
    val r = release ?: return
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var awaitingAllow by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun install() {
        if (busy) return
        busy = true
        status = null
        scope.launch(Dispatchers.Main.immediate) { // the installer is started from the main thread
            status = try {
                awaitingAllow = installer.downloadAndInstall(r) == InstallStep.ALLOW_INSTALLS
                if (awaitingAllow) ALLOW_INSTALLS else null
            } catch (e: UpdateRejectedException) {
                e.message
            } catch (e: IOException) {
                UPDATE_NEEDS_SIGNAL
            } catch (e: ApiException) {
                e.message
            } catch (e: ActivityNotFoundException) {
                NO_INSTALLER
            } finally {
                busy = false
            }
        }
    }

    LifecycleResumeEffect(awaitingAllow) {
        if (awaitingAllow && installer.canInstall()) {
            awaitingAllow = false
            install()
        }
        onPauseOrDispose { }
    }

    Surface(onClick = ::install, enabled = !busy, color = FmxColors.Accent, contentColor = Color.White, modifier = modifier.fillMaxWidth().testTag(UpdateTags.BANNER)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(UPDATE_BANNER, fontWeight = FontWeight.Bold)
            Text(
                if (busy) "Downloading version ${r.versionName}…" else status ?: "Version ${r.versionName}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(UpdateTags.STATUS),
            )
        }
    }
}
