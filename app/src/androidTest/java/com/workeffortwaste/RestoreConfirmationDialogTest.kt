package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Checks that restore details are shown and that confirmation remains a separate, gated action. */
class RestoreConfirmationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val hash = "bc5d2317a8e09f624d1c73ab906fe812c49a0bd6753e18f29a647cd0285b93ef"

    @Test fun savedBackupKeepsDetailsAndSeparateConfirmAndDismissActions() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                RestoreConfirmationDialog(
                    "Factory firmware", "27 Oct 2026, 16:00", hash, true,
                    { confirmed++ }, { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("Factory firmware").assertIsDisplayed()
        compose.onNodeWithText("27 Oct 2026, 16:00").assertIsDisplayed()
        compose.onNodeWithText(hash).assertIsDisplayed()
        compose.onNodeWithText("No, go back").performClick()
        compose.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
        compose.onNodeWithText("Yes, restore").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test fun smkRestoreRequiresFreshSameUnitAcknowledgement() {
        var confirmed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                RestoreConfirmationDialog(
                    backupName = "SMK snapshot",
                    backupDate = null,
                    sha256 = hash,
                    restoreEnabled = true,
                    targetFamily = DeviceFamily.SMK37,
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText(
            "Only restore an SMK-37 backup to the exact physical keyboard it was created with, due to device specific calibration data."
        ).assertIsDisplayed()
        compose.onNodeWithText("The first 16 KB bootloader area is not overwritten.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yes, restore").assertIsNotEnabled()
        compose.onNodeWithText("I confirm this backup was created with this exact physical SMK-37.")
            .performClick()
        compose.onNodeWithText("Yes, restore").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

}
