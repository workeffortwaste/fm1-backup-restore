package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStateTest {
    @Test fun editorLabelIsPrimaryBackupNameWithMidiFallback() {
        assertEquals("JIANT V10", primaryBackupName("JIANT V10", "FM-1_909"))
        assertEquals("FM-1_909", primaryBackupName(null, "FM-1_909"))
        assertEquals(null, primaryBackupName(null, null))
    }

    @Test fun legacyBackupUpgradeIsOneTimeAndDebugRescansUnknowns() {
        assertTrue(shouldUpgradeLegacyBackupLibrary(isDebugBuild = false, migrationDone = false, hasUnknownBackups = false))
        assertFalse(shouldUpgradeLegacyBackupLibrary(isDebugBuild = false, migrationDone = true, hasUnknownBackups = true))
        assertFalse(shouldUpgradeLegacyBackupLibrary(isDebugBuild = true, migrationDone = true, hasUnknownBackups = false))
        assertTrue(shouldUpgradeLegacyBackupLibrary(isDebugBuild = true, migrationDone = true, hasUnknownBackups = true))
    }

    @Test fun returningToMidiClearsRecoverySessionWithoutSuppressingDiscovery() {
        val disconnected = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            discoveryActive = true,
            ready = true,
            firmwareIdentity = "JIANT 1.0",
            editorFirmwareLabel = "JIANT 1.0",
            hash = "a".repeat(64),
        ).returnToMidi()

        assertTrue(disconnected.discoveryActive)
        assertTrue(disconnected.busy)
        assertFalse(disconnected.ready)
        assertEquals(DeviceMode.SEARCHING, disconnected.deviceMode)
        assertEquals("Returning ??? to MIDI mode", disconnected.status.heading)
        assertEquals(null, disconnected.firmwareIdentity)
        assertEquals(null, disconnected.editorFirmwareLabel)
        assertEquals("", disconnected.hash)
        assertFalse(disconnected.showTransferProgress)
        assertTrue(disconnected.rediscoverAfterDisconnect().discoveryActive)
    }

    @Test fun disconnectClearsSessionAndRestartsDiscovery() {
        val disconnected = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            discoveryActive = true,
            discoveryTimedOut = true,
            firmwareIdentity = "JIANT 1.0",
            editorFirmwareLabel = "JIANT 1.0",
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
        assertEquals(null, disconnected.editorFirmwareLabel)
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

    @Test fun connectedDeviceStatusUsesMidiFamilyAndKeepsUnknownExplicit() {
        assertEquals("??? · MIDI mode", BackupState(deviceMode = DeviceMode.MIDI).deviceStatus)
        assertEquals(
            "Pocket X / Blackbox · PocketX_924 · MIDI mode",
            BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "PocketX_924").deviceStatus,
        )
        assertEquals(
            "??? · MIDI mode",
            BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "JIANT v1.0").deviceStatus,
        )
    }

    @Test fun connectedDeviceStatusPrefersEditorLabelOverMidiIdentity() {
        assertEquals(
            "FM-1 \u00b7 JIANT v1.0 \u00b7 MIDI mode",
            BackupState(
                deviceMode = DeviceMode.MIDI,
                firmwareIdentity = "FM-1_909",
                editorFirmwareLabel = "JIANT v1.0",
            ).deviceStatus,
        )
    }

    @Test fun v15RecoverySnapshotSeparatesDisplayNameTagAndFamily() {
        val metadata = v15RecoveryBackupMetadata()

        assertEquals("FM-1_015 RECOVERY", V15_RECOVERY_BACKUP_NAME)
        assertEquals("FM-1_015", metadata.userFirmwareLabel)
        assertEquals(DeviceFamily.FM1, metadata.userFamily)
    }

    @Test fun manualFamilyStatusUsesShortManualQualifier() {
        val state = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
        ).withManualFamilySelection(DeviceFamily.FM1)

        assertEquals("FM-1 · Manual · Recovery mode", state.deviceStatus)
    }

    @Test fun recoveryClassificationOverridesMidiHintUnlessEvidenceConflicts() {
        val recoveredSmk = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            firmwareIdentity = null,
            deviceClassification = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, true),
        )
        assertEquals("SMK-37 Pro / Elite · Recovery mode", recoveredSmk.deviceStatus)

        val conflict = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "2_1_0_0", 0x856014, false),
        )
        assertEquals("??? · Conflicting device evidence", conflict.deviceStatus)
        val manuallySelectedConflict = conflict.copy(manualFamilySelection = DeviceFamily.FM1)
        assertEquals(DeviceFamily.UNKNOWN, manuallySelectedConflict.effectiveDeviceFamily)
        assertEquals("FM-1 · Conflicting evidence · Backup only", manuallySelectedConflict.deviceStatus)
        assertFalse(conflict.canBackUpWithFamily)
        assertTrue(manuallySelectedConflict.canBackUpWithFamily)
    }

    @Test fun recoveryRequiresAResolvedOrExplicitlySelectedFamilyBeforeBackup() {
        val unknown = BackupState(deviceMode = DeviceMode.DOWNLOAD, ready = true)
        assertTrue(unknown.requiresManualFamilySelection)
        assertFalse(unknown.canBackUpWithFamily)

        val automatic = unknown.copy(
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
        )
        assertFalse(automatic.requiresManualFamilySelection)
        assertTrue(automatic.canBackUpWithFamily)

        val manual = unknown.withManualFamilySelection(DeviceFamily.SMK37)
        assertFalse(manual.requiresManualFamilySelection)
        assertTrue(manual.canBackUpWithFamily)
    }

    @Test fun fm1RecoveryToolsRequireAutomaticFm1EvidenceWhenADeviceIsConnected() {
        val offline = BackupState()
        assertTrue(offline.canOfferFm1RecoverySnapshot)
        assertFalse(offline.isVerifiedFm1RecoverySession)

        val unknownRecovery = BackupState(deviceMode = DeviceMode.DOWNLOAD, ready = true)
        assertFalse(unknownRecovery.canOfferFm1RecoverySnapshot)
        assertFalse(unknownRecovery.isVerifiedFm1RecoverySession)

        val fm1Recovery = unknownRecovery.copy(
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
        )
        assertTrue(fm1Recovery.canOfferFm1RecoverySnapshot)
        assertTrue(fm1Recovery.isVerifiedFm1RecoverySession)

        val pocketX = BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "PocketX_924")
        assertFalse(pocketX.canOfferFm1RecoverySnapshot)

        val manuallySelectedFm1 = unknownRecovery.withManualFamilySelection(DeviceFamily.FM1)
        assertFalse(manuallySelectedFm1.canOfferFm1RecoverySnapshot)
        assertFalse(manuallySelectedFm1.isVerifiedFm1RecoverySession)
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
