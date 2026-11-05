package com.workeffortwaste

import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.Fm1Protocol
import com.workeffortwaste.protocol.RestoreImage
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

    private fun saveLegacy(
        name: String,
        spiSetting: String?,
        trimMarker: Boolean = false,
        metadata: BackupMetadata? = null,
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
            userFamily = DeviceFamily.SMK37,
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
        assertEquals(DeviceFamily.SMK37, restored.taggedFamily)
        assertTrue(restored.familyIsUserTagged)
        assertTrue(restored.familyIsManuallySelected)
        store.open(restored.id).use { assertArrayEquals(bytes, it.readBytes()) }
        assertEquals(hash, restored.sha256)
    }

    @Test fun recoveryBackupWithoutMidiIdentityDoesNotPersistFirmwareTag() {
        val classification = classifyDeviceFamily(null, "4_1_0_0", 0x856014, false)
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

    @Test fun legacyMigrationUsesFlashEvidenceAndPreservesNamesAndImages() {
        val fm1 = saveLegacy("FM-1-looking filename", "4_1_0_0")
        val pocketX = saveLegacy("Old Pocket X", "2_1_0_0")
        val smk = saveLegacy("Snoop37", "4_1_0_0", trimMarker = true)
        val unknown = saveLegacy("FM-1_015", null)
        val custom = saveLegacy(
            "My backup",
            "2_1_0_0",
            metadata = BackupMetadata(
                userFirmwareLabel = "My custom label",
            ),
        )
        val originalHashById = listOf(fm1, pocketX, smk, unknown, custom).associate { it.id to it.sha256 }
        val progress = mutableListOf<Pair<Int, Int>>()

        val upgraded = store.upgradeLegacyFamilies(store.list()) { completed, total ->
            progress += completed to total
        }

        assertEquals(DeviceFamily.FM1, upgraded.single { it.id == fm1.id }.taggedFamily)
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, upgraded.single { it.id == pocketX.id }.taggedFamily)
        assertEquals(DeviceFamily.SMK37, upgraded.single { it.id == smk.id }.taggedFamily)
        assertEquals(DeviceFamily.UNKNOWN, upgraded.single { it.id == unknown.id }.taggedFamily)
        val restoredCustom = upgraded.single { it.id == custom.id }
        assertEquals(DeviceFamily.POCKETX_BLACKBOX, restoredCustom.taggedFamily)
        assertEquals("My custom label", restoredCustom.metadata?.userFirmwareLabel)
        assertEquals((1..5).map { it to 5 }, progress)

        upgraded.forEach { entry ->
            assertEquals(originalHashById.getValue(entry.id), entry.sha256)
            store.open(entry.id).use { assertEquals(FlashRestore.FLASH_SIZE, it.readBytes().size) }
        }
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
                manualFamilySelection = DeviceFamily.SMK37,
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
        assertEquals(3, BackupStore.uniqueExports(entries).size)
        val destination = ByteArrayOutputStream()
        var completed = 0L
        store.exportZip(entries, destination, progress = { processed, total ->
            assertTrue(processed >= completed)
            assertEquals(3L * bytes.size, total)
            completed = processed
        })
        val extracted = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(destination.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertNull(extracted.put(entry.name, zip.readBytes()))
            }
        }
        assertEquals(3L * bytes.size, completed)
        assertEquals(listOf(first, renamed, different).map(BackupStore::exportFileName), extracted.keys.toList())
        assertArrayEquals(bytes, extracted[BackupStore.exportFileName(first)])
        assertArrayEquals(bytes, extracted[BackupStore.exportFileName(renamed)])
        assertArrayEquals(differentBytes, extracted[BackupStore.exportFileName(different)])
        assertEquals(4, store.list().size)
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

    @Test fun legacyBackupsHaveNoAssumedFirmware() {
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
