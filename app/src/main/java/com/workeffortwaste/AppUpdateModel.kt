package com.workeffortwaste

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
)

private const val MAX_APK_BYTES = 100 * 1024 * 1024L

internal class UpdateNotReadyException : IllegalArgumentException(
    "A newer release is available, but its APK hasn't been uploaded yet. Try again later.",
)

/** Accepts only a newer stable release with one bounded, checksum-addressed project APK. */
internal fun parseAppRelease(json: JSONObject, installedVersion: String): AppRelease? {
    require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) {
        "The latest release is not a stable release."
    }
    val version = json.getString("tag_name")
    if (ReleaseVersion.parse(version) <= ReleaseVersion.parse(installedVersion)) return null
    val assets = json.getJSONArray("assets")
    val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
        .filter { it.getString("name") == "fm1-backup-restore.apk" }
    if (matches.isEmpty()) throw UpdateNotReadyException()
    require(matches.size == 1) { "The release must contain one fm1-backup-restore.apk asset." }
    val asset = matches.single()
    val digest = asset.getString("digest")
    require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) {
        "The release APK is missing its GitHub SHA-256 digest."
    }
    val size = asset.getLong("size")
    require(size in 1..MAX_APK_BYTES) { "The release APK size is invalid." }
    val apkUrl = asset.getString("browser_download_url")
    require(apkUrl.startsWith(
        "https://github.com/workeffortwaste/fm1-backup-restore/releases/download/",
    )) { "The APK must be hosted in this project's GitHub Releases." }
    return AppRelease(version, json.optString("body"), apkUrl, size, digest.removePrefix("sha256:"))
}

/** Rejects mismatched package/version metadata and APKs not signed by the installed app's key. */
@Suppress("DEPRECATION")
internal fun verifyUpdateApk(current: PackageInfo, candidate: PackageInfo, releaseVersion: String) {
    require(candidate.packageName == current.packageName) { "APK belongs to a different app." }
    val currentCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
    val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
    require(candidateCode > currentCode) { "APK version code must be newer than the installed app." }
    require(ReleaseVersion.parse(candidate.versionName.orEmpty()).compareTo(ReleaseVersion.parse(releaseVersion)) == 0) {
        "APK version does not match the release."
    }
    val currentSigners = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
    val candidateSigners = if (Build.VERSION.SDK_INT >= 28) candidate.signingInfo?.apkContentsSigners else candidate.signatures
    require(!currentSigners.isNullOrEmpty() && !candidateSigners.isNullOrEmpty() &&
        currentSigners.toSet() == candidateSigners.toSet()) {
        "APK signing key differs from this installation. Install updates from the original distributor."
    }
}

internal data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val release: AppRelease? = null,
    val apk: File? = null,
    val message: String = "",
    val showDialog: Boolean = false,
)

