package com.workeffortwaste

import android.app.UiModeManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private val updates = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var originalComplete = false
    private var originalAppearance: String? = null
    private var originalPalette: String? = null
    private var originalLastCheck: Long? = null

    @Before fun prepare() {
        originalComplete = preferences.getBoolean("complete", false)
        originalAppearance = preferences.getString("appearance_theme", null)
        originalPalette = preferences.getString("fm1_palette", null)
        originalLastCheck = if (updates.contains("last_check")) updates.getLong("last_check", 0) else null
        preferences.edit(commit = true) {
            putBoolean("complete", true)
            putString("appearance_theme", "material_you")
            putString("fm1_palette", "black")
        }
        updates.edit(commit = true) { putLong("last_check", System.currentTimeMillis()) }
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        }
    }

    @After fun restore() {
        preferences.edit(commit = true) {
            putBoolean("complete", originalComplete)
            putString("appearance_theme", originalAppearance)
            putString("fm1_palette", originalPalette)
        }
        updates.edit(commit = true) {
            val lastCheck = originalLastCheck
            if (lastCheck == null) remove("last_check") else putLong("last_check", lastCheck)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val mode = when {
                originalAppearance == "material_you" -> UiModeManager.MODE_NIGHT_AUTO
                Fm1Palette.fromPreference(originalPalette).isDark -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_NO
            }
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
        }
    }

    private fun withActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            try {
                block(scenario)
            } finally {
                scenario.onActivity { activity ->
                    if (Build.VERSION.SDK_INT >= 31) {
                        activity.splashScreen.setSplashScreenTheme(
                            if (AppearanceTheme.fromPreference(originalAppearance) == AppearanceTheme.FM1) {
                                Fm1Palette.fromPreference(originalPalette).splashTheme
                            } else android.content.res.Resources.ID_NULL,
                        )
                    }
                }
            }
        }
    }

    @Test fun diagnosticsCopyKeepsDialogOpenAndCopiesAllDetails() {
        withActivity { scenario ->
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("USB diagnostics").performClick()
            compose.onNodeWithText("Copy").performClick()
            compose.onNodeWithText("Close").assertIsDisplayed()
            var copied: String? = null
            scenario.onActivity { activity ->
                val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip
                assertEquals("USB diagnostics", clip?.description?.label)
                copied = clip?.getItemAt(0)?.text?.toString()
            }
            assertFalse(copied.isNullOrBlank())
            compose.onNodeWithText(requireNotNull(copied)).assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Copy").assertDoesNotExist()
        }

    }

    @Test fun appIconSelectionWarnsBeforeApplyingAndCanBeCancelled() {
        val originalIcon = LauncherIcon.selected(context)
        val otherIcon = Fm1Palette.entries.first { it != originalIcon }
        withActivity {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNodeWithText("${otherIcon.label} icon").performScrollTo().performClick()
            compose.onNodeWithText("Change app icon?").assertIsDisplayed()
            compose.onNodeWithText("Apply and close").assertIsDisplayed()
            assertEquals(originalIcon, LauncherIcon.selected(context))
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Change app icon?").assertDoesNotExist()
            assertEquals(originalIcon, LauncherIcon.selected(context))
        }
    }

    @Test fun changingThemeDoesNotChangeAppIcon() {
        val originalIcon = LauncherIcon.selected(context)
        withActivity {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNode(hasText("Settings") and hasClickAction()).performClick()
            compose.onNodeWithText("FM-1 Themes").performScrollTo().performClick()
            compose.onNodeWithText("Purple").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("purple", preferences.getString("fm1_palette", null))
            assertEquals(originalIcon, LauncherIcon.selected(context))
            compose.onNodeWithText("Material You").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(originalIcon, LauncherIcon.selected(context))
        }
    }

    @Test fun applyingAppIconClosesActivityAndSurvivesRelaunch() {
        val originalIcon = LauncherIcon.selected(context)
        val otherIcon = Fm1Palette.entries.first { it != originalIcon }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNode(hasText("Settings") and hasClickAction()).performClick()
                compose.onNodeWithText("${otherIcon.label} icon").performScrollTo().performClick()
                compose.onNodeWithText("Apply and close").performClick()
                compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
                assertEquals(otherIcon, LauncherIcon.selected(context))
            }
            withActivity {
                compose.waitForIdle()
                assertEquals(otherIcon, LauncherIcon.selected(context))
            }
        } finally {
            LauncherIcon.update(context, originalIcon)
        }
    }
}
