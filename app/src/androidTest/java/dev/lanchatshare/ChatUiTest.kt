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
            ui.waitUntil(15000) { ui.onAllNodesWithText("See you downstairs").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("See you downstairs").assertIsDisplayed()
            ui.onNodeWithText("Nearby").performClick()
            ui.onNodeWithText("Chat with Test phone").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("See you downstairs").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("See you downstairs").assertIsDisplayed()
            val trust = ui.activity.trustStore
            runBlocking { repeat(36) { trust.enqueue(id, "Older note $it") } }
            ui.waitUntil(15000) { ui.onAllNodesWithText("Older note 35").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasScrollToIndexAction()).performScrollToIndex(34)
            db.execSQL("INSERT INTO messages(peerId,outgoing,id,text,time,delivered) VALUES(?,0,?,?,?,1)",
                arrayOf<Any>(id, UUID.randomUUID().toString(), "New incoming message", System.currentTimeMillis() + 60000))
            runBlocking { trust.enqueue(id, "Refresh conversation") }
            ui.waitUntil(15000) { ui.onAllNodesWithText("New incoming message").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("New incoming message").assertIsDisplayed()
            ui.onNodeWithText("Photo").assertIsDisplayed()
            ui.onNodeWithText("Share files").performClick()
            ui.onNodeWithText("Transfers").assertIsDisplayed()
        } finally {
            db.delete("messages", "peerId = ?", arrayOf(id))
            db.delete("trusted_peers", "id = ?", arrayOf(id))
            db.close()
        }
    }
}
