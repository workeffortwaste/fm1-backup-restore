package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Verifies explicit download/install steps and UI behavior while work or installer handoff is active. */
class AppUpdateUiTest {
    @get:Rule val compose = createComposeRule()

    private val release = AppRelease(
        "v0.2", "Test release notes", "https://github.com/example.apk", 1024 * 1024, "ab".repeat(32),
    )

    @Test fun requiresSeparateDownloadAndInstallActions() {
        val state = mutableStateOf(AppUpdateState(release = release, showDialog = true))
        var downloads = 0
        var installs = 0
        compose.setContent {
            MaterialExpressiveTheme {
                AppUpdateDialog(
                    state.value, true, false, {}, { downloads++ }, { installs++ },
                )
            }
        }
        compose.onNodeWithText("Test release notes").assertIsDisplayed()
        compose.onNodeWithText("Download: 1.0 MiB").assertIsDisplayed()
        compose.onNodeWithText("Download update").performClick()
        compose.runOnIdle {
            assertEquals(1, downloads)
            assertEquals(0, installs)
            state.value = state.value.copy(apk = File("verified.apk"))
        }
        compose.onNodeWithText("Install update").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun defersPromptDuringDeviceWorkAndDisablesActionsDuringDownload() {
        val idle = mutableStateOf(false)
        val state = mutableStateOf(AppUpdateState(release = release, showDialog = true))
        compose.setContent {
            MaterialExpressiveTheme {
                AppUpdateDialog(state.value, idle.value, false, {}, {}, {})
            }
        }
        compose.onNodeWithText("Update v0.2").assertDoesNotExist()
        compose.runOnIdle { idle.value = true }
        compose.onNodeWithText("Update v0.2").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(downloading = true, progress = 42) }
        compose.onNodeWithText("Downloading: 42%").assertIsDisplayed()
        compose.onNodeWithText("Download update").assertIsNotEnabled()
        compose.onNodeWithText("Later").assertIsNotEnabled()
    }

    @Test fun installationHandoffCannotBeRepeatedAndFailuresAreVisible() {
        val installerOpen = mutableStateOf(true)
        val state = AppUpdateState(
            release = release, apk = File("verified.apk"), showDialog = true,
            message = "Could not open update installer: installation permission denied.",
        )
        compose.setContent {
            MaterialExpressiveTheme {
                AppUpdateDialog(state, true, installerOpen.value, {}, {}, {})
            }
        }
        compose.onNodeWithText(state.message).assertIsDisplayed()
        compose.onNodeWithText("Install update").assertIsNotEnabled()
        compose.onNodeWithText("Later").assertIsNotEnabled()
        compose.runOnIdle { installerOpen.value = false }
        compose.onNodeWithText("Install update").performClick()
    }
}
