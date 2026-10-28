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

/** Exercises Android document selection, restore-image validation, and discovery eligibility without writing to USB. */
@SdkSuppress(minSdkVersion = 29)
class ExternalRestoreTest {
    @get:Rule
    val compose = createComposeRule()
    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val resolver = application.contentResolver
    private val documents = mutableListOf<Uri>()
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
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FM-1-restore-tests")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        documents += uri
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun select(uri: Uri) {
        compose.runOnIdle { model.selectRestoreFile(uri) }
        compose.waitUntil(timeoutMillis = 10000) { !model.state.busy }
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

    @Test
    fun validatesExternalBinaryWithoutDeviceOrStartingTransfer() {
        val bytes = ByteArray(FlashRestore.FLASH_SIZE) { (it * 17).toByte() }
        val uri = document("fm-1-valid.bin", bytes)
        select(uri)
        val selected = model.state.selectedRestore
        assertNotNull(selected)
        assertEquals("fm-1-valid.bin", selected?.name)
        assertEquals(RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes)), selected?.sha256)
        assertFalse(model.state.ready)
        assertFalse(model.state.showTransferProgress)
        assertEquals(0, starts)
        compose.runOnIdle { model.restoreSelectedFile() }
        assertEquals(0, starts)
        assertFalse(model.state.busy)
    }

    @Test
    fun rejectsWrongSizeAndClearsPreviouslyValidatedSelection() {
        select(document("fm-1-good.bin", ByteArray(FlashRestore.FLASH_SIZE)))
        assertNotNull(model.state.selectedRestore)
        for (size in listOf(0, FlashRestore.FLASH_SIZE - 1, FlashRestore.FLASH_SIZE + 1)) {
            select(document("fm-1-invalid-$size.bin", ByteArray(size)))
            assertNull(model.state.selectedRestore)
            assertTrue(model.state.status.isNotEmpty)
            assertEquals(0, starts)
        }
    }

    @Test
    fun rejectsNonBinaryFileEvenIfItsLengthMatches() {
        select(document("fm-1-not-binary.dat", ByteArray(FlashRestore.FLASH_SIZE)))
        assertNull(model.state.selectedRestore)
        assertTrue(model.state.status.isNotEmpty)
        assertEquals(0, starts)
    }

    @Test
    fun inaccessibleDocumentIsReportedWithoutStartingTransfer() {
        val uri = document("fm-1-deleted.bin", ByteArray(FlashRestore.FLASH_SIZE))
        resolver.delete(uri, null, null)
        documents.remove(uri)
        select(uri)
        assertNull(model.state.selectedRestore)
        assertEquals("Restore file rejected", model.state.status.heading)
        assertEquals(0, starts)
    }

    @Test
    fun missingBatteryExemptionBlocksEveryBackupAndRestoreEntryPoint() {
        val uri = document("fm-1-battery-gated.bin", ByteArray(FlashRestore.FLASH_SIZE))
        setBatteryExemption(false)
        val actions: List<() -> Unit> = listOf(
            { model.backup() },
            { model.selectRestoreFile(uri) },
            { model.restoreSelectedFile() },
            { model.restoreSavedBackup("not-selected") },
        )
        for (action in actions) {
            compose.runOnIdle { action() }
            assertFalse(model.state.busy)
            assertNull(model.state.selectedRestore)
            assertTrue(model.state.status.heading.contains("Backup and restore are blocked"))
            assertEquals(0, starts)
        }
        setBatteryExemption(true)
        select(uri)
        assertNotNull(model.state.selectedRestore)
        setBatteryExemption(false)
        compose.runOnIdle { model.restoreSelectedFile() }
        assertTrue(model.state.status.heading.contains("Backup and restore are blocked"))
        assertFalse(model.state.busy)
        assertEquals(0, starts)
    }
}
