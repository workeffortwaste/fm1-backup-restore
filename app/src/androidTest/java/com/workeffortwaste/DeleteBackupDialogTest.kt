package com.workeffortwaste

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
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

    @Test fun pinningOnlyBackupHidesBackupsSectionAndUnpinningShowsItAgain() {
        val name = "Pinned section test"
        createBackup(name)
        assertEquals(1, BackupStore(File(context.filesDir, "backups")).list().size)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.waitUntil(timeoutMillis = 10000) {
                compose.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Backups").assertIsDisplayed()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("Pin", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Pinned").assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Backups").assertDoesNotExist()
            compose.onNodeWithText("No current backups").assertDoesNotExist()
            compose.onNodeWithText(name).performClick()
            compose.onNodeWithText("Unpin", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Backups").assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            compose.onNodeWithText("Pinned").assertDoesNotExist()
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
