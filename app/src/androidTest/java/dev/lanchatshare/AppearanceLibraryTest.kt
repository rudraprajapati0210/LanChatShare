package dev.lanchatshare

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AppearanceLibraryTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun themeSurvivesRestartAndLibraryOpens() {
        ui.waitUntil(20000) {
            ui.onAllNodesWithText("Continue").fetchSemanticsNodes(false).isNotEmpty() ||
                ui.onAllNodesWithText("Library").fetchSemanticsNodes(false).isNotEmpty()
        }
        if (ui.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithText("Display name").performTextInput("Mira")
            ui.onNodeWithText("Continue").performClick()
        }
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Appearance").performClick()
        ui.onNodeWithText("Kiln").performScrollTo().performClick()
        ui.onNodeWithText("AMOLED").performScrollTo().performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Kiln").fetchSemanticsNodes(false).isNotEmpty() }
        ui.onNodeWithText("Kiln").assertIsSelected()
        ui.onNodeWithText("AMOLED").assertIsSelected()
        ui.onNodeWithText("Blueprint").performScrollTo().performClick()
        ui.onNodeWithText("Light").performScrollTo().performClick()
        screenshot("appearance")
        ui.onNodeWithText("Estuary").performScrollTo().performClick()
        ui.onNodeWithText("System").performScrollTo().performClick()
        ui.onNodeWithText("Library").performClick()
        ui.onNodeWithText("Find a file").performTextInput("receipt")
        ui.onNodeWithText("List view").performScrollTo().performClick()
        ui.onNodeWithText("Grid view").assertExists()
        ui.onNodeWithText("Share files").performClick()
        ui.onNodeWithText("Original").assertExists()
        screenshot("share")
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val test = InstrumentationRegistry.getInstrumentation()
        val bitmap = test.uiAutomation.takeScreenshot() ?: return
        try { java.io.File(test.targetContext.cacheDir,"$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        } } finally { bitmap.recycle() }
    }
}
