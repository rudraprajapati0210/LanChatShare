package dev.lanchatshare

import android.app.*
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.lanchatshare.connectivity.LanChat
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class ChatService : Service() {
    @Inject lateinit var chat: LanChat
    @Inject lateinit var trust: dev.lanchatshare.connectivity.TrustStore
    companion object {
        internal val running = kotlinx.coroutines.flow.MutableStateFlow(false)
        internal val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        channels()
        val channel = "local_chat"
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(31, Notification.Builder(this, channel).setSmallIcon(R.drawable.nav_chat)
            .setContentTitle("LanChatShare").setContentText("Available for local chats")
            .setContentIntent(launch).setOngoing(true).build())
        running.value = true
        scope.launch { holdTransferPower(trust.transfers.activeOperations) }
        scope.launch {
            kotlinx.coroutines.flow.combine(alerts,foreground,trust.transfers.all) { opts, visible, rows ->
                !opts.reachable && !visible && rows.none { it.state == "active" || it.state == "retrying" }
            }.collect { if (it) stopSelf() }
        }
        scope.launch { observeTexts(trust) }
        scope.launch { TransferMeter.observe(applicationContext,trust.transfers,chat) }
        scope.launch { observeProgress(trust) }
        scope.launch { observeOffers(trust) }
        scope.launch {
            try { chat.run() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("LanChatShare", "Local session stopped", e); stopSelf() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        running.value = false
        scope.cancel()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.filter { it.id == 43 }.forEach { notifications.cancel(it.tag, it.id) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
