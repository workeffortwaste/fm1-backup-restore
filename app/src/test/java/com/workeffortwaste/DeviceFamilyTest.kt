package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFamilyTest {
    @Test
    fun mapsSupportedMidiPrefixesWithoutRequiringVersionSuffixes() {
        assertEquals(DeviceFamily.FM1, DeviceFamily.fromMidiIdentity("FM-1_015"))
        assertEquals(DeviceFamily.MINI_FM, DeviceFamily.fromMidiIdentity("MiniFM_015"))
        assertEquals(DeviceFamily.MINI_FM, DeviceFamily.fromMidiIdentity(" minifm "))
        assertEquals(DeviceFamily.MINI_FM, DeviceFamily.fromMidiIdentity("MiniFM-new-version"))
        assertEquals("MiniFM", DeviceFamily.MINI_FM.displayName)
        assertEquals("MiniFM", DeviceFamily.MINI_FM.backupTagLabel)
        assertEquals(DeviceFamily.SMK37_PRO, DeviceFamily.fromMidiIdentity("SMK-37 Pro_016"))
        assertEquals(DeviceFamily.SMK37_ELITE, DeviceFamily.fromMidiIdentity("SMK-37 Elite_016"))
        assertEquals(DeviceFamily.STARRYKEY37_PLAY, DeviceFamily.fromMidiIdentity("STARRYKEY-37 PLAY_016"))
        assertEquals(DeviceFamily.MKE_P37, DeviceFamily.fromMidiIdentity("MKE-P37_015"))
        assertEquals(DeviceFamily.POCKET_X, DeviceFamily.fromMidiIdentity("PocketX_924"))
        assertEquals(DeviceFamily.BLACKBOX, DeviceFamily.fromMidiIdentity("BlackBox_9xx"))
        assertEquals("PX", DeviceFamily.POCKET_X.backupTagLabel)
        assertEquals("Pocket X", DeviceFamily.POCKET_X.metadataName)
        assertEquals("BB", DeviceFamily.BLACKBOX.backupTagLabel)
        assertEquals("BlackBox", DeviceFamily.BLACKBOX.metadataName)
        assertEquals(DeviceFamily.POCKET_AMP, DeviceFamily.fromMidiIdentity("Pocket AMP_105"))
        assertEquals(DeviceFamily.POCKET_AMP, DeviceFamily.fromMidiIdentity(" pocket amp_194 "))
        assertEquals(DeviceFamily.SMK37_ELITE, DeviceFamily.fromMidiIdentity("smk-37 elite"))
        assertEquals("PA", DeviceFamily.POCKET_AMP.backupTagLabel)
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("Pocket AMP"))
    }

    @Test
    fun editorLabelsAndUnknownValuesDoNotClassifyAsDevices() {
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity(null))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("JIANT v1.0"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("FELUCCA 1.0"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("FM1_OFFICIAL_OPEN"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("SMK-37"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("SMK-37+VA_102"))
    }

    @Test
    fun recoveryClassifierUsesKnownPairsAndKeepsAmbiguousEvidenceUnresolved() {
        val fm1 = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false, "PB01_08_0")
        assertEquals(DeviceFamily.FM1, fm1.family)
        assertEquals(FamilyEvidenceOrigin.RECOVERY_BOOT_CONFIG, fm1.origin)

        val blackBoxPackageConfig =
            classifyDeviceFamily("BlackBox_924", "4_1_0_0", 0x856014, false, "PB01_00_0")
        assertEquals(DeviceFamily.BLACKBOX, blackBoxPackageConfig.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY, blackBoxPackageConfig.origin)

        val blackBoxFm1Reset =
            classifyDeviceFamily("BlackBox_924", "4_1_0_0", 0x123456, false, "PB01_08_0")
        assertEquals(DeviceFamily.UNKNOWN, blackBoxFm1Reset.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, blackBoxFm1Reset.origin)

        val blackBoxMidi = classifyDeviceFamily("BlackBox_924", "4_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.BLACKBOX, blackBoxMidi.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY, blackBoxMidi.origin)

        val pocketXIdentityOnBlackBox =
            classifyDeviceFamily("PocketX_009", "4_1_0_0", 0x856014, false, "PB01_00_0")
        assertEquals(DeviceFamily.UNKNOWN, pocketXIdentityOnBlackBox.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, pocketXIdentityOnBlackBox.origin)
        assertEquals(
            DeviceFamily.POCKET_X,
            classifyDeviceFamily("PocketX_009", "2_1_0_0", 0x856014, false).family,
        )
        val pocketXWithPocketAmpReset =
            classifyDeviceFamily("PocketX_924", "2_1_0_0", 0x856014, false, "PB01_00_0")
        assertEquals(DeviceFamily.POCKET_X, pocketXWithPocketAmpReset.family)

        val unknownMidi = classifyDeviceFamily(null, "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.UNKNOWN, unknownMidi.family)
        assertEquals(DeviceFamily.POCKET_X, unknownMidi.suggestedFamily)
        assertEquals(FamilyEvidenceConfidence.MEDIUM, unknownMidi.confidence)
        assertEquals(FamilyEvidenceOrigin.UNKNOWN, unknownMidi.origin)

        val smkSuggestion = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, false)
        assertEquals(DeviceFamily.UNKNOWN, smkSuggestion.family)
        assertEquals(null, smkSuggestion.suggestedFamily)

        val unfamiliarPair = classifyDeviceFamily(null, "2_1_0_0", 0xB36014, false)
        assertEquals(DeviceFamily.UNKNOWN, unfamiliarPair.family)
        assertEquals(null, unfamiliarPair.suggestedFamily)
        assertEquals(FamilyEvidenceConfidence.NONE, unfamiliarPair.confidence)

        for (fm1Jedec in listOf(0x856014L, 0xB36014L)) {
            val recoveredFm1 = classifyDeviceFamily(null, "4_1_0_0", fm1Jedec, false, "PB01_08_0")
            assertEquals(DeviceFamily.FM1, recoveredFm1.family)
            assertEquals(FamilyEvidenceOrigin.RECOVERY_BOOT_CONFIG, recoveredFm1.origin)
            assertEquals(FamilyEvidenceConfidence.HIGH, recoveredFm1.confidence)
            assertEquals(fm1Jedec, recoveredFm1.jedecId)
        }

        val smkMidiConflict = classifyDeviceFamily("SMK-37 Pro_016", "4_1_0_0", 0x856014, false, "PB01_08_0")
        assertEquals(FamilyEvidenceOrigin.CONFLICT, smkMidiConflict.origin)
    }

    @Test
    fun recoveryClassifierHandlesPositiveTrimMissingEvidenceAndConflicts() {
        val smk = classifyDeviceFamily(null, "4_1_0_0", 0x856014, true, "PB01_08_0")
        assertEquals(DeviceFamily.UNKNOWN, smk.family)
        assertEquals(FamilyEvidenceOrigin.RECOVERY_MARKER, smk.origin)
        assertEquals(FamilyEvidenceConfidence.LOW, smk.confidence)
        assertEquals(
            DeviceFamily.UNKNOWN,
            classifyDeviceFamily(null, "4_1_0_0", 0x856014, true, "PB01_00_0").family,
        )

        val smkHint = classifyDeviceFamily("SMK-37 Elite", null, 0xEB6014, false)
        assertEquals(DeviceFamily.SMK37_ELITE, smkHint.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_HINT, smkHint.origin)
        assertEquals(FamilyEvidenceConfidence.LOW, smkHint.confidence)

        val conflict = classifyDeviceFamily("FM-1_015", "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.UNKNOWN, conflict.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, conflict.origin)

        val trimConflict = classifyDeviceFamily("BlackBox_924", "2_1_0_0", 0x856014, true)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, trimConflict.origin)

        val blackBoxTrimConflict =
            classifyDeviceFamily("BlackBox_924", "4_1_0_0", 0x856014, true, "PB01_00_0")
        assertEquals(DeviceFamily.UNKNOWN, blackBoxTrimConflict.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, blackBoxTrimConflict.origin)

        val blackBoxWrongSpi = classifyDeviceFamily("BlackBox_924", "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.UNKNOWN, blackBoxWrongSpi.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, blackBoxWrongSpi.origin)

        keyboardModels.forEach { (family, identity) ->
            val smk = classifyDeviceFamily(identity, "4_1_0_0", 0xEB6014, true, "PB01_08_0")
            assertEquals(identity, family, smk.family)
            assertEquals(FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY, smk.origin)
        }
    }

    @Test
    fun compatibilityTreatsUnknownAsUnresolvedAndKeepsUnrelatedFamiliesSeparate() {
        assertEquals(BackupCompatibility.COMPATIBLE, compatibility(DeviceFamily.FM1, DeviceFamily.FM1))
        assertEquals(
            BackupCompatibility.INCOMPATIBLE,
            compatibility(DeviceFamily.FM1, DeviceFamily.SMK37_PRO),
        )
        assertEquals(
            BackupCompatibility.INCOMPATIBLE,
            compatibility(DeviceFamily.POCKET_X, DeviceFamily.BLACKBOX),
        )
        assertEquals(BackupCompatibility.UNKNOWN, compatibility(DeviceFamily.UNKNOWN, DeviceFamily.FM1))
        assertEquals(BackupCompatibility.UNKNOWN, compatibility(DeviceFamily.FM1, DeviceFamily.UNKNOWN))
    }

    @Test
    fun fm1VariantsAreInterchangeableButNotCompatibleWithUnrelatedModels() {
        val variants = listOf(DeviceFamily.FM1, DeviceFamily.MINI_FM)
        for (variant in variants) {
            for (family in DeviceFamily.entries) {
                val expected = when {
                    family == DeviceFamily.UNKNOWN -> BackupCompatibility.UNKNOWN
                    family in variants -> BackupCompatibility.COMPATIBLE
                    else -> BackupCompatibility.INCOMPATIBLE
                }
                assertEquals(expected, compatibility(variant, family))
                assertEquals(expected, compatibility(family, variant))
            }
        }
    }

    @Test
    fun miniFmIdentitySurvivesRecoveryAndImageClassification() {
        for (jedec in listOf(0x856014L, 0xB36014L, 0x123456L, null)) {
            for (reset in listOf("PB01_08_0", null)) {
                val recovery = classifyDeviceFamily("MiniFM_015", "4_1_0_0", jedec, false, reset)
                assertEquals(DeviceFamily.MINI_FM, recovery.family)
                assertEquals(FamilyEvidenceConfidence.HIGH, recovery.confidence)
                assertEquals("MiniFM_015", recovery.rawMidiIdentity)
            }
        }
        val hint = classifyDeviceFamily("MiniFM_015", null, null, false)
        assertEquals(DeviceFamily.MINI_FM, hint.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_HINT, hint.origin)
        for (reset in listOf("PB01_08_0", null)) {
            val imported = classifyImportedImage("MiniFM_015", "4_1_0_0", false, reset)
            assertEquals(DeviceFamily.MINI_FM, imported.family)
            assertEquals(FamilyEvidenceOrigin.IMPORTED_IMAGE_SCAN, imported.origin)
        }
        for (conflict in listOf(
            classifyDeviceFamily("MiniFM_015", "2_1_0_0", 0x856014, false),
            classifyDeviceFamily("MiniFM_015", "4_1_0_0", 0x856014, false, "PB01_00_0"),
            classifyDeviceFamily("MiniFM_015", "4_1_0_0", 0x856014, true),
            classifyImportedImage("MiniFM_015", "2_1_0_0", false),
            classifyImportedImage("MiniFM_015", "4_1_0_0", false, "PB01_00_0"),
            classifyImportedImage("MiniFM_015", "4_1_0_0", true),
        )) {
            assertEquals(DeviceFamily.UNKNOWN, conflict.family)
            assertEquals(FamilyEvidenceOrigin.CONFLICT, conflict.origin)
        }
    }

    @Test
    fun importedImagesUseSpiAndTrimEvidenceWithoutOverridingMidiConflict() {
        assertEquals(DeviceFamily.FM1, classifyImportedImage(null, "4_1_0_0", false, "PB01_08_0").family)
        assertEquals(DeviceFamily.UNKNOWN, classifyImportedImage(null, "4_1_0_0", false).family)
        assertEquals(DeviceFamily.UNKNOWN, classifyImportedImage(null, "4_1_0_0", false, "PB01_00_0").family)
        assertEquals(
            DeviceFamily.POCKET_AMP,
            classifyImportedImage("Pocket AMP_105", "4_1_0_0", false, "PB01_00_0").family,
        )
        val pocketAmpNoReset = classifyImportedImage("Pocket AMP_105", "4_1_0_0", false)
        assertEquals(DeviceFamily.POCKET_AMP, pocketAmpNoReset.family)
        assertEquals(false, pocketAmpNoReset.explanation.contains("null"))
        assertEquals(
            FamilyEvidenceOrigin.CONFLICT,
            classifyImportedImage("Pocket AMP_105", "4_1_0_0", false, "PB01_08_0").origin,
        )
        assertEquals(
            FamilyEvidenceOrigin.CONFLICT,
            classifyImportedImage("BlackBox_924", "4_1_0_0", false, "PB01_08_0").origin,
        )
        val blackBoxNoReset = classifyImportedImage("BlackBox_924", "4_1_0_0", false)
        assertEquals(DeviceFamily.BLACKBOX, blackBoxNoReset.family)
        assertEquals(false, blackBoxNoReset.explanation.contains("null"))
        assertEquals(DeviceFamily.POCKET_X, classifyImportedImage(null, "2_1_0_0", false).family)
        assertEquals(
            DeviceFamily.BLACKBOX,
            classifyImportedImage("BlackBox_924", "4_1_0_0", false, "PB01_00_0").family,
        )
        val conflictingPocketX = classifyImportedImage("PocketX_009", "4_1_0_0", false, "PB01_00_0")
        assertEquals(DeviceFamily.UNKNOWN, conflictingPocketX.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, conflictingPocketX.origin)
        assertEquals(DeviceFamily.POCKET_X, classifyImportedImage("PocketX_009", "2_1_0_0", false).family)
        assertEquals(DeviceFamily.UNKNOWN, classifyImportedImage(null, "4_1_0_0", true).family)
        assertEquals(FamilyEvidenceConfidence.NONE, classifyImportedImage(null, null, true).confidence)
        assertEquals(
            FamilyEvidenceOrigin.CONFLICT,
            classifyImportedImage("FM-1_909", "2_1_0_0", false).origin,
        )
        assertEquals(DeviceFamily.UNKNOWN, classifyImportedImage(null, null, false).family)
    }

    @Test
    fun resetDistinguishesModelPairsWithoutCallingSharedBlackboxPairPocketAmp() {
        val pocket = classifyDeviceFamily(null, "4_1_0_0", 0x856014, false, "PB01_00_0")
        assertEquals(DeviceFamily.UNKNOWN, pocket.family)
        assertEquals(FamilyEvidenceOrigin.UNKNOWN, pocket.origin)
        assertEquals(FamilyEvidenceConfidence.NONE, pocket.confidence)
        assertEquals("PB01_00_0", pocket.resetSetting)
        val midiPocketAmp =
            classifyDeviceFamily("Pocket AMP_105", "4_1_0_0", 0x856014, false, "PB01_00_0")
        assertEquals(DeviceFamily.POCKET_AMP, midiPocketAmp.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY, midiPocketAmp.origin)
        for (reset in listOf(null, "invalid", "PB01_09_0")) {
            for (jedec in listOf(0x856014L, 0xB36014L)) {
                assertEquals(DeviceFamily.UNKNOWN, classifyDeviceFamily(null, "4_1_0_0", jedec, false, reset).family)
            }
        }
        for (spi in listOf(null, "2_1_0_0", "invalid")) {
            assertEquals(DeviceFamily.UNKNOWN, classifyDeviceFamily(null, spi, 0x856014, false, "PB01_00_0").family)
        }
        assertEquals(DeviceFamily.UNKNOWN, classifyDeviceFamily(null, "4_1_0_0", 0xB36014, false, "PB01_00_0").family)
        assertEquals(
            FamilyEvidenceOrigin.MIDI_HINT,
            classifyDeviceFamily("Pocket AMP_105", null, null, false).origin,
        )
        assertEquals(
            FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY,
            classifyDeviceFamily("Pocket AMP_105", "4_1_0_0", 0x856014, false).origin,
        )
    }

    @Test
    fun pocketAmpContradictionsBlockClassificationAndCrossFamilyCompatibility() {
        for (evidence in listOf(
            classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false, "PB01_00_0"),
            classifyDeviceFamily("Pocket AMP_105", "4_1_0_0", 0x856014, false, "PB01_08_0"),
            classifyDeviceFamily("Pocket AMP_105", "2_1_0_0", 0x856014, false),
            classifyDeviceFamily("Pocket AMP_105", null, null, true),
            classifyImportedImage("FM-1_015", "4_1_0_0", false, "PB01_00_0"),
        )) {
            assertEquals(DeviceFamily.UNKNOWN, evidence.family)
            assertEquals(FamilyEvidenceOrigin.CONFLICT, evidence.origin)
        }
        assertEquals(
            DeviceFamily.UNKNOWN,
            classifyImportedImage(null, "4_1_0_0", true, "PB01_00_0").family,
        )
        for (family in DeviceFamily.entries) {
            val expected = when (family) {
                DeviceFamily.POCKET_AMP -> BackupCompatibility.COMPATIBLE
                DeviceFamily.UNKNOWN -> BackupCompatibility.UNKNOWN
                else -> BackupCompatibility.INCOMPATIBLE
            }
            assertEquals(expected, compatibility(DeviceFamily.POCKET_AMP, family))
            assertEquals(expected, compatibility(family, DeviceFamily.POCKET_AMP))
        }
    }

    @Test
    fun keyboardModelsHaveDistinctTagsAndAreNotCrossRestoreCompatible() {
        assertEquals(
            listOf("SMK-P", "SMK-E", "SK37", "MKE37"),
            keyboardModels.keys.map { it.backupTagLabel },
        )
        for (source in keyboardModels.keys) {
            for (target in keyboardModels.keys) {
                assertEquals(
                    if (source == target) BackupCompatibility.COMPATIBLE else BackupCompatibility.INCOMPATIBLE,
                    compatibility(source, target),
                )
            }
        }
    }

    @Test
    fun keyboardModelIdentitySurvivesRecoveryAndImportWithoutGuessingFromSharedTrim() {
        for ((family, identity) in keyboardModels) {
            assertEquals(family, DeviceFamily.fromMidiIdentity(identity))
            for (trim in listOf(false, true)) {
                val recovery = classifyDeviceFamily(identity, "4_1_0_0", 0xEB6014, trim, "PB01_08_0")
                assertEquals(family, recovery.family)
                assertEquals(identity, recovery.rawMidiIdentity)
                val imported = classifyImportedImage(identity, "4_1_0_0", trim, "PB01_08_0")
                assertEquals(family, imported.family)
            }
            assertEquals(
                FamilyEvidenceOrigin.CONFLICT,
                classifyDeviceFamily(identity, "2_1_0_0", 0xEB6014, true).origin,
            )
        }
        val trimOnly = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, true, "PB01_08_0")
        assertEquals(DeviceFamily.UNKNOWN, trimOnly.family)
        assertEquals(null, trimOnly.suggestedFamily)
    }

    private val keyboardModels = linkedMapOf(
        DeviceFamily.SMK37_PRO to "SMK-37 Pro_016",
        DeviceFamily.SMK37_ELITE to "SMK-37 Elite_016",
        DeviceFamily.STARRYKEY37_PLAY to "STARRYKEY-37 PLAY_016",
        DeviceFamily.MKE_P37 to "MKE-P37_015",
    )
}