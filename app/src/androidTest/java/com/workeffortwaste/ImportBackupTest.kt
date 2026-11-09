package com.workeffortwaste

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.RestoreImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Exercises external backup validation and import without connecting to or writing to USB. */
@SdkSuppress(minSdkVersion = 29)
class ImportBackupTest {
    @get:Rule
    val compose = createComposeRule()
    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val resolver = application.contentResolver
    private val documents = mutableListOf<Uri>()
    private val documentDirectory = "Download/FM-1-import-tests-${System.currentTimeMillis()}"
    private lateinit var model: BackupModel
    private var starts = 0
    private var originallyExempt = false

    private fun setBatteryExemption(approved: Boolean) {
        val command = "cmd deviceidle whitelist ${if (approved) "+" else "-"}${application.packageName}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
        compose.waitUntil(timeoutMillis = 5000) {
            application.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(application.packageName) == approved
        }
    }

    @Before
    fun createModel() {
        originallyExempt = application.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(application.packageName)
        setBatteryExemption(true)
        compose.runOnIdle {
            model = BackupModel(application, { starts++; false }, {})
        }
        compose.waitUntil(timeoutMillis = 5000) { !model.state.libraryLoading }
    }

    @After
    fun cleanup() {
        compose.runOnIdle { model.shutdown() }
        documents.forEach { resolver.delete(it, null, null) }
        setBatteryExemption(originallyExempt)
    }

    private fun document(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, documentDirectory)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        documents += uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun select(uri: Uri) {
        compose.runOnIdle { model.selectImportFile(uri) }
        compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
    }

    @Test
    fun importsVerifiedImageWithoutDeviceOrBatteryApproval() {
        val bytes = ByteArray(FlashRestore.FLASH_SIZE) { (it * 17).toByte() }
        setBatteryExemption(false)
        val originalStatus = model.state.status
        select(document("fm-1-20000101-000000.bin", bytes))
        val selected = model.state.selectedImport
        assertNotNull(selected)
        assertEquals("fm-1-20000101-000000.bin", selected?.sourceName)
        assertEquals(
            RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            selected?.sha256,
        )
        assertEquals(originalStatus, model.state.status)
        assertFalse(model.state.ready)
        assertFalse(model.state.showTransferProgress)
        assertEquals(0, starts)

        compose.runOnIdle { model.saveImportedBackup("") }
        assertNotNull(model.state.selectedImport)
        assertTrue(model.firmwareImportState.message.startsWith("File name required"))
        assertEquals(originalStatus, model.state.status)

        val importName = "Imported-${System.currentTimeMillis()}"
        val importStartedAt = System.currentTimeMillis()
        compose.runOnIdle { model.saveImportedBackup(importName) }
        compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
        val importCompletedAt = System.currentTimeMillis()
        val imported = model.state.backups.singleOrNull { it.firmwareIdentity == importName }
        assertNotNull(imported)
        val importedBackup = requireNotNull(imported)
        assertTrue(
            "The library date should come from when the file is imported, not its filename",
            importedBackup.createdAt in importStartedAt..importCompletedAt,
        )
        assertEquals(0, starts)
        assertNull(model.state.selectedImport)
        assertTrue(model.firmwareImportState.completed)
        assertTrue(model.firmwareImportState.message.startsWith("Backup imported"))
        assertEquals(originalStatus, model.state.status)
        val savedIds = model.state.backups.map { it.id }
        select(document("different-name-same-image.bin", bytes))
        assertNull(model.state.selectedImport)
        assertTrue(model.firmwareImportState.completed)
        assertEquals("Not imported. A backup with this SHA-256 is already in the library.",
            model.firmwareImportState.message)
        assertEquals(savedIds, model.state.backups.map { it.id })
        assertEquals(originalStatus, model.state.status)
        compose.runOnIdle { model.deleteBackup(importedBackup.id) }
        compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
    }

    @Test
    fun rejectsWrongSizeAndClearsPreviouslyValidatedSelection() {
        val originalStatus = model.state.status
        select(document("fm-1-good.bin", ByteArray(FlashRestore.FLASH_SIZE)))
        assertNotNull(model.state.selectedImport)
        for (size in listOf(0, FlashRestore.FLASH_SIZE - 1, FlashRestore.FLASH_SIZE + 1)) {
            select(document("fm-1-invalid-$size.bin", ByteArray(size)))
            assertNull(model.state.selectedImport)
            assertTrue(model.firmwareImportState.message.startsWith("Import file rejected"))
            assertEquals(originalStatus, model.state.status)
            assertEquals(0, starts)
        }
    }

    @Test
    fun rejectsNonBinaryFileEvenIfItsLengthMatches() {
        val originalStatus = model.state.status
        select(document("fm-1-not-binary.dat", ByteArray(FlashRestore.FLASH_SIZE)))
        assertNull(model.state.selectedImport)
        assertTrue(model.firmwareImportState.message.startsWith("Import file rejected"))
        assertEquals(originalStatus, model.state.status)
        assertEquals(0, starts)
    }

    @Test
    fun inaccessibleDocumentIsReportedWithoutStartingTransfer() {
        val originalStatus = model.state.status
        val uri = document("fm-1-deleted.bin", ByteArray(FlashRestore.FLASH_SIZE))
        resolver.delete(uri, null, null)
        documents.remove(uri)
        select(uri)
        assertNull(model.state.selectedImport)
        assertTrue(model.firmwareImportState.message.startsWith("Import file rejected"))
        assertEquals(originalStatus, model.state.status)
        assertEquals(0, starts)
    }

