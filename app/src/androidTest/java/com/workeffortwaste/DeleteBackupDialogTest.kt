package com.workeffortwaste

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.workeffortwaste.protocol.FlashRestore
import com.workeffortwaste.protocol.RestoreImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

class DeleteBackupDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val updates = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var originalComplete = false
    private var originalLastCheck: Long? = null
    private var backup: File? = null

    @Before fun prepare() {
        originalComplete = preferences.getBoolean("complete", false)
        originalLastCheck = if (updates.contains("last_check")) updates.getLong("last_check", 0) else null
        preferences.edit(commit = true) { putBoolean("complete", true) }
        updates.edit(commit = true) { putLong("last_check", System.currentTimeMillis()) }
    }

    @After fun cleanup() {
        backup?.let { assertTrue(!it.exists() || it.delete()) }
        preferences.edit(commit = true) { putBoolean("complete", originalComplete) }
        updates.edit(commit = true) {
            val lastCheck = originalLastCheck
            if (lastCheck == null) remove("last_check") else putLong("last_check", lastCheck)
        }
    }

    @Test fun renamedBackupShowsCurrentNameAndDateAndCancelsWithoutDeleting() {
        checkDialog("Original firmware", "My factory backup", delete = false)
    }

    @Test fun unnamedBackupShowsLibraryFallbackAndDeletesOnlyAfterConfirmation() {
        checkDialog(null, "Firmware unknown", delete = true)
    }

    @Test fun emptyLibraryOffersSnapshotAndOpensExistingModal() {
        assertTrue(BackupStore(File(context.filesDir, "backups")).list().isEmpty())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodes(hasText("Already started flashing custom firmware?"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Generate a stock v15 snapshot even if you didn't save a backup first.")
                .assertIsDisplayed()
            compose.onNodeWithText("Create v15 recovery snapshot").performClick()
            compose.onNode(hasText("Create v15 recovery snapshot") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
            compose.onNodeWithText("Download and build").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Download and build").assertDoesNotExist()
        }
    }

    @Test fun mainMenuOpensFirmwareExportModalAndCancelPreservesLibrary() {
        val name = "Export modal test"
        createBackup(name)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Export all backups").performClick()
            compose.onNode(hasText("Export all backups") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("1 unique backup as a ZIP archive.").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Export all backups").assertDoesNotExist()
            assertTrue(requireNotNull(backup).exists())
        }
    }

    @Test fun verifyBootloaderActionIsEnabledOnlyWhenConnectedAndOpensResult() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var model: BackupModel? = null
            compose.waitUntil(timeoutMillis = 10000) {
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[TransferConnection::class.java].model
                }
                model != null
            }
            val target = requireNotNull(model)
            val state = backupState(target)
            val original = target.state
            try {
                compose.runOnIdle {
                    state.value = original.copy(
                        ready = false,
                        libraryLoading = false,
                        backups = emptyList(),
                        pinnedBackupIds = emptySet(),
                        status = statusMessage("FM-1 successfully connected", "Ready to backup or restore full flash"),
                    )
                }
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNodeWithText("Diagnostics").performClick()
                compose.onNodeWithText("Verify bootloader").assertIsNotEnabled()
                compose.runOnIdle {
                    state.value = state.value.copy(ready = true, deviceMode = DeviceMode.DOWNLOAD)
                }
                compose.onNodeWithText("Verify bootloader").performClick()
                compose.onNodeWithText("Diagnostics").assertDoesNotExist()
                compose.waitUntil(timeoutMillis = 5000) {
                    compose.onAllNodes(hasText("Bootloader verification")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitUntil(timeoutMillis = 5000) {
                    compose.onAllNodes(hasText("Could not verify the bootloader: Connect to the FM-1 first"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Could not verify the bootloader: Connect to the FM-1 first").assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(
                        statusMessage("FM-1 successfully connected", "Ready to backup or restore full flash"),
                        state.value.status,
                    )
                }
            } finally {
                compose.runOnIdle { state.value = original }
            }
        }
    }

    private fun backupState(model: BackupModel): MutableState<BackupState> {
        val delegate = BackupModel::class.java.getDeclaredField("state\$delegate")
            .apply { isAccessible = true }
            .get(model)
        @Suppress("UNCHECKED_CAST")
        return delegate as MutableState<BackupState>
    }

    @Test fun pinningOnlyBackupHidesBackupsSectionAndUnpinningShowsItAgain() {
        val name = "Pinned section test"
        createBackup(name)
        assertEquals(1, BackupStore(File(context.filesDir, "backups")).list().size)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Backups").assertIsDisplayed()
            compose.onNodeWithText("Already started flashing custom firmware?").assertDoesNotExist()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("Pin", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Pinned").assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Backups").assertDoesNotExist()
            compose.onNodeWithText("Already started flashing custom firmware?").assertDoesNotExist()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("Unpin", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Backups").assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Pinned").assertDoesNotExist()
            compose.onNodeWithText("Already started flashing custom firmware?").assertDoesNotExist()
        }
    }

    private fun createBackup(initialName: String?): BackupEntry {
        val store = BackupStore(File(context.filesDir, "backups"))
        val bytes = ByteArray(FlashRestore.FLASH_SIZE)
        val hash = RestoreImage.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val pending = store.createPending()
        backup = pending
        pending.writeBytes(bytes)
        val entry = store.publish(pending, hash, initialName)
        backup = File(context.filesDir, "backups/${entry.id}")
        return entry
    }

    private fun checkDialog(initialName: String?, displayedName: String, delete: Boolean) {
        val store = BackupStore(File(context.filesDir, "backups"))
        var entry = createBackup(initialName)
        if (initialName != null) {
            entry = store.rename(entry.id, displayedName)
            backup = File(context.filesDir, "backups/${entry.id}")
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(Date(entry.createdAt))
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodes(hasText(displayedName))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(displayedName).performClick()
            compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Delete this backup?").assertIsDisplayed()
            compose.onNode(hasText(displayedName) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNode(hasText(date) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText(
                "This permanently deletes the private copy from this app. Exported copies are not deleted.",
            ).assertIsDisplayed()
            assertTrue(requireNotNull(backup).exists())
            compose.onNodeWithText(if (delete) "Delete backup" else "Cancel").performClick()
            compose.onNodeWithText("Delete this backup?").assertDoesNotExist()
            if (delete) {
                compose.waitUntil(timeoutMillis = 10000) { !requireNotNull(backup).exists() }
                assertFalse(requireNotNull(backup).exists())
            } else {
                assertTrue(requireNotNull(backup).exists())
            }
        }
    }
}
