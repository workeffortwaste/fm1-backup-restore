package com.workeffortwaste

/** Supported compatibility families and their connected-device and backup-row labels. */
enum class DeviceFamily(
    val displayName: String,
    val metadataName: String,
    val backupTagLabel: String = metadataName,
) {
    FM1("FM-1", "FM-1"),
    SMK37("SMK-37 Pro / Elite", "SMK-37"),
    POCKETX_BLACKBOX("Pocket X / Blackbox", "Pocket X / Blackbox", "PX-BB"),
    UNKNOWN("Unknown", "Unknown");

    companion object {
        fun fromMidiIdentity(identity: String?): DeviceFamily {
            val value = identity?.trim() ?: return UNKNOWN
            return when {
                value.startsWith("FM-1", ignoreCase = true) -> FM1
                value.startsWith("SMK-37", ignoreCase = true) -> SMK37
                value.startsWith("PocketX", ignoreCase = true) -> POCKETX_BLACKBOX
                value.startsWith("BlackBox", ignoreCase = true) -> POCKETX_BLACKBOX
                else -> UNKNOWN
            }
        }
    }
}

enum class FamilyEvidenceOrigin {
    MIDI_PLUS_RECOVERY,
    RECOVERY_MARKER,
    RECOVERY_SPI_JEDEC,
    LEGACY_BACKUP_SCAN,
    MIDI_HINT,
    MANUAL,
    UNKNOWN,
    CONFLICT,
}

enum class FamilyEvidenceConfidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

enum class BackupCompatibility {
    COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN,
}

/** Unknown is unresolved, never implicitly compatible. */
fun compatibility(sourceFamily: DeviceFamily, targetFamily: DeviceFamily): BackupCompatibility = when {
    sourceFamily == DeviceFamily.UNKNOWN || targetFamily == DeviceFamily.UNKNOWN -> BackupCompatibility.UNKNOWN
    sourceFamily == targetFamily -> BackupCompatibility.COMPATIBLE
    else -> BackupCompatibility.INCOMPATIBLE
}

/** Keeps observed evidence and provenance separate from any manually selected family. */
data class DeviceClassification(
    val family: DeviceFamily = DeviceFamily.UNKNOWN,
    val origin: FamilyEvidenceOrigin = FamilyEvidenceOrigin.UNKNOWN,
    val confidence: FamilyEvidenceConfidence = FamilyEvidenceConfidence.NONE,
    val rawMidiIdentity: String? = null,
    val spiSetting: String? = null,
    val jedecId: Long? = null,
    val smkTrimMarkerPresent: Boolean = false,
    val suggestedFamily: DeviceFamily? = null,
    val explanation: String = "Device family is unknown",
)

/**
 * Uses MIDI as a hint and recovery evidence as a cross-check. Known FM-1 SPI/JEDEC pairs can
 * resolve the family; other known pairs may suggest a family for manual confirmation.
 * A missing SMK marker is not negative evidence, and SPI alone never assigns a family.
 */
