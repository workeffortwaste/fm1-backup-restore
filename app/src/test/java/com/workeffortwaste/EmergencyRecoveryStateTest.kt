package com.workeffortwaste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyRecoveryStateTest {
    @Test
    fun onlyCompletedUnknownHashesQualifyForRecovery() {
        val unknown = BootloaderVerificationState(showDialog = true, sha256 = "ab".repeat(32))
        assertTrue(unknown.canRecover)
        assertFalse(unknown.copy(sha256 = "").canRecover)
        assertFalse(unknown.copy(working = true).canRecover)
        assertFalse(unknown.copy(matchesStockV15 = true).canRecover)
        assertFalse(unknown.copy(identity = "m-bootloader 1.2", matchesPublishedRelease = true).canRecover)
        assertTrue(unknown.copy(identity = "m-bootloader 1.2", matchesPublishedRelease = false).canRecover)
    }

    @Test
    fun debugOverrideAllowsKnownHashesButStillRequiresCompletedVerification() {
        val stock = BootloaderVerificationState(sha256 = "ab".repeat(32), matchesStockV15 = true)
        assertTrue(stock.canRecover(debugOverride = true))
        assertFalse(stock.canRecover(debugOverride = false))
        assertFalse(stock.copy(working = true).canRecover(debugOverride = true))
        assertFalse(stock.copy(sha256 = "").canRecover(debugOverride = true))
        assertTrue(stock.copy(matchesStockV15 = false, identity = "custom", matchesPublishedRelease = true)
            .canRecover(debugOverride = true))
    }
}
