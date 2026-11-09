package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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

    @Test
    fun debugBuildAllowsKnownHashRecoveryButNotAfterDisconnect() {
        var connected by mutableStateOf(true)
        var requested = 0
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(showDialog = true, sha256 = stockHash, matchesStockV15 = true),
                    {}, recoveryConnected = connected, onEmergencyRecovery = { requested++ },
                    debugRecoveryOverride = true,
                )
            }
        }
        compose.onNodeWithText("Emergency restore").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, requested)
            connected = false
        }
        compose.onNodeWithText("Emergency restore").assertIsNotEnabled()
    }

    @Test
    fun emergencyButtonIsDisabledForKnownHashAndAfterConnectionIsLost() {
        var state by mutableStateOf(
            BootloaderVerificationState(showDialog = true, sha256 = stockHash, matchesStockV15 = true),
        )
        var connected by mutableStateOf(true)
        var requested = 0
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    state, {}, recoveryConnected = connected, onEmergencyRecovery = { requested++ },
                    debugRecoveryOverride = false,
                )
            }
        }
        compose.onNodeWithText("Emergency restore").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(matchesStockV15 = false, identity = "m-bootloader unknown", matchesPublishedRelease = false)
        }
        compose.onNodeWithText("Emergency restore").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, requested)
            connected = false
        }
        compose.onNodeWithText("Emergency restore").assertIsNotEnabled()
        compose.runOnIdle {
            connected = true
            state = state.copy(matchesPublishedRelease = true)
        }
        compose.onNodeWithText("Emergency restore").assertIsNotEnabled()
    }

    @Test
    fun verifiedV16RecoveryConfirmationWarnsThatItWillBeReplacedByV15() {
        val state = BootloaderVerificationState(
            showDialog = true,
            sha256 = "8f5228c2d6ea2068838ef4916f946bea877d14b07e6cfca49e41957f4c7b234f",
            matchesStockV16 = true,
            recovery = EmergencyRecoveryState(stage = EmergencyRecoveryStage.CONFIRM),
        )
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(state, {}, recoveryConnected = true)
            }
        }

        compose.onNodeWithText("This device has a verified stock v16 bootloader.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Proceed at my own risk").assertIsEnabled()
    }

    @Test
    fun sameDialogChangesFromDownloadToConfirmationAndBlocksProceedOnDisconnect() {
        var state by mutableStateOf(BootloaderVerificationState(showDialog = true, sha256 = "a".repeat(64)))
        var connected by mutableStateOf(true)
        var proceeded = 0
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    state, {}, recoveryConnected = connected,
                    onEmergencyRecovery = {
                        state = state.copy(working = true, recovery = EmergencyRecoveryState())
                    },
                    onProceedRecovery = { proceeded++ },
                )
            }
        }
        compose.onNodeWithText("Emergency restore").performClick()
        compose.onNodeWithText("Emergency Recover").assertIsDisplayed()
        compose.onNodeWithText("Downloading stock bootloader").assertIsDisplayed()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Proceed at my own risk").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(working = false, recovery = EmergencyRecoveryState(stage = EmergencyRecoveryStage.CONFIRM))
        }
        compose.onNodeWithText("Proceed at my own risk").assertIsEnabled()
        compose.runOnIdle { connected = false }
        compose.onNodeWithText("Proceed at my own risk").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, proceeded) }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun flashingHasNoDismissActionAndCompletionShowsClose() {
        var state by mutableStateOf(
            BootloaderVerificationState(
                showDialog = true, working = true,
                recovery = EmergencyRecoveryState(
                    stage = EmergencyRecoveryStage.FLASHING,
                    phase = "Verifying restored bootloader", progress = 0.5f,
                ),
            ),
        )
        var closed = 0
        compose.setContent {
            MaterialExpressiveTheme { BootloaderVerificationDialog(state, { closed++ }) }
        }
        compose.onNodeWithText("Emergency Recover").assertIsDisplayed()
        compose.onNodeWithText("Verifying restored bootloader").assertIsDisplayed()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onNodeWithText("Proceed at my own risk").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(
                working = false,
                recovery = EmergencyRecoveryState(stage = EmergencyRecoveryStage.SUCCESS, message = "Restored and verified."),
            )
        }
        compose.onNodeWithText("Restored and verified.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun failedDownloadShowsRequiredErrorWithoutProceed() {
        compose.setContent {
            MaterialExpressiveTheme {
                BootloaderVerificationDialog(
                    BootloaderVerificationState(
                        showDialog = true,
                        recovery = EmergencyRecoveryState(
                            stage = EmergencyRecoveryStage.FAILURE,
                            message = "Failed to download recovery data from M-VAVE repo.",
                        ),
                    ),
                    {},
                )
            }
        }
        compose.onNodeWithText("Emergency Recover").assertIsDisplayed()
        compose.onNodeWithText("Failed to download recovery data from M-VAVE repo.").assertIsDisplayed()
        compose.onNodeWithText("Proceed at my own risk").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}