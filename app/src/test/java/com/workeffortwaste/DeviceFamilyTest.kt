package com.workeffortwaste

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFamilyTest {
    @Test
    fun mapsSupportedMidiPrefixesWithoutRequiringVersionSuffixes() {
        assertEquals(DeviceFamily.FM1, DeviceFamily.fromMidiIdentity("FM-1_015"))
        assertEquals(DeviceFamily.SMK37, DeviceFamily.fromMidiIdentity("SMK-37 Pro_016"))
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, DeviceFamily.fromMidiIdentity("PocketX_924"))
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, DeviceFamily.fromMidiIdentity("BlackBox_9xx"))
        assertEquals("PX-BB", DeviceFamily.POCKETX_BLACKBOX.backupTagLabel)
        assertEquals("Pocket X / Blackbox", DeviceFamily.POCKETX_BLACKBOX.metadataName)
    }

    @Test
    fun prefixMatchingIsCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(DeviceFamily.FM1, DeviceFamily.fromMidiIdentity(" fm-1_custom "))
        assertEquals(DeviceFamily.SMK37, DeviceFamily.fromMidiIdentity("smk-37 elite"))
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, DeviceFamily.fromMidiIdentity("blackbox_001"))
    }

    @Test
    fun editorLabelsAndUnknownValuesDoNotClassifyAsDevices() {
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity(null))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("JIANT v1.0"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("FELUCCA 1.0"))
        assertEquals(DeviceFamily.UNKNOWN, DeviceFamily.fromMidiIdentity("FM1_OFFICIAL_OPEN"))
    }

    @Test
    fun recoveryClassifierUsesKnownPairsAndKeepsAmbiguousEvidenceUnresolved() {
        val fm1 = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.FM1, fm1.family)
        assertEquals(FamilyEvidenceOrigin.RECOVERY_SPI_JEDEC, fm1.origin)

        val pocketX = classifyDeviceFamily("BlackBox_924", "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, pocketX.family)

        val unknownMidi = classifyDeviceFamily(null, "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.UNKNOWN, unknownMidi.family)
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, unknownMidi.suggestedFamily)
        assertEquals(FamilyEvidenceConfidence.MEDIUM, unknownMidi.confidence)
        assertEquals(FamilyEvidenceOrigin.UNKNOWN, unknownMidi.origin)

        val smkSuggestion = classifyDeviceFamily(null, "4_1_0_0", 0xEB6014, false)
        assertEquals(DeviceFamily.UNKNOWN, smkSuggestion.family)
        assertEquals(DeviceFamily.SMK37, smkSuggestion.suggestedFamily)

        val unfamiliarPair = classifyDeviceFamily(null, "2_1_0_0", 0xB36014, false)
        assertEquals(DeviceFamily.UNKNOWN, unfamiliarPair.family)
        assertEquals(null, unfamiliarPair.suggestedFamily)
        assertEquals(FamilyEvidenceConfidence.NONE, unfamiliarPair.confidence)

        for (fm1Jedec in listOf(0x856014L, 0xB36014L)) {
            val recoveredFm1 = classifyDeviceFamily(null, "4_1_0_0", fm1Jedec, false)
            assertEquals(DeviceFamily.FM1, recoveredFm1.family)
            assertEquals(FamilyEvidenceOrigin.RECOVERY_SPI_JEDEC, recoveredFm1.origin)
            assertEquals(FamilyEvidenceConfidence.HIGH, recoveredFm1.confidence)
            assertEquals(fm1Jedec, recoveredFm1.jedecId)
        }

        val smkMidiConflict = classifyDeviceFamily("SMK-37 Pro_016", "4_1_0_0", 0x856014, false)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, smkMidiConflict.origin)
        val smkMarkerConflict = classifyDeviceFamily(null, "4_1_0_0", 0xB36014, true)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, smkMarkerConflict.origin)

    }

    @Test
    fun recoveryClassifierHandlesPositiveTrimMissingEvidenceAndConflicts() {
        val smk = classifyDeviceFamily(null, "4_1_0_0", 0x856014, true)
        assertEquals(DeviceFamily.UNKNOWN, smk.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, smk.origin)
        assertEquals(FamilyEvidenceConfidence.NONE, smk.confidence)

        val smkHint = classifyDeviceFamily("SMK-37 Elite", null, 0xEB6014, false)
        assertEquals(DeviceFamily.SMK37, smkHint.family)
        assertEquals(FamilyEvidenceOrigin.MIDI_HINT, smkHint.origin)
        assertEquals(FamilyEvidenceConfidence.LOW, smkHint.confidence)

        val conflict = classifyDeviceFamily("FM-1_015", "2_1_0_0", 0x856014, false)
        assertEquals(DeviceFamily.UNKNOWN, conflict.family)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, conflict.origin)

        val trimConflict = classifyDeviceFamily("BlackBox_924", "2_1_0_0", 0x856014, true)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, trimConflict.origin)
    }

    @Test
    fun compatibilityTreatsUnknownAsUnresolvedAndOnlyMatchesSameFamilies() {
        assertEquals(BackupCompatibility.COMPATIBLE, compatibility(DeviceFamily.FM1, DeviceFamily.FM1))
        assertEquals(
            BackupCompatibility.INCOMPATIBLE,
            compatibility(DeviceFamily.FM1, DeviceFamily.SMK37),
        )
        assertEquals(BackupCompatibility.UNKNOWN, compatibility(DeviceFamily.UNKNOWN, DeviceFamily.FM1))
        assertEquals(BackupCompatibility.UNKNOWN, compatibility(DeviceFamily.FM1, DeviceFamily.UNKNOWN))
    }

    @Test
    fun legacyBackupsUseSpiAndTrimEvidenceWithoutOverridingMidiConflict() {
        assertEquals(DeviceFamily.FM1, classifyLegacyBackup(null, "4_1_0_0", false).family)
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, classifyLegacyBackup(null, "2_1_0_0", false).family)
        assertEquals(DeviceFamily.SMK37, classifyLegacyBackup(null, "4_1_0_0", true).family)
        assertEquals(FamilyEvidenceConfidence.HIGH, classifyLegacyBackup(null, null, true).confidence)
        assertEquals(
            FamilyEvidenceOrigin.CONFLICT,
            classifyLegacyBackup("FM-1_909", "2_1_0_0", false).origin,
        )
        assertEquals(DeviceFamily.UNKNOWN, classifyLegacyBackup(null, null, false).family)
    }

}