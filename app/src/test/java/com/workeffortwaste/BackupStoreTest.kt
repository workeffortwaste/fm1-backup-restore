package com.workeffortwaste

import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.Fm1Protocol
import com.workeffortwaste.protocol.RestoreImage
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Verifies persistent backup publication, metadata, corruption handling, and path/symlink containment. */
class BackupStoreTest {
    private lateinit var root: File
    private lateinit var directory: File
    private lateinit var store: BackupStore
    private val bytes = ByteArray(FlashRestore.FLASH_SIZE) { (it % 251).toByte() }
    private val hash get() = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Before fun setup() {
        val parent = File("build/backup-store-tests").apply { mkdirs() }
        root = Files.createTempDirectory(parent.toPath(), "library-").toFile()
        directory = File(root, "backups")
        store = BackupStore(directory)
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    private fun save(): BackupEntry {
        val pending = store.createPending()
        pending.writeBytes(bytes)
        return store.publish(pending, hash)
    }

    private fun saveImage(
        name: String,
        spiSetting: String?,
        trimMarker: Boolean = false,
        metadata: BackupMetadata? = null,
        resetSetting: String? = if (spiSetting == "4_1_0_0") "PB01_08_0" else null,
        appIdentity: String? = null,
    ): BackupEntry {
        val image = bytes.copyOf()
        val config = ByteArrayOutputStream().apply {
            write(ByteArray(34))
            fun record(key: String, value: String) {
                val valueBytes = value.toByteArray(Charsets.US_ASCII)
                write(valueBytes.size)
                write(key.toByteArray(Charsets.US_ASCII))
                write(0)
                write(valueBytes)
            }
            record("OSC", "OSC0")
            spiSetting?.let { record("SPI", it) }
            resetSetting?.let { record("RESET", it) }
        }.toByteArray()
        val bootArea = ByteArray(FlashRestore.PROTECTED_SIZE) { 0xff.toByte() }

        fun write16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value ushr 8).toByte()
        }

        fun write32(bytes: ByteArray, offset: Int, value: Int) {
            repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
        }

        fun encode(bytes: ByteArray): ByteArray {
            var key = 0xffff
            return ByteArray(bytes.size) { index ->
                val value = bytes[index].toInt() xor (key and 0xff)
                key = ((key shl 1) xor (if (key and 0x8000 != 0) 0x1021 else 0)) and 0xffff
                value.toByte()
            }
        }

        fun writeEntry(offset: Int, fileName: String, address: Int, length: Int, dataCrc: Int, attribute: Int) {
            val entry = ByteArray(32)
            write16(entry, 2, dataCrc)
            write32(entry, 4, address)
            write32(entry, 8, length)
            entry[12] = attribute.toByte()
            fileName.toByteArray(Charsets.US_ASCII).copyInto(entry, 16)
            write16(entry, 0, Fm1Protocol.crc16(entry.copyOfRange(2, entry.size)))
            encode(entry).copyInto(bootArea, offset)
        }

        val header = ByteArray(32)
        write16(header, 0, Fm1Protocol.crc16(header.copyOfRange(2, header.size)))
        encode(header).copyInto(bootArea)
        writeEntry(0x20, "uboot.boot", 0xa0, 16, 0, 0)
        writeEntry(0x40, "isd_config.ini", 0x100, config.size, Fm1Protocol.crc16(config), 2)
        writeEntry(0x60, "app_dir_head", FlashRestore.PROTECTED_SIZE, 0, 0, 0x81)
        writeEntry(0x80, "key_mac", 0xff000, 0x1000, 0, 0x12)
        config.copyInto(bootArea, 0x100)
        bootArea.copyInto(image)
        if (appIdentity != null) {
            fun encodeKeyed(data: ByteArray, key: Int): ByteArray {
                var state = key and 0xffff
                return ByteArray(data.size) { index ->
                    val value = data[index].toInt() xor (state and 0xff)
                    state = ((state shl 1) xor (if (state and 0x8000 != 0) 0x1021 else 0)) and 0xffff
                    value.toByte()
                }
            }
            val payload = ByteArrayOutputStream().apply {
                write("  32 Voice Save To ...  ".toByteArray(Charsets.US_ASCII))
                write(0)
                write(appIdentity.toByteArray(Charsets.US_ASCII))
                write(0)
                write(ByteArray(7))
            }.toByteArray()
            val base = FlashRestore.PROTECTED_SIZE
            fun appRecord(relative: Int, recordName: String, address: Int, length: Int, dataCrc: Int) {
                val record = ByteArray(32)
                write16(record, 2, dataCrc)
                write32(record, 4, address)
                write32(record, 8, length)
                recordName.toByteArray(Charsets.US_ASCII).copyInto(record, 16)
                write16(record, 0, Fm1Protocol.crc16(record.copyOfRange(2, record.size)))
                encodeKeyed(record, 0x980F xor (relative shr 2)).copyInto(image, base + relative)
            }
            appRecord(0, "app_area_head", 0x120, 0x40 + payload.size, 0xff83)
            appRecord(0x20, "app.bin", 0x40, payload.size, Fm1Protocol.crc16(payload))
            for (offset in payload.indices step 32) {
                val chunk = payload.copyOfRange(offset, minOf(offset + 32, payload.size))
                encodeKeyed(chunk, 0x980F xor ((0x40 + offset) shr 2)).copyInto(image, base + 0x40 + offset)
            }
        }
        if (trimMarker) {
            "trimdat\u0000".toByteArray(Charsets.US_ASCII)
                .copyInto(image, com.workeffortwaste.protocol.SMK_TRIM_MARKER_ADDRESS.toInt())
        }