/** Coordinates bounded release checks, verified downloads, and the Android installer handoff. */
internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(AppUpdateState())
        private set
    var installerOpen by mutableStateOf(false)
        private set

    private val preferences = application.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val updateDirectory = File(application.cacheDir, "updates")
    private val installed: PackageInfo
        get() = packageInfo(null)

    /** Non-null for debug or non-release installs; every check is skipped without touching the network. */
    val unavailableReason: String? = updatesUnavailableReason(
        debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        versionName = installed.versionName.orEmpty(),
    )

    fun check(manual: Boolean) {
        if (unavailableReason != null) return
        if (state.checking || state.downloading || installerOpen) return
        val now = System.currentTimeMillis()
        if (!manual && !updateCheckDue(now, preferences.getLong("last_check", 0))) return
        preferences.edit { putLong("last_check", now) }
        state = state.copy(checking = true, message = "", showDialog = manual)
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchRelease() }
                state = state.copy(
                    checking = false,
                    release = release,
                    apk = null,
                    message = if (release == null) "You're up to date." else "",
                    showDialog = manual || release != null,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: UpdateNotReadyException) {
                state = state.copy(release = null, apk = null)
                reportFailure("Update not ready", failure)
            } catch (failure: Exception) {
                reportFailure("Could not check for updates", failure)
            }
        }
    }

    fun dismiss() {
        if (!state.checking && !state.downloading && !installerOpen) {
            state = state.copy(showDialog = false)
        }
    }

    fun show() {
        state = state.copy(showDialog = true)
    }

    fun download() {
        val release = state.release ?: return
        if (state.checking || state.downloading || installerOpen) return
        state = state.copy(downloading = true, progress = 0, message = "", apk = null)
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadAndVerify(release) }
                state = state.copy(downloading = false, apk = apk, progress = 100)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportFailure("Could not download update", failure)
            }
        }
    }

    fun installerOpened() {
        installerOpen = true
    }

    fun installerClosed() {
        installerOpen = false
    }

    fun installationFailed(failure: Exception) {
        installerOpen = false
        reportFailure("Could not open update installer", failure)
    }

    fun verifyForInstallation(): File {
        val apk = requireNotNull(state.apk) { "Download the update again." }
        try {
            verifyApk(apk)
        } catch (failure: Exception) {
            state = state.copy(apk = null)
            throw failure
        }
        return apk
    }

    private fun reportFailure(action: String, failure: Exception) {
        Log.w("AppUpdate", action, failure)
        state = state.copy(
            checking = false,
            downloading = false,
            message = "$action: ${failure.message ?: failure.javaClass.simpleName}",
        )
    }

    private fun fetchRelease(): AppRelease? {
        val connection = openConnection(
            "https://api.github.com/repos/workeffortwaste/fm1-backup-restore/releases/latest",
        )
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IOException("No published stable GitHub Release is available (HTTP 404).")
            }
            requireSuccess(connection)
            val json = connection.inputStream.bufferedReader().use { reader ->
                // Release notes and metadata should never require an unbounded allocation.
                val text = reader.readTextLimited(1024 * 1024)
                JSONObject(text)
            }
            return parseAppRelease(json, installed.versionName.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadAndVerify(release: AppRelease): File {
        check(updateDirectory.isDirectory || updateDirectory.mkdirs()) { "Cannot create update cache." }
        val apk = File(updateDirectory, "update.apk")
        var complete = false
        try {
            val connection = openConnection(release.apkUrl)
            try {
                requireSuccess(connection)
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastProgress = -1
                connection.inputStream.use { input ->
                    apk.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            downloaded += count
                            require(downloaded <= release.size) { "APK exceeds its declared size." }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val progress = (downloaded * 100 / release.size).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { state = state.copy(progress = progress) }
                            }
                        }
                    }
                }
                require(downloaded == release.size) { "APK download is incomplete." }
                val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                require(MessageDigest.isEqual(digest.digest(), expected)) { "APK checksum does not match." }
                // Check package, version, and signing identity before exposing an install action.
                verifyApk(apk)
                complete = true
                return apk
            } finally {
                connection.disconnect()
            }
        } finally {
            if (!complete && apk.exists() && !apk.delete()) {
                Log.w("AppUpdate", "Could not delete incomplete update APK")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(apk: File?): PackageInfo {
        val application = getApplication<Application>()
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (apk == null) {
            application.packageManager.getPackageInfo(application.packageName, flags)
        } else {
            requireNotNull(application.packageManager.getPackageArchiveInfo(apk.path, flags)) {
                "The downloaded file is not a valid APK."
            }
        }
    }

    private fun verifyApk(apk: File) {
        val release = requireNotNull(state.release) { "Release information is unavailable." }
        verifyUpdateApk(installed, packageInfo(apk), release.version)
    }

    private fun openConnection(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            // Validate every hop; a trusted initial URL must not redirect to an arbitrary host.
            require(url.protocol == "https" && url.host in DOWNLOAD_HOSTS && url.userInfo == null &&
                (url.port == -1 || url.port == 443)) { "Untrusted update URL." }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FM1-Backup-Restore")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Update redirect has no destination.")
                url = URL(url, location)
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            connection.disconnect()
        }
        throw IOException("Too many update redirects.")
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Server returned HTTP ${connection.responseCode}. Try again later.")
        }
    }

    private fun java.io.Reader.readTextLimited(limit: Int): String {
        val buffer = CharArray(4096)
        val result = StringBuilder()
        while (true) {
            val count = read(buffer)
            if (count == -1) return result.toString()
            require(result.length + count <= limit) { "Update metadata is too large." }
            result.append(buffer, 0, count)
        }
    }

    companion object {
        private val DOWNLOAD_HOSTS = setOf(
            "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        )
    }
}
