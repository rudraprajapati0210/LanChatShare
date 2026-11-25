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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        val channel = "local_chat"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "LAN connection", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(31, Notification.Builder(this, channel).setSmallIcon(R.drawable.nav_chat)
            .setContentTitle("LanChatShare").setContentText("Available for local chats")
            .setContentIntent(launch).setOngoing(true).build())
        scope.launch { observeOffers(trust) }
        scope.launch {
            try { chat.run() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("LanChatShare", "Local session stopped", e); stopSelf() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
