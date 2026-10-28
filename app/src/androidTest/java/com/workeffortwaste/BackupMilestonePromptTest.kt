package com.workeffortwaste

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupMilestonePromptTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
    private var originalComplete = false
    private var originalBackupCount: Int? = null
    private var originalDismissed: Boolean? = null

    @Before fun prepare() {
        originalComplete = preferences.getBoolean("complete", false)
        originalBackupCount = if (preferences.contains("successful_backups")) {
            preferences.getInt("successful_backups", 0)
        } else null
        originalDismissed = if (preferences.contains("five_backup_prompt_dismissed")) {
            preferences.getBoolean("five_backup_prompt_dismissed", false)
        } else null
        preferences.edit(commit = true) {
            putBoolean("complete", true)
            putInt("successful_backups", 0)
            putBoolean("five_backup_prompt_dismissed", false)
        }
    }

    @After fun restore() {
        preferences.edit(commit = true) {
            putBoolean("complete", originalComplete)
            val backupCount = originalBackupCount
            if (backupCount == null) remove("successful_backups")
            else putInt("successful_backups", backupCount)
            val dismissed = originalDismissed
            if (dismissed == null) remove("five_backup_prompt_dismissed")
            else putBoolean("five_backup_prompt_dismissed", dismissed)
        }
    }

    @Test fun promptWaitsUntilNextAppOpenAndAppearsOnlyOnce() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.onNodeWithText("Thanks for using FM-1 Backup & Restore").assertDoesNotExist()
            repeat(5) {
                assertTrue(BackupMilestonePrompt.recordSuccessfulBackup(preferences))
            }
            compose.onNodeWithText("Thanks for using FM-1 Backup & Restore").assertDoesNotExist()
        }

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.onNodeWithText("Thanks for using FM-1 Backup & Restore").assertIsDisplayed()
            compose.onNodeWithText(
                "Thank you for completing five backups and using this app! " +
                    "Don't worry, this is the only time this pop-up will appear.",
                substring = true,
            ).assertIsDisplayed()
            compose.onNodeWithText("project discussions", substring = true).assertDoesNotExist()
            compose.onNodeWithText("GitHub Sponsors", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Ko-fi", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
        }

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            compose.onNodeWithText("Thanks for using FM-1 Backup & Restore").assertDoesNotExist()
        }
    }
}
