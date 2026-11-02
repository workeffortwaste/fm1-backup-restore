package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BootloaderVerificationDialogTest {
    @get:Rule val compose = createComposeRule()

    private val stockHash = "d9f43191777656de01c7bed4f9e9ba2e34e94832c5d2ed02c3fc7ab7a6d9bd67"

    @Test
    fun verifiedStockResultShowsSelectableHashAndCloses() {
        var dismissed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(
                        showDialog = true,
                        sha256 = stockHash,
                        matchesStockV15 = true,
                        message = "Stock v15 bootloader SHA-256 matches the verified official image.",
                    ),
                    { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("Verified").assertIsDisplayed()
        compose.onNodeWithText(stockHash).assertIsDisplayed()
        compose.onNodeWithText("Stock v15 bootloader SHA-256 matches the verified official image.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun verifiedMbootloaderShowsReleaseIdentityAndHashResult() {
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(
                        showDialog = true,
                        sha256 = "c2".repeat(32),
                        identity = "m-bootloader 1.2-22e2559",
                        matchesPublishedRelease = true,
                        message = "Identified as m-bootloader 1.2-22e2559. Reconstructed boot container SHA-256 matches the published release.",
                    ),
                    {},
                )
            }
        }
        compose.onNodeWithText("Verified").assertIsDisplayed()
        compose.onNodeWithText("m-bootloader 1.2-22e2559", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reconstructed boot container SHA-256 matches the published release.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun unrecognizedResultExplainsCustomOrDamagedCouldNotBeDistinguished() {
        val message = "Unrecognized bootloader. It may be custom, corrupted, or another stock version."
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(showDialog = true, sha256 = "a".repeat(64), message = message),
                    {},
                )
            }
        }
        compose.onNodeWithText("Not verified").assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun activeReadShowsProgressWithoutCancelButtons() {
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(showDialog = true, working = true, bytesRead = 4096),
                    {},
                )
            }
        }
        compose.onNodeWithText("Reading bootloader: 25%").assertIsDisplayed()
        compose.onNodeWithText("Bootloader verification").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }
}