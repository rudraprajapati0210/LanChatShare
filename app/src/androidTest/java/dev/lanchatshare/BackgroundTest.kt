package dev.lanchatshare

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BackgroundTest {
    @Test fun backgroundTextPostsAndReadActionClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val peer = UUID.randomUUID().toString()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var trust: dev.lanchatshare.connectivity.TrustStore
            scenario.onActivity {
                trust = it.trustStore
                it.startForegroundService(Intent(it, ChatService::class.java))
            }
            runBlocking { trust.peers.first() }
            context.openOrCreateDatabase("peers.db", Context.MODE_PRIVATE, null).use { db ->
                try {
                    db.execSQL("INSERT INTO trusted_peers(id,name,colour,pin) VALUES(?,?,0,'local-test')", arrayOf(peer,"Test phone"))
                    val columns = db.rawQuery("SELECT * FROM messages LIMIT 0", null).use { it.columnNames }
                    val unread = if ("unread" in columns) ",unread" else ""
                    val extra = if (unread.isEmpty()) "" else ",1"
                    db.execSQL("INSERT INTO messages(peerId,outgoing,id,text,time,delivered$unread) VALUES(?,0,?,?,1,1$extra)",
                        arrayOf(peer,UUID.randomUUID().toString(),"Hello from the LAN"))
                    // Refresh Room after the fixture writes through a second connection.
                    runBlocking { trust.enqueue(peer,"Fixture refresh") }
                    runBlocking { delay(1500) }
                    Log.i("LanAcceptance","Saved incoming text, channels=" + manager.notificationChannels.map { it.id } +
                        " posted=" + manager.activeNotifications.map { it.notification.channelId })
                    val notification = runBlocking {
                        withTimeout(8000) {
                            while (manager.activeNotifications.none { it.tag == peer }) delay(100)
                            manager.activeNotifications.first { it.tag == peer }.notification
                        }
                    }
                    assertTrue(notification.actions.any { !it.remoteInputs.isNullOrEmpty() })
                    val reply = notification.actions.single { it.title == "Reply" }
                    val response = Intent()
                    android.app.RemoteInput.addResultsToIntent(reply.remoteInputs,response,android.os.Bundle().apply { putCharSequence("text","From notification") })
                    reply.actionIntent.send(context,0,response)
                    runBlocking { withTimeout(5000) { trust.conversation(peer).first { rows -> rows.any { it.outgoing && it.text == "From notification" } } } }
                    notification.actions.single { it.title == "Mark as read" }.actionIntent.send()
                    runBlocking { withTimeout(5000) { while (manager.activeNotifications.any { it.tag == peer }) delay(100) } }
                } finally {
                    db.delete("messages","peerId=?",arrayOf(peer)); db.delete("trusted_peers","id=?",arrayOf(peer))
                    context.stopService(Intent(context,ChatService::class.java))
                }
            }
        }
    }
}
