package dev.lanchatshare

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ChatUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun trustedPeerOpensFromBothTabsAndQueuedTextSurvivesRecreation() {
        val placeholder = "Test phone"
        val id = UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { ui.activity.trustStore.peers.first() }
        val db = context.openOrCreateDatabase("peers.db", 0, null)
        try {
            db.execSQL("INSERT INTO trusted_peers (id,name,colour,pin) VALUES (?, ?, 0, 'test-only')", arrayOf(id, placeholder))
            ui.activityRule.scenario.recreate()
            ui.waitUntil(20000) {
                ui.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty() ||
                    ui.onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
            }
            if (ui.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) {
                ui.onNodeWithText("Display name").performTextInput("Mira")
                ui.onNodeWithText("Continue").performClick()
            }
            ui.waitUntil(15000) { ui.onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(placeholder).performClick()
            ui.onNodeWithText("Message").performTextInput("See you downstairs")
            ui.onNodeWithText("Send").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("Queued").fetchSemanticsNodes().isNotEmpty() }
            ui.activityRule.scenario.recreate()
            ui.onNodeWithText("See you downstairs").assertIsDisplayed()
            ui.onNodeWithText("Nearby").performClick()
            ui.onNodeWithText("Chat with Test phone").performClick()
            ui.onNodeWithText("See you downstairs").assertIsDisplayed()
        } finally {
            db.delete("messages", "peerId = ?", arrayOf(id))
            db.delete("trusted_peers", "id = ?", arrayOf(id))
            db.close()
        }
    }
}
