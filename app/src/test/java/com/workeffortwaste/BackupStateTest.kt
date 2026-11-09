package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStateTest {
    @Test fun legacyBackupUpgradeIsOneTimeAndDebugRescansUnknowns() {
        assertTrue(shouldUpgradeLegacyBackupLibrary(isDebugBuild = false, migrationDone = false, hasUnknownBackups = false))
        assertFalse(shouldUpgradeLegacyBackupLibrary(isDebugBuild = false, migrationDone = true, hasUnknownBackups = true))
        assertFalse(shouldUpgradeLegacyBackupLibrary(isDebugBuild = true, migrationDone = true, hasUnknownBackups = false))
        assertTrue(shouldUpgradeLegacyBackupLibrary(isDebugBuild = true, migrationDone = true, hasUnknownBackups = true))
    }

    @Test fun allDeviceModelsShareTheSameRecoverySelectionRoutes() {
        val identities = mapOf(
            DeviceFamily.FM1 to "FM-1_015",
            DeviceFamily.MINI_FM to "MiniFM_015",
            DeviceFamily.SMK37_PRO to "SMK-37 Pro_016",
            DeviceFamily.SMK37_ELITE to "SMK-37 Elite_016",
            DeviceFamily.STARRYKEY37_PLAY to "STARRYKEY-37 PLAY_016",
            DeviceFamily.MKE_P37 to "MKE-P37_015",
            DeviceFamily.POCKET_X to "PocketX_009",
            DeviceFamily.BLACKBOX to "BlackBox_021",
            DeviceFamily.POCKET_AMP to "Pocket AMP_105",
        )
        assertEquals(DeviceFamily.entries.filter { it != DeviceFamily.UNKNOWN }.toSet(), identities.keys)
        identities.forEach { (family, identity) ->
            val spi = if (family == DeviceFamily.POCKET_X) "2_1_0_0" else "4_1_0_0"
            val reset = if (family == DeviceFamily.BLACKBOX || family == DeviceFamily.POCKET_AMP) {
                "PB01_00_0"
            } else "PB01_08_0"
            val classification = classifyDeviceFamily(identity, spi, 0x856014, family.isSmk37Family, reset)
            val automatic = BackupState(
                deviceMode = DeviceMode.DOWNLOAD,
                ready = true,
                firmwareIdentity = identity,
                deviceClassification = classification,
            )
            assertEquals(family, automatic.effectiveDeviceFamily)
            assertFalse(automatic.canSelectDeviceManually)
            assertFalse(automatic.requiresManualFamilySelection)
            assertTrue(automatic.canBackUpWithFamily)

            val direct = automatic.copy(recoverySelectionRequired = true, firmwareIdentity = null)
            assertTrue(direct.canSelectDeviceManually)
            assertTrue(direct.requiresManualFamilySelection)
            assertFalse(direct.canBackUpWithFamily)
            assertEquals(DeviceFamily.UNKNOWN, direct.effectiveDeviceFamily)

            val selected = direct.withManualFamilySelection(family)
            assertTrue(selected.canSelectDeviceManually)
            assertFalse(selected.requiresManualFamilySelection)
            assertTrue(selected.canBackUpWithFamily)
            assertEquals(family, selected.effectiveDeviceFamily)
            assertEquals("${family.displayName} · Recovery mode", selected.deviceStatus)
            assertEquals(
                automatic.copy(firmwareIdentity = null).deviceStatus,
                selected.deviceStatus,
            )
            assertEquals("${family.displayName} successfully connected", selected.status.heading)
            assertEquals("Ready to backup or restore full flash.", selected.status.secondary)
            assertEquals(classification.rawMidiIdentity, selected.deviceClassification.rawMidiIdentity)
            assertEquals(classification.spiSetting, selected.deviceClassification.spiSetting)
            assertFalse(selected.rediscoverAfterDisconnect().canSelectDeviceManually)
            assertFalse(selected.returnToMidi().canSelectDeviceManually)
            assertFalse(selected.copy(ready = false).canSelectDeviceManually)
        }
    }

    @Test fun conflictingRecoveryShowsBlockedStatusInsteadOfSuccess() {
        val classification = classifyDeviceFamily("FM-1_015", "2_1_0_0", 0x856014, false)
        val message = recoveryConnectionStatus(classification, selectionRequired = false)
        assertEquals("Device identity could not be confirmed", message.heading)
        assertEquals(
            "Recovery checks disagree with the MIDI identity. Backup and restore are blocked.",
            message.secondary,
        )
        val state = BackupState(
            deviceMode = DeviceMode.DOWNLOAD, ready = true,
            firmwareIdentity = "FM-1_015", deviceClassification = classification, status = message,
        )
        assertEquals("Device", state.connectedDeviceDisplayName)
        assertEquals("Conflicting evidence", state.deviceStatus)
        assertFalse(state.canBackUpWithFamily)
        assertFalse(state.canSelectDeviceManually)
        assertFalse(state.requiresManualFamilySelection)
        assertEquals("Returning Device to MIDI mode", state.returnToMidi().status.heading)
        assertTrue(state.rediscoverAfterDisconnect().status.isEmpty)
    }

    @Test fun recoveryStatusDistinguishesSelectionUnknownAndConfirmedIdentity() {
        val classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false, "PB01_08_0")
        val confirmed = recoveryConnectionStatus(classification, selectionRequired = false)
        assertEquals("FM-1 successfully connected", confirmed.heading)
        assertEquals("Ready to backup or restore full flash.", confirmed.secondary)
        val manual = recoveryConnectionStatus(classification, selectionRequired = true)
        assertEquals("Device connected in recovery mode", manual.heading)
        assertEquals("Select the connected device before backing up or restoring.", manual.secondary)
        val unknown = recoveryConnectionStatus(DeviceClassification(), selectionRequired = false)
        assertEquals("Device identity could not be confirmed", unknown.heading)
        assertEquals("The connected device model is unknown. Backup and restore are blocked.", unknown.secondary)
    }

    @Test fun editorLabelIsPrimaryBackupNameWithMidiFallback() {
        assertEquals("JIANT V10", primaryBackupName("JIANT V10", "FM-1_909"))
        assertEquals(
            "Pocket_AMP_Onyx_1.01-2611051015",
            primaryBackupName("Pocket_AMP_Onyx_1.01-2611051015", "Pocket AMP_105"),
        )
        assertEquals("FM-1_909", primaryBackupName(null, "FM-1_909"))
        assertEquals(null, primaryBackupName(null, null))
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
        assertEquals("Returning Device to MIDI mode", disconnected.status.heading)
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
        assertEquals("Device · MIDI mode", BackupState(deviceMode = DeviceMode.MIDI).deviceStatus)
        assertEquals(
            "Pocket X · PocketX_924 · MIDI mode",
            BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "PocketX_924").deviceStatus,
        )
        assertEquals(
            "BlackBox · BlackBox_924 · MIDI mode",
            BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "BlackBox_924").deviceStatus,
        )
        assertEquals(
            "Device · MIDI mode",
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

        assertEquals("FM-1 · Recovery mode", state.deviceStatus)
    }

    @Test fun recoveryClassificationOverridesMidiHintUnlessEvidenceConflicts() {
        val recoveredSmk = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            recoverySelectionRequired = true,
            firmwareIdentity = null,
            deviceClassification = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, true),
        )
        assertEquals("Device · Recovery mode", recoveredSmk.deviceStatus)
        assertEquals(
            "SMK-37 Pro · Recovery mode",
            recoveredSmk.withManualFamilySelection(DeviceFamily.SMK37_PRO).deviceStatus,
        )

        val conflict = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            recoverySelectionRequired = true,
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "2_1_0_0", 0x856014, false),
        )
        assertEquals("Conflicting evidence", conflict.deviceStatus)
        val manuallySelectedConflict = conflict.withManualFamilySelection(DeviceFamily.FM1)
        assertEquals(DeviceFamily.FM1, manuallySelectedConflict.effectiveDeviceFamily)
        assertEquals(FamilyEvidenceOrigin.MANUAL, manuallySelectedConflict.deviceClassification.origin)
        assertEquals("FM-1 · FM-1_015 · Recovery mode", manuallySelectedConflict.deviceStatus)
        assertFalse(conflict.canBackUpWithFamily)
        assertTrue(manuallySelectedConflict.canBackUpWithFamily)
    }

    @Test fun recoveryRequiresAResolvedOrExplicitlySelectedFamilyBeforeBackup() {
        val unknown = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            recoverySelectionRequired = true,
        )
        assertTrue(unknown.requiresManualFamilySelection)
        assertFalse(unknown.canBackUpWithFamily)

        val automatic = unknown.copy(
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
        )
        assertTrue(automatic.requiresManualFamilySelection)
        assertFalse(automatic.canBackUpWithFamily)
        assertEquals("Device", automatic.connectedDeviceDisplayName)

        val selected = automatic.withManualFamilySelection(DeviceFamily.FM1)
        assertFalse(selected.requiresManualFamilySelection)
        assertTrue(selected.canBackUpWithFamily)
        assertEquals(DeviceFamily.FM1, selected.effectiveDeviceFamily)
        assertEquals(FamilyEvidenceOrigin.MANUAL, selected.deviceClassification.origin)

        val mismatch = automatic.withManualFamilySelection(DeviceFamily.BLACKBOX)
        assertEquals(FamilyEvidenceOrigin.MANUAL, mismatch.deviceClassification.origin)
        assertEquals(DeviceFamily.BLACKBOX, mismatch.effectiveDeviceFamily)
        assertTrue(mismatch.canBackUpWithFamily)

        val manual = unknown.withManualFamilySelection(DeviceFamily.SMK37_PRO)
        assertFalse(manual.requiresManualFamilySelection)
        assertTrue(manual.canBackUpWithFamily)
    }

    @Test fun fm1RecoveryToolsUseMidiEvidenceOrDirectRecoverySelection() {
        val offline = BackupState()
        assertTrue(offline.canOfferFm1RecoverySnapshot)
        assertFalse(offline.isVerifiedFm1RecoverySession)

        val unknownRecovery = BackupState(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            recoverySelectionRequired = true,
        )
        assertFalse(unknownRecovery.canOfferFm1RecoverySnapshot)
        assertFalse(unknownRecovery.isVerifiedFm1RecoverySession)

        val fm1Recovery = unknownRecovery.copy(
            recoverySelectionRequired = false,
            firmwareIdentity = "FM-1_015",
            deviceClassification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
        )
        assertFalse(fm1Recovery.requiresManualFamilySelection)
        assertTrue(fm1Recovery.canBackUpWithFamily)
        assertTrue(fm1Recovery.canOfferFm1RecoverySnapshot)
        assertTrue(fm1Recovery.isVerifiedFm1RecoverySession)

        val pocketX = BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "PocketX_924")
        assertFalse(pocketX.canOfferFm1RecoverySnapshot)

        val manuallySelectedFm1 = unknownRecovery.withManualFamilySelection(DeviceFamily.FM1)
        assertTrue(manuallySelectedFm1.canOfferFm1RecoverySnapshot)
        assertTrue(manuallySelectedFm1.isVerifiedFm1RecoverySession)
    }

    @Test fun pocketAmpStateUsesItsOwnFamilyAndNeverOffersFm1RecoveryTools() {
        val midi = BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = "Pocket AMP_105")
        assertEquals(DeviceFamily.POCKET_AMP, midi.midiFamilyHint)
        assertEquals("Pocket AMP", midi.connectedDeviceDisplayName)
        assertFalse(midi.canOfferFm1RecoverySnapshot)
        val detectedRecovery = midi.copy(
            deviceMode = DeviceMode.DOWNLOAD,
            ready = true,
            deviceClassification = classifyDeviceFamily(
                "Pocket AMP_105", "4_1_0_0", 0x856014, false, "PB01_00_0",
            ),
        )
        assertTrue(detectedRecovery.canBackUpWithFamily)
        assertFalse(detectedRecovery.requiresManualFamilySelection)
        val recovery = detectedRecovery
        assertTrue(recovery.canBackUpWithFamily)
        assertFalse(recovery.requiresManualFamilySelection)
        assertFalse(recovery.isVerifiedFm1RecoverySession)
        assertFalse(recovery.canOfferFm1RecoverySnapshot)
        assertEquals("Pocket AMP · Pocket AMP_105 · Recovery mode", recovery.deviceStatus)
        val conflict = recovery.copy(
            deviceClassification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false, "PB01_00_0"),
        ).withManualFamilySelection(DeviceFamily.POCKET_AMP)
        assertEquals(FamilyEvidenceOrigin.MANUAL, conflict.deviceClassification.origin)
        assertEquals(DeviceFamily.POCKET_AMP, conflict.effectiveDeviceFamily)
        assertEquals(DeviceClassification(), recovery.rediscoverAfterDisconnect().deviceClassification)
        val fm1 = recovery.copy(
            manualFamilySelection = DeviceFamily.FM1,
            deviceClassification = classifyDeviceFamily(null, "4_1_0_0", 0x856014, false, "PB01_08_0"),
        )
        assertTrue(fm1.isVerifiedFm1RecoverySession)
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

    @Test fun keyboardModelsAreRetainedFromMidiAndSelectableInDirectRecovery() {
        val identities = listOf(
            "SMK-37 Pro_016", "SMK-37 Elite_016", "STARRYKEY-37 PLAY_016", "MKE-P37_015",
        )
        for (identity in identities) {
            val family = DeviceFamily.fromMidiIdentity(identity)
            val midi = BackupState(deviceMode = DeviceMode.MIDI, firmwareIdentity = identity)
            assertEquals(family, midi.effectiveDeviceFamily)
            val viaMidi = midi.copy(
                deviceMode = DeviceMode.DOWNLOAD,
                ready = true,
                recoverySelectionRequired = false,
                deviceClassification = classifyDeviceFamily(identity, "4_1_0_0", 0xEB6014, true, "PB01_08_0"),
            )
            assertFalse(viaMidi.requiresManualFamilySelection)
            assertEquals(family, viaMidi.effectiveDeviceFamily)
            assertTrue(viaMidi.canBackUpWithFamily)
            val direct = BackupState(
                deviceMode = DeviceMode.DOWNLOAD, ready = true, recoverySelectionRequired = true,
                deviceClassification = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, true, "PB01_08_0"),
            )
            assertTrue(direct.requiresManualFamilySelection)
            val selected = direct.withManualFamilySelection(family)
            assertEquals(family, selected.effectiveDeviceFamily)
            assertEquals(family.displayName, selected.connectedDeviceDisplayName)
            assertTrue(selected.canBackUpWithFamily)
            assertFalse(selected.requiresManualFamilySelection)
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
