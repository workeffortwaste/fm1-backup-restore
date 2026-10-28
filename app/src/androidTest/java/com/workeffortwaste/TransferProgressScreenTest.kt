package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class TransferProgressScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparationTransitionsToMeasuredProgress() {
        val progress = mutableStateOf<Float?>(null)
        compose.setContent {
            MaterialExpressiveTheme {
                TransferProgressScreen(
                    FlashOperation.BACKUP,
                    statusMessage("Backing up FM-1", "Reading flash"),
                    progress.value,
                )
            }
        }
        compose.onNodeWithContentDescription("Backup progress").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertExists()
        compose.runOnIdle { progress.value = 0.5f }
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f),
        )).assertExists()
    }

    @Test fun restoreCannotBeCancelledAndSupportsZeroProgress() {
        compose.setContent {
            MaterialExpressiveTheme {
                TransferProgressScreen(
                    FlashOperation.RESTORE,
                    statusMessage("Restoring FM-1", "Writing flash"),
                    0f,
                )
            }
        }
        compose.onNodeWithContentDescription("Restore progress").assertIsDisplayed()
        compose.onNodeWithText("0%").assertIsDisplayed()
        compose.onNodeWithText("Cancel restore").assertDoesNotExist()
        compose.onNodeWithText("Cancel backup").assertDoesNotExist()
    }
}
