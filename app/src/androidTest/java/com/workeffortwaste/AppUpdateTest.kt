package com.workeffortwaste

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises release metadata validation, APK identity checks, and update-cache URI isolation. */
class AppUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun release(): JSONObject = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v0.2")
        .put("body", "Release notes")
        .put("assets", JSONArray().put(
            JSONObject()
                .put("name", "fm1-backup-restore.apk")
                .put("size", 1024)
                .put("digest", "sha256:${"ab".repeat(32)}")
                .put("browser_download_url",
                    "https://github.com/workeffortwaste/fm1-backup-restore/releases/download/v0.2/fm1-backup-restore.apk"),
        ))

    @Test fun parsesExpectedReleaseAndIgnoresUnrelatedAssets() {
        val metadata = release()
        metadata.getJSONArray("assets").put(JSONObject().put("name", "source.zip"))
        val result = requireNotNull(parseAppRelease(metadata, "0.1"))
        assertEquals("v0.2", result.version)
        assertEquals("Release notes", result.notes)
        assertEquals(1024L, result.size)
        assertEquals("ab".repeat(32), result.sha256)
        assertNull(parseAppRelease(metadata, "0.2.0"))
        assertNull(parseAppRelease(metadata, "0.3"))
    }

    @Test fun rejectsDraftsPrereleasesAndAmbiguousAssets() {
        for (field in listOf("draft", "prerelease")) {
            assertRejected { parseAppRelease(release().put(field, true), "0.1") }
        }
        assertRejected { parseAppRelease(release().put("assets", JSONArray()), "0.1") }
        val duplicate = release()
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertRejected { parseAppRelease(duplicate, "0.1") }
    }

    @Test fun reportsNewerReleaseWithoutExpectedApkAsNotReady() {
        val missing = release().put("assets", JSONArray())
        val renamed = release()
        renamed.getJSONArray("assets").getJSONObject(0).put("name", "other.apk")
        for (metadata in listOf(missing, renamed)) {
            val failure = runCatching { parseAppRelease(metadata, "0.1") }.exceptionOrNull()
            assertTrue("Expected update not ready, got $failure", failure is UpdateNotReadyException)
            assertEquals(
                "A newer release is available, but its APK hasn't been uploaded yet. Try again later.",
                failure?.message,
            )
            assertNull(parseAppRelease(metadata, "0.2"))
        }
    }

    @Test fun enforcesDigestOriginAndExactSizeLimit() {
        for (digest in listOf("", "md5:abc", "sha256:bad", "sha256:${"z".repeat(64)}")) {
            val metadata = release()
            metadata.getJSONArray("assets").getJSONObject(0).put("digest", digest)
            assertRejected { parseAppRelease(metadata, "0.1") }
        }
        for (size in listOf(0L, -1L, 100 * 1024 * 1024L + 1)) {
            val metadata = release()
            metadata.getJSONArray("assets").getJSONObject(0).put("size", size)
            assertRejected { parseAppRelease(metadata, "0.1") }
        }
        val largest = release()
        largest.getJSONArray("assets").getJSONObject(0).put("size", 100 * 1024 * 1024L)
        assertNotNull(parseAppRelease(largest, "0.1"))
        for (url in listOf("http://github.com/file.apk", "https://example.com/file.apk",
            "https://github.com/another/project/releases/download/v0.2/file.apk")) {
            val metadata = release()
            metadata.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertRejected { parseAppRelease(metadata, "0.1") }
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String): PackageInfo = context.packageManager.getPackageInfo(
        name,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
    )

    @Suppress("DEPRECATION")
    private fun candidate(): PackageInfo {
        val current = packageInfo(context.packageName)
        return PackageInfo().apply {
            packageName = current.packageName
            versionName = "0.2"
            versionCode = current.versionCode + 1
            if (Build.VERSION.SDK_INT >= 28) signingInfo = current.signingInfo
            else signatures = current.signatures
        }
    }

    @Suppress("DEPRECATION")
    @Test fun verifiesPackageVersionAndSigningIdentity() {
        val current = packageInfo(context.packageName)
        verifyUpdateApk(current, candidate(), "v0.2")
        assertRejected { verifyUpdateApk(current, candidate().apply { packageName = "other.app" }, "v0.2") }
        assertRejected { verifyUpdateApk(current, current, "v0.2") }
        assertRejected { verifyUpdateApk(current, candidate(), "v0.3") }
        val unsigned = candidate().apply {
            if (Build.VERSION.SDK_INT >= 28) signingInfo = null else signatures = null
        }
        assertRejected { verifyUpdateApk(current, unsigned, "v0.2") }
        val differentSigner = candidate().apply {
            val platform = packageInfo("android")
            if (Build.VERSION.SDK_INT >= 28) signingInfo = platform.signingInfo else signatures = platform.signatures
        }
        assertRejected { verifyUpdateApk(current, differentSigner, "v0.2") }
    }

    @Test fun fileProviderExposesOnlyUpdateCache() {
        val file = File(context.cacheDir, "updates/update.apk")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.updates", uri.authority)
        assertRejected {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "backup.bin"))
        }
    }

    private fun assertRejected(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected validation failure, got $failure", failure is IllegalArgumentException)
    }
}
