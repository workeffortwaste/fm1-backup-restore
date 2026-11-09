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

    @Test
    fun recognizedV16CanRecoverButRecognizedV15Cannot() {
        val stockV16 = BootloaderVerificationState(
            sha256 = "8f5228c2d6ea2068838ef4916f946bea877d14b07e6cfca49e41957f4c7b234f",
            matchesStockV16 = true,
        )
        val stockV15 = BootloaderVerificationState(
            sha256 = "d9f43191777656de01c7bed4f9e9ba2e34e94832c5d2ed02c3fc7ab7a6d9bd67",
            matchesStockV15 = true,
        )

        assertTrue(stockV16.hasKnownHash)
        assertTrue(stockV16.canRecover)
        assertFalse(stockV15.canRecover)
    }
}
