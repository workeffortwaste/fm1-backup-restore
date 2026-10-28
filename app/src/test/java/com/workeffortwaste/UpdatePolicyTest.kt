package com.workeffortwaste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers update eligibility, stable numeric version ordering, and the daily-check boundary. */
class UpdatePolicyTest {
    @Test fun installationWaitsForAllDeviceWork() {
        assertTrue(BackupState().canInstallAppUpdate)
        assertFalse(BackupState(busy = true).canInstallAppUpdate)
        assertFalse(BackupState(findingBootloader = true).canInstallAppUpdate)
        assertFalse(BackupState(autoConnectPending = true).canInstallAppUpdate)
        for (operation in FlashOperation.entries) {
            assertFalse(BackupState(busy = true, transfer = operation).canInstallAppUpdate)
        }
    }

    @Test fun comparesNumericComponentsRatherThanStrings() {
        assertTrue(ReleaseVersion.parse("v0.10") > ReleaseVersion.parse("0.9"))
        assertTrue(ReleaseVersion.parse("1.0") > ReleaseVersion.parse("0.99.99"))
        assertTrue(ReleaseVersion.parse("0.1.1") > ReleaseVersion.parse("0.1"))
        assertTrue(ReleaseVersion.parse("v0.1.0").compareTo(ReleaseVersion.parse("0.1")) == 0)
        assertTrue(ReleaseVersion.parse("0.1") < ReleaseVersion.parse("0.2"))
    }

    @Test fun rejectsNonStableAndMalformedVersions() {
        for (version in listOf("", "v", "1.0-beta", "1.0+build", "1..0", "-1", "1.a", "999999999999")) {
            assertTrue(version, runCatching { ReleaseVersion.parse(version) }.isFailure)
        }
    }

    @Test fun checksNoMoreThanOncePerDayAtExactBoundary() {
        val lastCheck = 10_000L
        assertFalse(updateCheckDue(lastCheck, lastCheck))
        assertFalse(updateCheckDue(lastCheck + UPDATE_CHECK_INTERVAL_MS - 1, lastCheck))
        assertTrue(updateCheckDue(lastCheck + UPDATE_CHECK_INTERVAL_MS, lastCheck))
        assertTrue(updateCheckDue(lastCheck + UPDATE_CHECK_INTERVAL_MS + 1, lastCheck))
    }

    @Test fun firstLaunchAndClockRollbackAllowChecking() {
        assertTrue(updateCheckDue(10_000, 0))
        assertTrue(updateCheckDue(10_000, -1))
        assertTrue(updateCheckDue(10_000, 20_000))
    }
}
