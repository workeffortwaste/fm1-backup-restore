package com.workeffortwaste

internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Rechecks after a day, on first launch, or if the device clock moved backwards. */
internal fun updateCheckDue(now: Long, lastCheck: Long): Boolean =
    lastCheck <= 0 || lastCheck > now || now - lastCheck >= UPDATE_CHECK_INTERVAL_MS

/**
 * Explains why this install can never receive a release update, or null if checks are allowed.
 * Debuggable installs use a different application ID, signing key and version suffix, so a signed
 * release APK could never pass installation checks; the GitHub request is skipped entirely.
 */
internal fun updatesUnavailableReason(debuggable: Boolean, versionName: String): String? = when {
    debuggable -> "This is a debug build, so it doesn't check for or install updates. " +
        "Install the release APK from GitHub to receive updates."
    runCatching { ReleaseVersion.parse(versionName) }.isFailure ->
        "This build's version ($versionName) isn't a release version, so updates can't be checked."
    else -> null
}

/** Updates wait until discovery and any active device operation are finished. */
internal val BackupState.canInstallAppUpdate: Boolean
    get() = !busy && !findingBootloader && !autoConnectPending

internal data class ReleaseVersion(private val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        // Missing trailing components compare as zero, so 1.2 and 1.2.0 are equivalent.
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val comparison = (parts.getOrElse(index) { 0 }).compareTo(other.parts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String): ReleaseVersion {
            val normalized = value.removePrefix("v")
            require(normalized.matches(Regex("[0-9]+(\\.[0-9]+)*"))) {
                "Unsupported release version: $value. Use a numeric version such as v0.2.0."
            }
            return ReleaseVersion(normalized.split('.').map {
                requireNotNull(it.toIntOrNull()) { "Release version component is too large." }
            })
        }
    }
}
