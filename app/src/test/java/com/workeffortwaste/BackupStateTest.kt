package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStateTest {
    @Test fun userDisconnectSuppressesDiscoveryUntilARealDetach() {
        val disconnected = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            discoveryActive = true,
            ready = true,
            firmwareIdentity = "JIANT 1.0",
            hash = "a".repeat(64),
        ).disconnectByUser()

        assertTrue(disconnected.discoverySuppressedByUser)
        assertFalse(disconnected.discoveryActive)
        assertFalse(disconnected.ready)
        assertEquals(DeviceMode.SEARCHING, disconnected.deviceMode)
        assertEquals("FM-1 disconnected", disconnected.status.heading)
        assertEquals(null, disconnected.firmwareIdentity)
        assertEquals("", disconnected.hash)
        assertFalse(disconnected.rediscoverAfterDisconnect().discoverySuppressedByUser)
    }

    @Test fun disconnectClearsSessionAndRestartsDiscovery() {
        val disconnected = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            discoveryActive = true,
            discoveryTimedOut = true,
            firmwareIdentity = "JIANT 1.0",
            status = statusMessage("Backup saved", "Verified"),
            hash = "a".repeat(64),
        ).rediscoverAfterDisconnect()
        assertEquals(DeviceMode.SEARCHING, disconnected.deviceMode)
        assertTrue(disconnected.discoveryActive)
        assertFalse(disconnected.discoveryTimedOut)
        assertFalse(disconnected.ready)
        assertTrue(disconnected.status.isEmpty)
        assertEquals("", disconnected.hash)
        assertEquals(null, disconnected.firmwareIdentity)
    }

    @Test fun backupHashOnlyRemainsOnTheCompletedBackupStep() {
        val completed = BackupState(
            ready = true,
            status = statusMessage("Backup saved", "Verified"),
            hash = "a".repeat(64),
        )
        assertEquals(completed, completed.withCurrentBackupHash())
        for (next in listOf(
            completed.copy(ready = false),
            completed.copy(busy = true),
            completed.copy(status = StatusMessage()),
        )) {
            assertEquals("", next.withCurrentBackupHash().hash)
        }
    }

    @Test fun discoveryTimeoutPreservesTransferResultsAndActiveConnections() {
        val result = BackupState(status = statusMessage("Restore failed", "Check the device"))
        assertEquals(result.status, result.afterDiscoveryTimeout().status)
        assertTrue(result.afterDiscoveryTimeout().discoveryTimedOut)
        for (state in listOf(
            result.copy(busy = true),
            result.copy(findingBootloader = true),
            result.copy(deviceMode = DeviceMode.MIDI),
            result.copy(deviceMode = DeviceMode.DOWNLOAD),
        )) {
            assertEquals(state, state.afterDiscoveryTimeout())
        }
    }

    @Test fun onlyActiveFlashTransfersUseTheFullScreenProgress() {
        assertFalse(BackupState(busy = true).showTransferProgress)
        for (operation in FlashOperation.entries) {
            val active = BackupState(busy = true, transfer = operation)
            assertTrue(active.showTransferProgress)
            assertFalse(active.copy(busy = false).showTransferProgress)
            assertFalse(active.copy(transfer = null).showTransferProgress)
        }
    }

    @Test fun pinnedBackupsAreSeparatedFromTheRegularList() {
        val pinned = BackupEntry("pinned", 1L, "0".repeat(64))
        val regular = BackupEntry("regular", 2L, "1".repeat(64))
        val state = BackupState(backups = listOf(pinned, regular), pinnedBackupIds = setOf(pinned.id))
        assertEquals(listOf(pinned), state.pinnedBackups)
        assertEquals(listOf(regular), state.unpinnedBackups)
        assertTrue(BackupState().pinnedBackups.isEmpty())
    }

    @Test fun discoveryRequiresOneUnambiguousDevice() {
        assertEquals(DeviceMode.SEARCHING, discoverDeviceMode(0, 0))
        assertEquals(DeviceMode.MIDI, discoverDeviceMode(1, 0))
        assertEquals(DeviceMode.DOWNLOAD, discoverDeviceMode(0, 1))
        for ((midi, download) in listOf(2 to 0, 0 to 2, 1 to 1)) {
            assertEquals(DeviceMode.MULTIPLE, discoverDeviceMode(midi, download))
        }
    }
}