fun classifyDeviceFamily(
    midiIdentity: String?,
    spiSetting: String?,
    jedecId: Long?,
    smkTrimMarkerPresent: Boolean,
): DeviceClassification {
    val midiFamily = DeviceFamily.fromMidiIdentity(midiIdentity)
    val expectedSpi = when (midiFamily) {
        DeviceFamily.FM1, DeviceFamily.SMK37 -> "4_1_0_0"
        DeviceFamily.POCKETX_BLACKBOX -> "2_1_0_0"
        DeviceFamily.UNKNOWN -> null
    }
    val recognizedSpi = spiSetting?.takeIf { it == "4_1_0_0" || it == "2_1_0_0" }
    val isFm1Jedec = jedecId == 0x856014L || jedecId == 0xB36014L
    val fm1RecoveryPair = recognizedSpi == "4_1_0_0" && isFm1Jedec
    val recoverySuggestion = when (recognizedSpi) {
        "2_1_0_0" -> if (jedecId == 0x856014L) DeviceFamily.POCKETX_BLACKBOX else null
        "4_1_0_0" -> if (jedecId == 0xEB6014L) DeviceFamily.SMK37 else null
        else -> null
    }
    val base = DeviceClassification(
        rawMidiIdentity = midiIdentity,
        spiSetting = spiSetting,
        jedecId = jedecId,
        smkTrimMarkerPresent = smkTrimMarkerPresent,
    )

    fun result(
        family: DeviceFamily,
        origin: FamilyEvidenceOrigin,
        confidence: FamilyEvidenceConfidence,
        explanation: String,
        suggestedFamily: DeviceFamily? = null,
    ) = base.copy(
        family = family,
        origin = origin,
        confidence = confidence,
        suggestedFamily = suggestedFamily,
        explanation = explanation,
    )

    if (recognizedSpi != null && expectedSpi != null && recognizedSpi != expectedSpi) {
        return result(
            DeviceFamily.UNKNOWN,
            FamilyEvidenceOrigin.CONFLICT,
            FamilyEvidenceConfidence.NONE,
            "MIDI family conflicts with boot SPI setting $recognizedSpi",
        )
    }
    if (fm1RecoveryPair && (smkTrimMarkerPresent ||
            midiFamily != DeviceFamily.UNKNOWN && midiFamily != DeviceFamily.FM1)
    ) {
        return result(
            DeviceFamily.UNKNOWN,
            FamilyEvidenceOrigin.CONFLICT,
            FamilyEvidenceConfidence.NONE,
            "FM-1 SPI/JEDEC recovery evidence conflicts with SMK marker or MIDI family",
        )
    }
    if (smkTrimMarkerPresent) {
        if (midiFamily != DeviceFamily.UNKNOWN && midiFamily != DeviceFamily.SMK37) {
            return result(
                DeviceFamily.UNKNOWN,
                FamilyEvidenceOrigin.CONFLICT,
                FamilyEvidenceConfidence.NONE,
                "SMK trim marker conflicts with MIDI family ${midiFamily.displayName}",
            )
        }
        return result(
            DeviceFamily.SMK37,
            if (midiFamily == DeviceFamily.SMK37 && recognizedSpi == "4_1_0_0") {
                FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY
            } else {
                FamilyEvidenceOrigin.RECOVERY_MARKER
            },
            FamilyEvidenceConfidence.HIGH,
            "Exact SMK trim marker found at the expected flash address",
        )
    }
    if (fm1RecoveryPair) {
        return result(
            DeviceFamily.FM1,
            FamilyEvidenceOrigin.RECOVERY_SPI_JEDEC,
            FamilyEvidenceConfidence.HIGH,
            "SPI 4_1_0_0 and a known FM-1 JEDEC ID agree",
        )
    }
    if (midiFamily != DeviceFamily.UNKNOWN) {
        if (midiFamily == DeviceFamily.SMK37) {
            return result(
                midiFamily,
                FamilyEvidenceOrigin.MIDI_HINT,
                FamilyEvidenceConfidence.LOW,
                "MIDI suggests SMK-37, but the positive trim marker was not found",
            )
        }
        if (recognizedSpi == expectedSpi) {
            return result(
                midiFamily,
                FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY,
                FamilyEvidenceConfidence.HIGH,
                "MIDI family and boot SPI setting agree",
            )
        }
        return result(
            midiFamily,
            FamilyEvidenceOrigin.MIDI_HINT,
            FamilyEvidenceConfidence.LOW,
            "MIDI identity suggests a family, but recovery SPI evidence is unavailable",
        )
    }
    return result(
        DeviceFamily.UNKNOWN,
        FamilyEvidenceOrigin.UNKNOWN,
        if (recoverySuggestion != null) FamilyEvidenceConfidence.MEDIUM else FamilyEvidenceConfidence.NONE,
        when {
            recoverySuggestion != null ->
                "Boot SPI and JEDEC suggest ${recoverySuggestion.displayName}; manual selection is required"
            recognizedSpi == "2_1_0_0" -> "Boot SPI suggests Pocket X / Blackbox, but JEDEC does not match a known pair"
            recognizedSpi == "4_1_0_0" -> "Boot SPI cannot distinguish FM-1 from SMK-37; manual selection is required"
            else -> "No supported MIDI identity or decisive recovery marker was found"
        },
        suggestedFamily = recoverySuggestion,
    )
}

internal fun classifyLegacyBackup(
    midiIdentity: String?,
    spiSetting: String?,
    smkTrimMarkerPresent: Boolean,
): DeviceClassification {
    val midiFamily = DeviceFamily.fromMidiIdentity(midiIdentity)
    val family = when {
        smkTrimMarkerPresent -> DeviceFamily.SMK37
        spiSetting == "4_1_0_0" -> DeviceFamily.FM1
        spiSetting == "2_1_0_0" -> DeviceFamily.POCKETX_BLACKBOX
        else -> DeviceFamily.UNKNOWN
    }
    val conflicts = family != DeviceFamily.UNKNOWN && midiFamily != DeviceFamily.UNKNOWN && midiFamily != family
    val resolvedFamily = if (conflicts) DeviceFamily.UNKNOWN else family
    val origin = when {
        conflicts -> FamilyEvidenceOrigin.CONFLICT
        resolvedFamily == DeviceFamily.UNKNOWN -> FamilyEvidenceOrigin.UNKNOWN
        else -> FamilyEvidenceOrigin.LEGACY_BACKUP_SCAN
    }
    val confidence = when {
        conflicts || resolvedFamily == DeviceFamily.UNKNOWN -> FamilyEvidenceConfidence.NONE
        smkTrimMarkerPresent -> FamilyEvidenceConfidence.HIGH
        else -> FamilyEvidenceConfidence.MEDIUM
    }
    val explanation = when {
        conflicts -> "Legacy backup recovery evidence conflicts with captured M-UPGRADE identity"
        smkTrimMarkerPresent -> "Legacy backup contains the SMK-37 trim marker"
        spiSetting == "4_1_0_0" -> "Legacy backup contains SPI 4_1_0_0"
        spiSetting == "2_1_0_0" -> "Legacy backup contains SPI 2_1_0_0"
        else -> "No supported device evidence was found in the legacy backup"
    }
    return DeviceClassification(
        family = resolvedFamily,
        origin = origin,
        confidence = confidence,
        rawMidiIdentity = midiIdentity,
        spiSetting = spiSetting,
        smkTrimMarkerPresent = smkTrimMarkerPresent,
        explanation = explanation,
    )
}