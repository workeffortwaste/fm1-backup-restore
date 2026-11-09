package com.workeffortwaste

/** Supported device models and their connected-device and backup-row labels. */
enum class DeviceFamily(
    val displayName: String,
    val metadataName: String,
    val backupTagLabel: String = metadataName,
) {
    FM1("FM-1", "FM-1"),
    MINI_FM("MiniFM", "MiniFM"),
    SMK37_PRO("SMK-37 Pro", "SMK-37 Pro", "SMK-P"),
    SMK37_ELITE("SMK-37 Elite", "SMK-37 Elite", "SMK-E"),
    STARRYKEY37_PLAY("Starrykey-37 PLAY", "Starrykey-37 PLAY", "SK37"),
    MKE_P37("MKE-P37", "MKE-P37", "MKE37"),
    POCKET_X("Pocket X", "Pocket X", "PX"),
    BLACKBOX("BlackBox", "BlackBox", "BB"),
    POCKET_AMP("Pocket AMP", "Pocket AMP", "PA"),
    UNKNOWN("Unknown", "Unknown");

    val isFm1Family: Boolean
        get() = this == FM1 || this == MINI_FM

    val isSmk37Family: Boolean
        get() = this == SMK37_PRO || this == SMK37_ELITE ||
                this == STARRYKEY37_PLAY || this == MKE_P37

    companion object {
        fun fromMidiIdentity(identity: String?): DeviceFamily {
            val value = identity?.trim() ?: return UNKNOWN
            return when {
                value.startsWith("FM-1", ignoreCase = true) -> FM1
                value.startsWith("MiniFM", ignoreCase = true) -> MINI_FM
                value.startsWith("SMK-37 Pro", ignoreCase = true) -> SMK37_PRO
                value.startsWith("SMK-37 Elite", ignoreCase = true) -> SMK37_ELITE
                value.startsWith("STARRYKEY-37 PLAY", ignoreCase = true) -> STARRYKEY37_PLAY
                value.startsWith("MKE-P37", ignoreCase = true) -> MKE_P37
                value.startsWith("PocketX", ignoreCase = true) -> POCKET_X
                value.startsWith("BlackBox", ignoreCase = true) -> BLACKBOX
                value.startsWith("Pocket AMP_", ignoreCase = true) -> POCKET_AMP
                else -> UNKNOWN
            }
        }
    }
}

enum class FamilyEvidenceOrigin {
    MIDI_PLUS_RECOVERY,
    RECOVERY_MARKER,
    RECOVERY_BOOT_CONFIG,
    IMPORTED_IMAGE_SCAN,
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
    sourceFamily.isFm1Family && targetFamily.isFm1Family -> BackupCompatibility.COMPATIBLE
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
    val resetSetting: String? = null,
)

private const val FM1_RESET = "PB01_08_0"
private const val POCKET_AMP_RESET = "PB01_00_0"

private fun resetFamily(resetSetting: String?): DeviceFamily? = when (resetSetting) {
    FM1_RESET -> DeviceFamily.FM1
    POCKET_AMP_RESET -> DeviceFamily.POCKET_AMP
    else -> null
}

/**
 * Documented RESET expectations: BlackBox shares Pocket AMP's record, keyboards share FM-1's,
 * so only FM-1 variants, BlackBox and Pocket AMP treat a different recognised RESET as a contradiction.
 */
private fun expectedResetSetting(family: DeviceFamily): String? = when (family) {
    DeviceFamily.FM1, DeviceFamily.MINI_FM -> FM1_RESET
    DeviceFamily.POCKET_AMP, DeviceFamily.BLACKBOX -> POCKET_AMP_RESET
    else -> null
}

/**
 * Classifies recovery evidence, independently of whether the connection route requires a user choice.
 * BlackBox and Pocket AMP share boot settings; their M-UPGRADE identities distinguish them.
 * The keyboard trim marker identifies a shared platform, never an individual keyboard model.
 * Missing evidence alone lowers confidence; recognised contradictions take precedence over hints.
 * MIDI identifies running firmware, not a unique physical unit.
 */
