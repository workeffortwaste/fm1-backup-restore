package com.workeffortwaste

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class TransferResultTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val summary = "Backup saved. Verified saved copy.\nSHA-256: ${"ab".repeat(32)}"

    private fun resultIntent() = Intent(context, MainActivity::class.java)
        .putExtra(TransferService.RESULT_SUMMARY, summary)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @Test fun notificationOpensClosableDialogAndDoesNotReplayAfterRecreation() {
        ActivityScenario.launch<MainActivity>(resultIntent()).use { scenario ->
            compose.onNodeWithText("Transfer result").assertIsDisplayed()
            compose.onNodeWithText(summary).assertIsDisplayed()
            scenario.onActivity { assertFalse(it.intent.hasExtra(TransferService.RESULT_SUMMARY)) }

            scenario.recreate()
            compose.onNodeWithText(summary).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Transfer result").assertDoesNotExist()
            compose.onNodeWithText(summary).assertDoesNotExist()

            scenario.recreate()
            compose.onNodeWithText("Transfer result").assertDoesNotExist()
            compose.onNodeWithText(summary).assertDoesNotExist()
        }
    }

    @Test fun notificationAlsoOpensDialogInAlreadyRunningActivity() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            compose.onNodeWithText("Transfer result").assertDoesNotExist()
            scenario.onActivity { it.startActivity(resultIntent()) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Transfer result").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(summary).assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText(summary).assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("Transfer result").assertDoesNotExist()
        }
    }
}