    @Test
    fun zipImportAndSingleExportPreserveMetadataWithoutChangingMainInformation() {
        val directory = File(application.cacheDir, "archive-test-${System.nanoTime()}")
        val store = BackupStore(directory)
        val initialIds = model.state.backups.map { it.id }.toSet()
        val originalStatus = model.state.status
        val originalHash = model.state.hash
        val originalProgress = model.state.progress
        val metadata = BackupMetadata(
            classification = classifyDeviceFamily("FM-1_015", "4_1_0_0", 0x856014, false),
            userFamily = DeviceFamily.SMK37_PRO, userFirmwareLabel = "Imported custom tag",
        )
        try {
            val bytes = ByteArray(FlashRestore.FLASH_SIZE) { (it * 31).toByte() }
            val hash = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val entry = store.publishWithMetadata(store.createPending().apply { writeBytes(bytes) }, hash,
                "ZIP test", metadata)
            val archive = ByteArrayOutputStream()
            store.exportBackupZip(entry, archive)
            select(document("backup.zip", archive.toByteArray()))
            val imported = model.state.backups.single { it.id !in initialIds }
            assertEquals(metadata, imported.metadata)
            assertEquals(entry.createdAt, imported.createdAt)
            assertEquals(hash, imported.sha256)
            assertTrue(model.firmwareImportState.completed)
            compose.runOnIdle { model.cancelImport() }

            val destination = document("export.zip", byteArrayOf())
            compose.runOnIdle {
                model.showFirmwareExportDialog(imported.id)
                model.exportAllFirmware(destination)
            }
            compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
            assertTrue(model.firmwareExportState.completed)
            assertTrue(model.firmwareExportState.message.startsWith("Exported 1 backup"))
            val names = mutableListOf<String>()
            requireNotNull(resolver.openInputStream(destination)).use { source ->
                ZipInputStream(source).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        names += item.name
                    }
                }
            }
            assertEquals(listOf(BackupStore.exportFileName(imported),
                BackupStore.exportFileName(imported).removeSuffix(".bin") + ".json"), names)
            val savedIds = model.state.backups.map { it.id }
            select(destination)
            assertEquals("No backups imported. Matching backups are already in the library.",
                model.firmwareImportState.message)
            assertEquals(savedIds, model.state.backups.map { it.id })
            select(document("same-image-no-metadata.bin", bytes))
            assertNull(model.state.selectedImport)
            assertEquals("Not imported. A backup with this SHA-256 is already in the library.",
                model.firmwareImportState.message)
            assertEquals(savedIds, model.state.backups.map { it.id })
            assertEquals(originalStatus, model.state.status)
            assertEquals(originalHash, model.state.hash)
            assertEquals(originalProgress, model.state.progress)
            assertEquals(0, starts)
        } finally {
            directory.deleteRecursively()
            model.state.backups.filter { it.id !in initialIds }.forEach { entry ->
                compose.runOnIdle { model.deleteBackup(entry.id) }
                compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
            }
        }
    }

    @Test
    fun bulkZipImportsAllVerifiedPackages() {
        val directory = File(application.cacheDir, "bulk-archive-test-${System.nanoTime()}")
        val store = BackupStore(directory)
        val initialIds = model.state.backups.map { it.id }.toSet()
        val originalStatus = model.state.status
        try {
            val entries = (1..2).map { seed ->
                val bytes = ByteArray(FlashRestore.FLASH_SIZE) { (it * seed).toByte() }
                val hash = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                store.publish(store.createPending().apply { writeBytes(bytes) }, hash, "Bulk $seed")
            }
            val archive = ByteArrayOutputStream()
            store.exportZip(entries, archive)
            val source = document("all-backups.zip", archive.toByteArray())
            select(source)
            val imported = model.state.backups.filter { it.id !in initialIds }
            assertEquals(entries.map { it.sha256 }.toSet(), imported.map { it.sha256 }.toSet())
            assertEquals("Imported 2 verified backups.", model.firmwareImportState.message)
            val savedIds = model.state.backups.map { it.id }
            select(source)
            assertEquals(savedIds, model.state.backups.map { it.id })
            assertEquals("No backups imported. Matching backups are already in the library.",
                model.firmwareImportState.message)
            assertEquals(originalStatus, model.state.status)
            assertEquals(0, starts)
        } finally {
            directory.deleteRecursively()
            model.state.backups.filter { it.id !in initialIds }.forEach { entry ->
                compose.runOnIdle { model.deleteBackup(entry.id) }
                compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
            }
        }
    }

    @Test
    fun discoveryWaitsForCompletedSetupAndBatteryApproval() {
        val preferences = application.getSharedPreferences("setup", Context.MODE_PRIVATE)
        val originallyComplete = preferences.getBoolean("complete", false)
        try {
            preferences.edit().putBoolean("complete", false).apply()
            compose.runOnIdle { model.refresh() }
            assertFalse(model.state.discoveryActive)
            assertNull(model.state.device)
            preferences.edit().putBoolean("complete", true).apply()
            compose.runOnIdle { model.refresh() }
            assertTrue(model.state.discoveryActive)
            setBatteryExemption(false)
            compose.runOnIdle { model.refresh() }
            assertFalse(model.state.discoveryActive)
            assertFalse(model.state.ready)
            setBatteryExemption(true)
            compose.runOnIdle { model.refresh() }
            assertTrue(model.state.discoveryActive)
            assertEquals(0, starts)
        } finally {
            preferences.edit().putBoolean("complete", originallyComplete).apply()
        }
    }
}