        val imageHash = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(image))
        val pending = store.createPending().apply { writeBytes(image) }
        return if (metadata == null) {
            store.publish(pending, imageHash, name)
        } else {
            store.publishWithMetadata(pending, imageHash, name, metadata)
        }
    }

    @Test fun emptyLibraryDoesNotRequireDirectory() {
        assertTrue(store.list().isEmpty())
    }

    @Test fun archiveRoundTripPreservesMetadataAndRejectsChangedHash() {
        val original = store.updateMetadata(save().id, BackupMetadata(
            classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
            userFamily = DeviceFamily.SMK37_PRO, userFirmwareLabel = "Custom tag",
        ))
        val output = ByteArrayOutputStream()
        store.exportBackupZip(original, output)
        val imported = BackupStore(File(root, "imported")).importZip(output.toByteArray().inputStream()).single()
        assertEquals(original.metadata, imported.metadata)
        assertEquals(original.sha256, imported.sha256)
        assertEquals(original.createdAt, imported.createdAt)

        val corrupted = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(corrupted).use { zip ->
            ZipInputStream(output.toByteArray().inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val data = input.readBytes()
                    if (entry.name.endsWith(".bin")) data[0] = (data[0] + 1).toByte()
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        val rejected = BackupStore(File(root, "rejected"))
        assertThrows(IllegalArgumentException::class.java) { rejected.importZip(corrupted.toByteArray().inputStream()) }
        assertTrue(rejected.list().isEmpty())
    }

    @Test fun archiveRejectsUnknownFamilyTagsInsteadOfSilentlyDroppingThem() {
        val original = store.updateMetadata(save().id, BackupMetadata(userFamily = DeviceFamily.FM1))
        val output = ByteArrayOutputStream()
        store.exportBackupZip(original, output)
        val contents = archiveEntries(output.toByteArray()).toMutableMap()
        val jsonName = contents.keys.single { it.endsWith(".json") }
        val json = JsonParser.parseString(contents.getValue(jsonName).toString(Charsets.UTF_8)).asJsonObject
        json.getAsJsonObject("metadata").addProperty("userFamily", "FUTURE_DEVICE")
        contents[jsonName] = json.toString().toByteArray()
        val target = BackupStore(File(root, "unknown-family-import"))
        assertThrows(IllegalArgumentException::class.java) {
            target.importZip(archive(*contents.toList().toTypedArray()).inputStream())
        }
        assertTrue(target.list().isEmpty())
    }

    @Test fun archiveSkipsExistingBackupOnlyWhenAllMetadataMatches() {
        val original = store.updateMetadata(save().id, BackupMetadata(
            classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
            userFirmwareLabel = "Original tag",
        ))
        val output = ByteArrayOutputStream()
        store.exportBackupZip(original, output)
        val packaged = output.toByteArray()
        assertTrue(store.importZip(packaged.inputStream()).isEmpty())
        assertEquals(listOf(original), store.list())

        val originalContents = archiveEntries(packaged)
        val jsonName = originalContents.keys.single { it.endsWith(".json") }
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("createdAt", original.createdAt + 1) },
            { it.addProperty("firmwareIdentity", "Different name") },
            { it.getAsJsonObject("metadata").addProperty("userFirmwareLabel", "Different tag") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("explanation", "Different evidence") },
        )
        for (mutate in mutations) {
            val contents = originalContents.toMutableMap()
            val json = JsonParser.parseString(contents.getValue(jsonName).toString(Charsets.UTF_8)).asJsonObject
            mutate(json)
            contents[jsonName] = json.toString().toByteArray(Charsets.UTF_8)
            val changed = archive(*contents.toList().toTypedArray())
            assertEquals(1, store.importZip(changed.inputStream()).size)
            assertTrue(store.importZip(changed.inputStream()).isEmpty())
        }
        assertEquals(1 + mutations.size, store.list().size)
    }

    @Test fun bulkArchiveSkipsDuplicatesWithinBatchAndOnRepeatedImport() {
        val original = save()
        val output = ByteArrayOutputStream()
        store.exportBackupZip(original, output)
        val packaged = output.toByteArray()
        val bulk = archive("first.zip" to packaged, "duplicate.zip" to packaged)
        val targetDirectory = File(root, "deduplicated-bulk")
        val target = BackupStore(targetDirectory)
        assertEquals(1, target.importZip(bulk.inputStream()).size)
        assertTrue(target.importZip(bulk.inputStream()).isEmpty())
        assertEquals(1, target.list().size)
        assertFalse(targetDirectory.listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test fun archiveRejectsMalformedMetadataFieldsWithoutPublishing() {
        val entry = store.updateMetadata(save().id, BackupMetadata(userFamily = DeviceFamily.FM1))
        val output = ByteArrayOutputStream()
        store.exportBackupZip(entry, output)
        val original = archiveEntries(output.toByteArray())
        val jsonName = original.keys.single { it.endsWith(".json") }
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("schema", 1.5) },
            { it.add("sha256", JsonNull.INSTANCE) },
            { it.addProperty("createdAt", 1700000000000.5) },
            { it.addProperty("metadata", "not an object") },
            { it.getAsJsonObject("metadata").addProperty("userFamily", 42) },
            { it.getAsJsonObject("metadata").addProperty("manualFamilySelection", "UNSUPPORTED") },
            { it.getAsJsonObject("metadata").addProperty("editorFirmwareLabel", 42) },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").remove("family") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("family", "UNSUPPORTED") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("origin", "UNSUPPORTED") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("confidence", "UNSUPPORTED") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("suggestedFamily", "UNSUPPORTED") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("smkTrimMarkerPresent", "false") },
            { it.getAsJsonObject("metadata").getAsJsonObject("classification").addProperty("jedecId", "856014") },
        )
        val targetDirectory = File(root, "malformed-metadata")
        val target = BackupStore(targetDirectory)
        for (mutate in mutations) {
            val contents = original.toMutableMap()
            val json = JsonParser.parseString(contents.getValue(jsonName).toString(Charsets.UTF_8)).asJsonObject
            mutate(json)
            contents[jsonName] = json.toString().toByteArray(Charsets.UTF_8)
            assertThrows(IllegalArgumentException::class.java) {
                target.importZip(archive(*contents.toList().toTypedArray()).inputStream())
            }
            assertTrue(target.list().isEmpty())
            assertTrue(targetDirectory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun firmwareIdentitySurvivesRecreationWithoutChangingBackupBytes() {
        val pending = store.createPending().apply { writeBytes(bytes) }
        val entry = store.publish(pending, hash, "FM-1_900")
        assertEquals("FM-1_900", entry.firmwareIdentity)
        assertEquals(listOf(entry), BackupStore(directory).list())
        store.open(entry.id).use { assertArrayEquals(bytes, it.readBytes()) }
        store.delete(entry.id)
        assertTrue(store.list().isEmpty())
    }

    @Test fun observedEvidenceAndUserTagsSurviveRenameWithoutChangingImage() {
        val classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false)
        val captured = BackupMetadata(classification, editorFirmwareLabel = "JIANT v1.0")
        val pending = store.createPending().apply { writeBytes(bytes) }
        val entry = store.publishWithMetadata(pending, hash, "FM-1_015", captured)
        assertEquals(captured, entry.metadata)

        val tagged = captured.copy(
            manualFamilySelection = DeviceFamily.FM1,
            userFamily = DeviceFamily.SMK37_PRO,
            userFirmwareLabel = "Custom label",
        )
        val updated = store.updateMetadata(entry.id, tagged)
        assertEquals(entry.id, updated.id)
        assertEquals(tagged, updated.metadata)
        assertEquals(entry.sha256, updated.sha256)

        val renamed = store.rename(updated.id, "Visible backup name")
        val restored = BackupStore(directory).list().single()
        assertEquals("Visible backup name", renamed.firmwareIdentity)
        assertEquals(tagged, restored.metadata)
        assertEquals(DeviceFamily.SMK37_PRO, restored.taggedFamily)
        assertTrue(restored.familyIsUserTagged)
        assertTrue(restored.familyIsManuallySelected)
        store.open(restored.id).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(hash, restored.sha256)
    }

    @Test fun keyboardModelEvidenceAndSelectionsPersistThroughRenameAndArchiveRoundTrip() {
        val identities = listOf(
            "SMK-37 Pro_016", "SMK-37 Elite_016", "STARRYKEY-37 PLAY_016", "MKE-P37_015",
        )
        for (identity in identities) {
            val family = DeviceFamily.fromMidiIdentity(identity)
            for (manual in listOf(false, true)) {
                val metadata = if (manual) {
                    BackupMetadata(
                        classification = DeviceClassification(
                            family = family, origin = FamilyEvidenceOrigin.MANUAL,
                            smkTrimMarkerPresent = true,
                        ),
                        manualFamilySelection = family,
                    )
                } else {
                    BackupMetadata(classifyDeviceFamily(identity, "4_1_0_0", 0xEB6014, true, "PB01_08_0"))
                }
                val pending = store.createPending().apply { writeBytes(bytes) }
                val original = store.publishWithMetadata(pending, hash, identity.takeUnless { manual }, metadata)
                val renamed = store.rename(original.id, "${family.displayName} snapshot")
                val restored = BackupStore(directory).list().single { it.id == renamed.id }
                assertEquals(family, restored.taggedFamily)
                assertEquals(metadata, restored.metadata)
                val archive = ByteArrayOutputStream()
                store.exportBackupZip(restored, archive)
                val target = BackupStore(File(root, "${family.name}-$manual"))
                val imported = target.importZip(archive.toByteArray().inputStream()).single()
                assertEquals(family, imported.taggedFamily)
                assertEquals(metadata, imported.metadata)
                assertEquals(hash, imported.sha256)
                target.open(imported.id).use { assertArrayEquals(bytes, it.readBytes()) }
            }
        }
    }

    @Test fun recoveryBackupWithoutMidiIdentityDoesNotPersistFirmwareTag() {
        val classification = classifyDeviceFamily(null, "4_1_0_0", 0x856014, false, "PB01_08_0")
        val pending = store.createPending().apply { writeBytes(bytes) }
        val entry = store.publishWithMetadata(
            pending,
            hash,
            firmwareIdentity = null,
            metadata = BackupMetadata(classification),
        )

        assertNull(entry.firmwareIdentity)
        assertNull(entry.metadata?.classification?.rawMidiIdentity)

        val restored = BackupStore(directory).list().single()
        assertNull(restored.firmwareIdentity)
        assertEquals(DeviceFamily.FM1, restored.taggedFamily)
        assertEquals("", restored.identityLine())
    }

    @Test fun importedImagesUseFlashEvidenceAndPreserveNamesAndImages() {
        val fm1 = saveImage("FM-1-looking filename", "4_1_0_0")
        val pocketX = saveImage("Pocket X", "2_1_0_0")
        val smk = saveImage("Snoop37", "4_1_0_0", trimMarker = true)
        val unknown = saveImage("FM-1_015", null)
        val custom = saveImage(
            "My backup",
            "2_1_0_0",
            metadata = BackupMetadata(
                userFirmwareLabel = "My custom label",
            ),
        )
        val originalHashById = listOf(fm1, pocketX, smk, unknown, custom).associate { it.id to it.sha256 }
        val classified = store.list().map(store::classifyImportedBackup)

        assertEquals(DeviceFamily.FM1, classified.single { it.id == fm1.id }.taggedFamily)
        val restoredPocketX = classified.single { it.id == pocketX.id }
        assertEquals(DeviceFamily.POCKET_X, restoredPocketX.taggedFamily)
        assertEquals(FamilyEvidenceOrigin.IMPORTED_IMAGE_SCAN, restoredPocketX.metadata?.classification?.origin)
        assertEquals(restoredPocketX.metadata, BackupStore(directory).list().single { it.id == pocketX.id }.metadata)
        assertEquals(DeviceFamily.UNKNOWN, classified.single { it.id == smk.id }.taggedFamily)
        assertEquals(DeviceFamily.UNKNOWN, classified.single { it.id == unknown.id }.taggedFamily)
        val restoredCustom = classified.single { it.id == custom.id }
        assertEquals(DeviceFamily.POCKET_X, restoredCustom.taggedFamily)
        assertEquals("My custom label", restoredCustom.metadata?.userFirmwareLabel)

        classified.forEach { entry ->
            assertEquals(originalHashById.getValue(entry.id), entry.sha256)
            store.open(entry.id).use { assertEquals(FlashRestore.FLASH_SIZE, it.readBytes().size) }
        }
    }

    @Test fun pocketAmpEvidenceSurvivesRenameExportAndImportWithoutChangingImage() {
        val classification = classifyDeviceFamily("Pocket AMP_105", "4_1_0_0", 0x856014, false, "PB01_00_0")
        val original = saveImage(
            "Onyx snapshot", "4_1_0_0",
            metadata = BackupMetadata(classification, userFirmwareLabel = "Onyx 1.01"),
            resetSetting = "PB01_00_0",
        )
        val renamed = store.rename(original.id, "Pocket AMP factory")
        assertEquals(DeviceFamily.POCKET_AMP, renamed.taggedFamily)
        assertEquals("PA", renamed.taggedFamily.backupTagLabel)
        assertEquals(original.metadata, BackupStore(directory).list().single().metadata)
        val output = ByteArrayOutputStream()
        store.exportBackupZip(renamed, output)
        val target = BackupStore(File(root, "pocket-import"))
        val imported = target.importZip(output.toByteArray().inputStream()).single()
        assertEquals(renamed.metadata, imported.metadata)
        assertEquals(renamed.sha256, imported.sha256)
        store.open(renamed.id).use { source ->
            target.open(imported.id).use { copy -> assertArrayEquals(source.readBytes(), copy.readBytes()) }
        }
    }

    @Test fun importedImageClassificationPreservesCapturedAndExplicitTags() {
        val captured = BackupMetadata(
            classification = DeviceClassification(
                family = DeviceFamily.FM1,
                origin = FamilyEvidenceOrigin.RECOVERY_BOOT_CONFIG,
                confidence = FamilyEvidenceConfidence.HIGH,
                spiSetting = "4_1_0_0",
                jedecId = 0x856014,
            ),
            userFirmwareLabel = "Keep this label",
        )
        val pocket = saveImage("Pocket snapshot", "4_1_0_0", resetSetting = "PB01_00_0")
        val automatic = saveImage("Captured tag", "4_1_0_0", metadata = captured, resetSetting = "PB01_00_0")
        val tagged = saveImage(
            "User tag", "4_1_0_0", metadata = captured.copy(userFamily = DeviceFamily.FM1),
            resetSetting = "PB01_00_0",
        )
        val manual = saveImage(
            "Manual tag", "4_1_0_0", metadata = captured.copy(manualFamilySelection = DeviceFamily.FM1),
            resetSetting = "PB01_00_0",
        )
        val before = store.list().associate { it.id to it.sha256 }
        val classified = store.list().map(store::classifyImportedBackup).associateBy { it.id }
        assertEquals(DeviceFamily.UNKNOWN, classified.getValue(pocket.id).taggedFamily)
        assertEquals(automatic.metadata, classified.getValue(automatic.id).metadata)
        assertEquals(tagged.metadata, classified.getValue(tagged.id).metadata)
        assertEquals(manual.metadata, classified.getValue(manual.id).metadata)
        classified.values.forEach { assertEquals(before.getValue(it.id), it.sha256) }
    }

    @Test fun importedImageIdentityIsExtractedFromEncryptedApplication() {
        val blackBox = saveImage(
            "BlackBox import", "4_1_0_0", resetSetting = "PB01_00_0", appIdentity = "BlackBox_021",
        )
        val classified = store.classifyImportedBackup(blackBox)
        assertEquals(DeviceFamily.BLACKBOX, classified.taggedFamily)
        assertEquals("BlackBox_021", classified.metadata?.classification?.rawMidiIdentity)

        // Without the stored identity, the shared Pocket AMP/BlackBox boot settings stay unresolved.
        val anonymous = saveImage("Anonymous import", "4_1_0_0", resetSetting = "PB01_00_0")
        assertEquals(DeviceFamily.UNKNOWN, store.classifyImportedBackup(anonymous).taggedFamily)

        // A stored identity that contradicts the boot records never silently wins.
        val conflicted = saveImage(
            "Conflicted import", "4_1_0_0", resetSetting = "PB01_00_0", appIdentity = "FM-1_015",
        )
        val conflict = store.classifyImportedBackup(conflicted)
        assertEquals(DeviceFamily.UNKNOWN, conflict.taggedFamily)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, conflict.metadata?.classification?.origin)
    }

    @Test fun archivesPreserveCapturedEvidenceWithoutReclassifyingBeforeDeduplication() {
        val original = saveImage(
            "Captured snapshot", "4_1_0_0", resetSetting = "PB01_00_0",
            metadata = BackupMetadata(classification = DeviceClassification(
                family = DeviceFamily.FM1,
                origin = FamilyEvidenceOrigin.RECOVERY_BOOT_CONFIG,
                confidence = FamilyEvidenceConfidence.MEDIUM,
                spiSetting = "4_1_0_0",
            )),
        )
        val archive = ByteArrayOutputStream()
        store.exportBackupZip(original, archive)
        val target = BackupStore(File(root, "captured-import"))
        val imported = target.importZip(archive.toByteArray().inputStream()).single()
        assertEquals(original.metadata, imported.metadata)
        assertEquals(original.sha256, imported.sha256)
        assertTrue(target.importZip(archive.toByteArray().inputStream()).isEmpty())
    }

    @Test fun identityLinePrefersCustomTagThenCapturedFirmwareAndNeverFallsBackToEditorName() {
        val automatic = save().copy(
            metadata = BackupMetadata(
                classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
            ),
        )
        assertEquals("FM-1_015", automatic.identityLine())

        val custom = automatic.copy(
            metadata = requireNotNull(automatic.metadata).copy(userFirmwareLabel = "Custom firmware"),
        )
        assertEquals("Custom firmware", custom.identityLine())
        assertEquals(
            "FM-1_015",
            custom.copy(metadata = requireNotNull(custom.metadata).copy(userFirmwareLabel = null)).identityLine(),
        )

        val manual = automatic.copy(
            metadata = BackupMetadata(
                manualFamilySelection = DeviceFamily.SMK37_PRO,
                userFirmwareLabel = "OpenPocket",
            ),
        )
        assertEquals("OpenPocket", manual.identityLine())
        assertEquals("", save().identityLine())
        assertEquals("", save().copy(firmwareIdentity = "FM-1_909", metadata = BackupMetadata()).identityLine())
    }

    @Test fun exportFilenameUsesVisibleNameAndAppendsHash() {
        val entry = save().copy(firmwareIdentity = "My FM-1")
        assertEquals("my-fm-1-$hash.bin", BackupStore.exportFileName(entry))
        assertEquals("fm-1-$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "FM/1")))
        assertEquals("m-vave-$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "M-VÁVE")))
        assertEquals("$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "***")))
        assertEquals("$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = null)))
    }

    @Test fun renameChangesOnlyTheIdAndKeepsBytes() {
        val entry = save()
        val renamed = store.rename(entry.id, "  My FM-1  ")
        assertEquals("My FM-1", renamed.firmwareIdentity)
        assertEquals(entry.sha256, renamed.sha256)
        store.open(renamed.id).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(listOf(renamed), BackupStore(directory).list())
        assertThrows(IllegalArgumentException::class.java) { store.rename(renamed.id, "  ") }
        assertEquals(renamed, store.list().single())
        val long = store.rename(renamed.id, "x".repeat(BackupStore.MAX_NAME_LENGTH))
        assertEquals(long, store.list().single())
        assertThrows(IllegalArgumentException::class.java) {
            store.rename(long.id, "x".repeat(BackupStore.MAX_NAME_LENGTH + 1))
        }
    }

    @Test fun zipExportsUniqueNamesAndHashesWithExactContents() {
        val first = store.rename(save().id, "My FM-1")
        val duplicate = store.rename(save().id, "My FM/1")
        val renamed = store.rename(save().id, "Other firmware")
        val differentBytes = bytes.copyOf().apply { this[0] = 42 }
        val differentHash = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(differentBytes))
        val pending = store.createPending().apply { writeBytes(differentBytes) }
        val different = store.publish(pending, differentHash, "My FM-1")
        val entries = listOf(first, duplicate, renamed, different)
        assertEquals(2, BackupStore.uniqueExports(entries).size)
        val destination = ByteArrayOutputStream()
        var completed = 0L
        store.exportZip(entries, destination, progress = { processed, total ->
            assertTrue(processed >= completed)
            assertEquals(2L * bytes.size, total)
            completed = processed
        })
        val extracted = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(destination.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertNull(extracted.put(entry.name, zip.readBytes()))
            }
        }
        assertEquals(2L * bytes.size, completed)
        assertEquals(listOf(first, different).map(BackupStore::exportArchiveName), extracted.keys.toList())
        val importedStore = BackupStore(File(root, "all-imported"))
        val imported = importedStore.importZip(destination.toByteArray().inputStream())
        assertEquals(listOf(first.sha256, different.sha256), imported.map { it.sha256 })
        assertEquals(listOf(first.createdAt, different.createdAt), imported.map { it.createdAt })
        importedStore.open(imported[0].id).use { assertArrayEquals(bytes, it.readBytes()) }
        importedStore.open(imported[1].id).use { assertArrayEquals(differentBytes, it.readBytes()) }
        assertEquals(4, store.list().size)
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        java.util.zip.ZipOutputStream(this).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun archiveEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes()
            }
        }
        return entries
    }

    @Test fun archiveRequiresIdentifierAndMatchingJsonAndNeverInfersUnpairedBins() {
        val entry = save()
        val output = ByteArrayOutputStream()
        store.exportBackupZip(entry, output)
        val packaged = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { input ->
            while (true) {
                val item = input.nextEntry ?: break
                packaged[item.name] = input.readBytes()
            }
        }
        val jsonName = packaged.keys.single { it.endsWith(".json") }
        val binName = packaged.keys.single { it.endsWith(".bin") }
        val target = BackupStore(File(root, "pairs"))
        for (invalid in listOf(
            archive(binName to bytes),
            archive(jsonName to "{}".toByteArray(), binName to bytes),
            archive(jsonName to packaged.getValue(jsonName)),
            archive(jsonName to packaged.getValue(jsonName), "different.bin" to bytes),
        )) {
            assertThrows(Exception::class.java) { target.importZip(invalid.inputStream()) }
            assertTrue(target.list().isEmpty())
        }
        val imported = target.importZip(archive(*packaged.toList().toTypedArray(), "unpaired.bin" to bytes).inputStream())
        assertEquals(1, imported.size)
    }

    @Test fun archiveRejectsUnsafeOversizedAndNestedInputWithoutPublishing() {
        val target = BackupStore(File(root, "invalid-archives"))
        for (invalid in listOf(
            archive("../image.bin" to bytes),
            archive("/image.bin" to bytes),
            archive("hidden/" to bytes),
            archive("image.bin" to ByteArray(FlashRestore.FLASH_SIZE * 2 + 1)),
            archive("outer.zip" to archive("inner.zip" to archive("image.bin" to bytes))),
        )) {
            assertThrows(IllegalArgumentException::class.java) { target.importZip(invalid.inputStream()) }
            assertTrue(target.list().isEmpty())
        }
    }

    @Test fun archiveCancellationCleansStagedAndPublishedBackups() {
        val output = ByteArrayOutputStream()
        store.exportZip(listOf(save()), output)
        val targetDirectory = File(root, "cancelled-import")
        val target = BackupStore(targetDirectory)
        var cancel = false
        assertThrows(CancellationException::class.java) {
            target.importZip(output.toByteArray().inputStream(),
                { if (cancel) throw CancellationException() }, { cancel = true })
        }
        assertTrue(target.list().isEmpty())
        assertTrue(targetDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun bulkImportRollsBackEarlierPublicationsWhenLaterMetadataIsInvalid() {
        val output = ByteArrayOutputStream()
        store.exportBackupZip(save(), output)
        val valid = output.toByteArray()
        val contents = linkedMapOf<String, ByteArray>()
        ZipInputStream(valid.inputStream()).use { input ->
            while (true) {
                val item = input.nextEntry ?: break
                contents[item.name] = input.readBytes()
            }
        }
        val jsonName = contents.keys.single { it.endsWith(".json") }
        val json = com.google.gson.JsonParser.parseString(contents.getValue(jsonName).toString(Charsets.UTF_8)).asJsonObject
        json.addProperty("firmwareIdentity", "x".repeat(BackupStore.MAX_NAME_LENGTH + 1))
        contents[jsonName] = json.toString().toByteArray()
        val invalid = archive(*contents.toList().toTypedArray())
        val targetDirectory = File(root, "rolled-back-import")
        val target = BackupStore(targetDirectory)
        assertThrows(IllegalArgumentException::class.java) {
            target.importZip(archive("valid.zip" to valid, "invalid.zip" to invalid).inputStream())
        }
        assertTrue(target.list().isEmpty())
        assertTrue(targetDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test fun importedImagesRequireBlackBoxIdentityAndRejectConflictingPocketX() {
        val blackbox = saveImage(
            "Backup label",
            "4_1_0_0",
            metadata = BackupMetadata(
                classification = DeviceClassification(rawMidiIdentity = "BlackBox_924"),
            ),
            resetSetting = "PB01_00_0",
        )

        val classified = store.classifyImportedBackup(blackbox)

        assertEquals(DeviceFamily.BLACKBOX, classified.taggedFamily)
        assertEquals("BlackBox_924", classified.metadata?.classification?.rawMidiIdentity)

        val pocketX = saveImage(
            "Conflicting backup label",
            "4_1_0_0",
            metadata = BackupMetadata(
                classification = DeviceClassification(rawMidiIdentity = "PocketX_009"),
            ),
            resetSetting = "PB01_00_0",
        )
        val classifiedPocketX = store.classifyImportedBackup(pocketX)
        assertEquals(DeviceFamily.UNKNOWN, classifiedPocketX.taggedFamily)
        assertEquals(FamilyEvidenceOrigin.CONFLICT, classifiedPocketX.metadata?.classification?.origin)
        assertEquals("PocketX_009", classifiedPocketX.metadata?.classification?.rawMidiIdentity)
    }

    @Test fun zipRejectsDamagedFirmwareAndPropagatesWriteFailures() {
        val entry = save()
        val failing = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Disk full") }
        }
        assertThrows(IOException::class.java) { store.exportZip(listOf(entry), failing) }
        File(directory, entry.id).writeBytes(ByteArray(bytes.size))
        assertThrows(IOException::class.java) { store.exportZip(listOf(entry), ByteArrayOutputStream()) }
    }

    @Test fun zipCancellationStopsVerificationAndWritingWithoutChangingLibrary() {
        val entry = save()
        assertThrows(CancellationException::class.java) {
            store.exportZip(listOf(entry), ByteArrayOutputStream(), { throw CancellationException() })
        }
        var cancel = false
        assertThrows(CancellationException::class.java) {
            store.exportZip(listOf(entry), ByteArrayOutputStream(),
                { if (cancel) throw CancellationException() },
                { _, _ -> cancel = true })
        }
        assertEquals(listOf(entry), store.list())
    }

    @Test fun backupsWithoutMetadataHaveNoAssumedFirmware() {
        val entry = save()
        assertNull(entry.firmwareIdentity)
        assertNull(entry.metadata)
        assertNull(BackupStore(directory).list().single().firmwareIdentity)
        assertNull(BackupStore(directory).list().single().metadata)
    }

    @Test fun rejectsInvalidFirmwareMetadataBeforePublication() {
        for (name in listOf("", "x".repeat(BackupStore.MAX_NAME_LENGTH + 1), "FM-1\nbad", "\u00e9")) {
            val pending = store.createPending().apply { writeBytes(bytes) }
            assertThrows(IllegalArgumentException::class.java) { store.publish(pending, hash, name) }
            assertTrue(pending.exists())
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun savedBackupSurvivesStoreRecreationAndOpensExactly() {
        val entry = save()
        assertEquals(listOf(entry), BackupStore(directory).list())
        store.open(entry.id).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(hash, entry.sha256)
    }

    @Test fun incompleteAndUnknownFilesAreNeverListed() {
        val pending = store.createPending()
        pending.writeBytes(bytes)
        File(directory, "old-backup.bin").writeBytes(bytes)
        assertTrue(store.list().isEmpty())
        assertTrue(store.discard(pending))
        assertFalse(pending.exists())
    }

    @Test fun shortAndOversizedImagesCannotBePublished() {
        for (size in listOf(0, FlashRestore.FLASH_SIZE - 1, FlashRestore.FLASH_SIZE + 1)) {
            val pending = store.createPending()
            pending.writeBytes(ByteArray(size))
            assertThrows(IOException::class.java) { store.publish(pending, hash) }
            assertTrue(store.list().isEmpty())
        }
    }

    @Test fun savedFileMustMatchDeviceHash() {
        val pending = store.createPending()
        pending.writeBytes(bytes)
        assertThrows(IOException::class.java) { store.publish(pending, "0".repeat(64)) }
        assertTrue(pending.exists())
        assertTrue(store.list().isEmpty())
    }

    @Test fun cancellationBeforePublicationLeavesOnlyHiddenPendingFile() {
        val pending = store.createPending()
        pending.writeBytes(bytes)
        assertThrows(CancellationException::class.java) {
            store.publish(pending, hash) { throw CancellationException() }
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun sameTimeBackupsNeverCollideAndSortDeterministically() {
        val entries = (1..3).map {
            val pending = store.createPending()
            val sameTime = File(directory, pending.name.replace(Regex("[0-9]{13}"), "1700000000000"))
            assertTrue(pending.renameTo(sameTime))
            sameTime.writeBytes(bytes)
            store.publish(sameTime, hash)
        }
        assertEquals(3, entries.map { it.id }.toSet().size)
        assertEquals(entries.sortedByDescending { it.id }, store.list())
    }

    @Test fun newestBackupIsFirst() {
        val newer = save()
        val pending = store.createPending()
        val olderFile = File(directory, pending.name.replace(Regex("[0-9]{13}"), "1600000000000"))
        assertTrue(pending.renameTo(olderFile))
        olderFile.writeBytes(bytes)
        val older = store.publish(olderFile, hash)
        assertEquals(listOf(newer, older), store.list())
    }

    @Test fun corruptedOrTruncatedSavedBackupsAreHiddenAndCannotOpen() {
        val entry = save()
        File(directory, entry.id).writeBytes(ByteArray(bytes.size))
        assertTrue(store.list().isEmpty())
        assertThrows(IOException::class.java) { store.open(entry.id) }
        File(directory, entry.id).writeBytes(bytes.copyOf(bytes.size - 1))
        assertTrue(store.list().isEmpty())
    }

    @Test fun deletingOneBackupPreservesOtherBackups() {
        val first = save()
        val second = save()
        store.delete(first.id)
        assertEquals(listOf(second), store.list())
    }

    @Test fun traversalAndExternalPendingFilesAreRejected() {
        val external = File(root, "outside.bin").apply { writeBytes(bytes) }
        for (id in listOf("../outside.bin", external.absolutePath, "backups/../outside.bin", "x\\outside.bin")) {
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
            assertThrows(IllegalArgumentException::class.java) { store.open(id) }
        }
        val pending = File(root, "fm1-1700000000000-123.partial").apply { writeBytes(bytes) }
        assertThrows(IllegalArgumentException::class.java) { store.publish(pending, hash) }
        assertThrows(IllegalArgumentException::class.java) { store.discard(pending) }
        assertTrue(external.exists())
        assertTrue(pending.exists())
    }

    @Test fun symbolicLinksCannotExposeOrDeleteExternalFiles() {
        val entry = save()
        val original = File(directory, entry.id)
        val external = File(root, "external.bin")
        assertTrue(original.renameTo(external))
        Files.createSymbolicLink(original.toPath(), external.toPath().toAbsolutePath())
        assertTrue(store.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.delete(entry.id) }
        assertThrows(IllegalArgumentException::class.java) { store.open(entry.id) }
        assertTrue(external.exists())
    }
}
