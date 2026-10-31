package com.workeffortwaste

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Test release notes").fetchSemanticsNodes().isNotEmpty()
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

    @Test fun releaseNotesRenderMarkdownAndOpenLinks() {
        var openedUri = ""
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) { openedUri = uri }
        }
        val notes = """
            ## Changes

            **Bold** and *italic* text.

            - Fixed backups

            [Read more](https://github.com/workeffortwaste)
        """.trimIndent()
        compose.setContent {
            MaterialExpressiveTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    AppUpdateDialog(
                        AppUpdateState(release = release.copy(notes = notes), showDialog = true),
                        true, false, {}, {}, {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Read more").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Changes").assertIsDisplayed()
        compose.onNodeWithText("Bold and italic text.").assertIsDisplayed()
        compose.onNodeWithText("Fixed backups").assertIsDisplayed()
        compose.onNodeWithText("## Changes").assertDoesNotExist()
        compose.onNodeWithText("**Bold** and *italic* text.").assertDoesNotExist()
        compose.onNodeWithText("Read more").performTouchInput { click(Offset(12f, center.y)) }
        compose.runOnIdle { assertEquals("https://github.com/workeffortwaste", openedUri) }
    }

    @Test fun blankReleaseNotesKeepFallbackMessage() {
        compose.setContent {
            MaterialExpressiveTheme {
                AppUpdateDialog(
                    AppUpdateState(release = release.copy(notes = "  "), showDialog = true),
                    true, false, {}, {}, {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No release notes provided.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No release notes provided.").assertIsDisplayed()
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
