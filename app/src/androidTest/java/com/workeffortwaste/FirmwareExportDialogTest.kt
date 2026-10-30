package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FirmwareExportDialogTest {
    @get:Rule val compose = createComposeRule()
    private val entry = BackupEntry("test", 0, "a".repeat(64), "Factory firmware")

    @Test fun confirmationSeparatesExportFromDismissal() {
        var exported = 0
        var dismissed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                FirmwareExportDialog(
                    FirmwareExportState(showDialog = true, entries = listOf(entry)),
                    true, { dismissed++ }, {}, { exported++ },
                )
            }
        }
        compose.onNodeWithText("1 unique backup as a ZIP archive.").assertIsDisplayed()
        compose.onNodeWithText("Export").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, exported)
            assertEquals(1, dismissed)
        }
    }

    @Test fun workingExportShowsProgressThenCancellationAndResult() {
        val state = mutableStateOf(FirmwareExportState(showDialog = true, working = true, progress = 42))
        var cancelled = 0
        var dismissed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                FirmwareExportDialog(state.value, false, { dismissed++ }, {
                    cancelled++
                    state.value = state.value.copy(cancelling = true)
                }, {})
            }
        }
        compose.onNodeWithText("Exporting backups: 42%").assertIsDisplayed()
        compose.onNodeWithText("Export").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Cancelling export").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, dismissed)
            state.value = state.value.copy(
                working = false, cancelling = false, completed = true,
                message = "Export cancelled. The incomplete ZIP was removed.",
            )
        }
        compose.onNodeWithText("Export cancelled. The incomplete ZIP was removed.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun exportIsDisabledWhileSavePickerIsOpen() {
        compose.setContent {
            MaterialExpressiveTheme {
                FirmwareExportDialog(
                    FirmwareExportState(showDialog = true, entries = listOf(entry)),
                    false, {}, {}, {},
                )
            }
        }
        compose.onNodeWithText("Export").assertIsNotEnabled()
    }
}