fun classifyDeviceFamily(
    midiIdentity: String?,
    spiSetting: String?,
    jedecId: Long?,
    smkTrimMarkerPresent: Boolean,
    resetSetting: String? = null,
): DeviceClassification {
    val midiFamily = DeviceFamily.fromMidiIdentity(midiIdentity)
    val expectedSpi = when (midiFamily) {
        DeviceFamily.UNKNOWN -> null
        DeviceFamily.POCKET_X -> "2_1_0_0"
        else -> "4_1_0_0"
    }
    val recognizedSpi = spiSetting?.takeIf { it == "4_1_0_0" || it == "2_1_0_0" }
    val recognizedReset = resetSetting?.takeIf { it == FM1_RESET || it == POCKET_AMP_RESET }
    val isFm1Jedec = jedecId == 0x856014L || jedecId == 0xB36014L
    val fm1RecoveryPair = recognizedSpi == "4_1_0_0" && isFm1Jedec && resetSetting == FM1_RESET
    val pocketAmpRecoveryPair = recognizedSpi == "4_1_0_0" && jedecId == 0x856014L &&
            resetSetting == POCKET_AMP_RESET
    val recoverySuggestion = DeviceFamily.POCKET_X
        .takeIf { recognizedSpi == "2_1_0_0" && jedecId == 0x856014L }
    val base = DeviceClassification(
        rawMidiIdentity = midiIdentity,
        spiSetting = spiSetting,
        jedecId = jedecId,
        smkTrimMarkerPresent = smkTrimMarkerPresent,
        resetSetting = resetSetting,
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
    val expectedReset = expectedResetSetting(midiFamily)
    if (expectedReset != null && recognizedReset != null && recognizedReset != expectedReset) {
        return result(
            DeviceFamily.UNKNOWN, FamilyEvidenceOrigin.CONFLICT, FamilyEvidenceConfidence.NONE,
            "MIDI family conflicts with boot RESET setting $resetSetting",
        )
    }
    // The SPI and RESET conflicts above already exclude every non-keyboard MIDI family here.
    if (pocketAmpRecoveryPair && midiFamily.isSmk37Family) {
        return result(
            DeviceFamily.UNKNOWN, FamilyEvidenceOrigin.CONFLICT, FamilyEvidenceConfidence.NONE,
            "Pocket AMP SPI/RESET/JEDEC evidence conflicts with the keyboard MIDI family",
        )
    }
    if (fm1RecoveryPair && !smkTrimMarkerPresent && midiFamily.isSmk37Family) {
        return result(
            DeviceFamily.UNKNOWN,
            FamilyEvidenceOrigin.CONFLICT,
            FamilyEvidenceConfidence.NONE,
            "FM-1 SPI/RESET/JEDEC recovery evidence conflicts with the keyboard MIDI family",
        )
    }
    if (smkTrimMarkerPresent) {
        if (midiFamily != DeviceFamily.UNKNOWN && !midiFamily.isSmk37Family) {
            return result(
                DeviceFamily.UNKNOWN,
                FamilyEvidenceOrigin.CONFLICT,
                FamilyEvidenceConfidence.NONE,
                "SMK trim marker conflicts with MIDI family ${midiFamily.displayName}",
            )
        }
        return result(
            midiFamily,
            if (midiFamily.isSmk37Family) {
                FamilyEvidenceOrigin.MIDI_PLUS_RECOVERY
            } else {
                FamilyEvidenceOrigin.RECOVERY_MARKER
            },
            if (midiFamily.isSmk37Family) FamilyEvidenceConfidence.HIGH else FamilyEvidenceConfidence.LOW,
            if (midiFamily.isSmk37Family) {
                "MIDI identifies ${midiFamily.displayName} and the shared SMK trim marker is present"
            } else {
                "Shared SMK trim marker found; select Pro, Elite, Starrykey-37 PLAY or MKE-P37"
            },
        )
    }
    if (fm1RecoveryPair) {
        return result(
            midiFamily.takeIf { it.isFm1Family } ?: DeviceFamily.FM1,
            FamilyEvidenceOrigin.RECOVERY_BOOT_CONFIG,
            FamilyEvidenceConfidence.HIGH,
            "SPI 4_1_0_0, RESET PB01_08_0 and a known FM-1 JEDEC ID agree",
        )
    }
    if (midiFamily != DeviceFamily.UNKNOWN) {
        if (midiFamily.isSmk37Family) {
            return result(
                midiFamily,
                FamilyEvidenceOrigin.MIDI_HINT,
                FamilyEvidenceConfidence.LOW,
                "MIDI suggests ${midiFamily.displayName}, but the positive trim marker was not found",
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
            recognizedSpi == "2_1_0_0" -> "Boot SPI suggests Pocket X, but JEDEC does not match a known pair"
            pocketAmpRecoveryPair ->
                "SPI/RESET/JEDEC match both Pocket AMP and BlackBox; MIDI identity or manual selection is required"
            recognizedSpi == "4_1_0_0" ->
                "Boot SPI/JEDEC alone cannot distinguish FM-1, Pocket AMP or SMK-37; manual selection is required"
            else -> "No supported MIDI identity or decisive recovery marker was found"
        },
        suggestedFamily = recoverySuggestion,
    )
}

/**
 * Classifies saved image evidence without inventing a live JEDEC ID or connection route.
 * Shared Pocket AMP/BlackBox boot settings stay unresolved without a matching model identity.
 */
internal fun classifyImportedImage(
    midiIdentity: String?,
    spiSetting: String?,
    smkTrimMarkerPresent: Boolean,
    resetSetting: String? = null,
): DeviceClassification {
    val midiFamily = DeviceFamily.fromMidiIdentity(midiIdentity)
    val blackBoxConfig = midiFamily == DeviceFamily.BLACKBOX &&
            spiSetting == "4_1_0_0" && resetSetting == POCKET_AMP_RESET
    val family = when {
        smkTrimMarkerPresent -> midiFamily.takeIf { it.isSmk37Family } ?: DeviceFamily.UNKNOWN
        midiFamily.isFm1Family && spiSetting == "4_1_0_0" -> midiFamily
        midiFamily.isSmk37Family && spiSetting == "4_1_0_0" -> midiFamily
        midiFamily == DeviceFamily.BLACKBOX && spiSetting == "4_1_0_0" ->
            DeviceFamily.BLACKBOX
        midiFamily == DeviceFamily.POCKET_AMP && spiSetting == "4_1_0_0" ->
            DeviceFamily.POCKET_AMP
        // Without identity, the shared Pocket AMP/BlackBox boot settings stay unresolved.
        midiFamily == DeviceFamily.UNKNOWN && spiSetting == "4_1_0_0" &&
                resetSetting == POCKET_AMP_RESET ->
            DeviceFamily.UNKNOWN
        spiSetting == "4_1_0_0" -> resetFamily(resetSetting) ?: DeviceFamily.UNKNOWN
        spiSetting == "2_1_0_0" -> DeviceFamily.POCKET_X
        else -> DeviceFamily.UNKNOWN
    }
    val expectedReset = expectedResetSetting(midiFamily)
    val recognizedReset = resetSetting?.takeIf { it == FM1_RESET || it == POCKET_AMP_RESET }
    val conflicts = (expectedReset != null && recognizedReset != null && recognizedReset != expectedReset) ||
            (smkTrimMarkerPresent && midiFamily != DeviceFamily.UNKNOWN && !midiFamily.isSmk37Family) ||
            (family != DeviceFamily.UNKNOWN && midiFamily != DeviceFamily.UNKNOWN &&
                    midiFamily != family)
    val resolvedFamily = if (conflicts) DeviceFamily.UNKNOWN else family
    val origin = when {
        conflicts -> FamilyEvidenceOrigin.CONFLICT
        resolvedFamily == DeviceFamily.UNKNOWN -> FamilyEvidenceOrigin.UNKNOWN
        else -> FamilyEvidenceOrigin.IMPORTED_IMAGE_SCAN
    }
    val confidence = when {
        conflicts || resolvedFamily == DeviceFamily.UNKNOWN -> FamilyEvidenceConfidence.NONE
        smkTrimMarkerPresent -> FamilyEvidenceConfidence.HIGH
        else -> FamilyEvidenceConfidence.MEDIUM
    }
    val explanation = when {
        conflicts -> "Imported image recovery evidence conflicts with MIDI identity or boot RESET"
        smkTrimMarkerPresent && midiFamily.isSmk37Family ->
            "Imported image has ${midiFamily.displayName} MIDI evidence and the shared SMK trim marker"
        smkTrimMarkerPresent -> "Imported image has the shared SMK trim marker; the specific keyboard model is unknown"
        blackBoxConfig -> "Imported image has the BlackBox SPI/RESET pair and BlackBox MIDI identity"
        family != DeviceFamily.UNKNOWN && spiSetting == "4_1_0_0" && resetSetting != null ->
            "Imported image contains SPI 4_1_0_0 and RESET $resetSetting"
        family != DeviceFamily.UNKNOWN && spiSetting == "4_1_0_0" ->
            "Imported image boot SPI 4_1_0_0 agrees with the ${family.displayName} MIDI identity"
        spiSetting == "4_1_0_0" && resetSetting == POCKET_AMP_RESET ->
            "Imported image has the shared Pocket AMP/BlackBox SPI and RESET settings without MIDI identity"
        spiSetting == "2_1_0_0" -> "Imported image contains SPI 2_1_0_0"
        else -> "No supported device evidence was found in the imported image"
    }
    return DeviceClassification(
        family = resolvedFamily,
        origin = origin,
        confidence = confidence,
        rawMidiIdentity = midiIdentity,
        spiSetting = spiSetting,
        smkTrimMarkerPresent = smkTrimMarkerPresent,
        explanation = explanation,
        resetSetting = resetSetting,
    )
}