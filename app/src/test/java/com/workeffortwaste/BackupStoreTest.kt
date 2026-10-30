package com.workeffortwaste

import com.workeffortwaste.protocol.FlashRestore
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

    @Test fun exportFilenameUsesVisibleNameAndAppendsHash() {
        val entry = save().copy(firmwareIdentity = "My FM-1")
        assertEquals("my-fm-1-$hash.bin", BackupStore.exportFileName(entry))
        assertEquals("fm-1-$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "FM/1")))
        assertEquals("m-vave-$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "M-VÁVE")))
        assertEquals("firmware-unknown-$hash.bin", BackupStore.exportFileName(entry.copy(firmwareIdentity = "***")))
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
        assertNull(BackupStore(directory).list().single().firmwareIdentity)
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
