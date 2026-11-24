package dev.lanchatshare

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun welcomeToChatsThenEditAndRecreate() {
        ui.onNodeWithText("Continue").performClick()
        ui.onNodeWithText("Enter a name with 1 to 24 characters.").assertIsDisplayed()
        ui.onNodeWithText("Display name").performTextInput("  Rudra 🐚  ")
        ui.onNodeWithText("Continue").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Start your first chat").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Start your first chat").assertIsDisplayed()
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Profile").performClick()
        ui.onNodeWithText("Rudra 🐚").performTextReplacement("Mira")
        ui.onNodeWithText("Save changes").performClick()
        ui.waitUntil(10000) { ui.onAllNodes(hasText("Save changes") and !isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Mira").assertIsDisplayed()
    }
}
