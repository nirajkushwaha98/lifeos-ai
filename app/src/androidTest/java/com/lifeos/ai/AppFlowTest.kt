package com.lifeos.ai

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI flow on a real device/emulator (English locale):
 * onboarding -> login validation -> guest mode -> create a task -> it appears in the list.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    // Run `adb shell pm clear com.lifeos.ai` before this test to exercise onboarding + login too;
    // the test also passes when the app is already in guest mode.
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test fun guestCanCreateTaskEndToEnd() {
        val home = hasText("Ask LifeOS AI")
        rule.waitUntilAtLeastOneExists(hasText("Skip").or(hasText("Welcome back")).or(home), 15_000)
        if (exists("Skip")) rule.onNodeWithText("Skip").performClick()

        if (!exists("Ask LifeOS AI")) {
            // Login form validation
            rule.waitUntilAtLeastOneExists(hasText("Welcome back"), 10_000)
            rule.onNodeWithText("Log in").performClick()
            rule.onNodeWithText("Email is required").assertExists()
            rule.onNodeWithText("Password is required").assertExists()

            // Guest mode -> Home dashboard
            rule.onNodeWithText("Continue as guest").performClick()
        }
        rule.waitUntilAtLeastOneExists(home, 10_000)

        // Quick action -> task editor; saving empty shows validation
        rule.onNodeWithText("Add Task").performClick()
        rule.waitUntilAtLeastOneExists(hasText("New task"), 5_000)
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Title is required").assertExists()

        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Pay electricity bill")
        rule.onNodeWithText("Save").performClick()

        // Back on Home; open Tasks list and verify the persisted task is shown
        rule.waitUntilAtLeastOneExists(hasText("Ask LifeOS AI"), 5_000)
        rule.onAllNodesWithText("See all").onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasText("Pay electricity bill"), 5_000)
        rule.onAllNodes(hasContentDescription("Mark as complete")).onFirst().performClick()
        rule.waitUntilAtLeastOneExists(hasText("tasks completed", substring = true), 5_000)
    }
}
