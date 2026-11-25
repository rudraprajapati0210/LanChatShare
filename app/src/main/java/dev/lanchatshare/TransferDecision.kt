package dev.lanchatshare

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import dev.lanchatshare.connectivity.TrustStore
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@AndroidEntryPoint
class TransferDecision : BroadcastReceiver() {
    @Inject lateinit var trust: TrustStore
    override fun onReceive(context: Context, intent: Intent) {
        val (peer, id, decision) = intent.data?.pathSegments?.takeIf { it.size == 3 } ?: return
        if (decision !in listOf("active", "declined")) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val row = trust.transfers.all.first().find { it.peer == peer && it.id == id && !it.outgoing }
                if (row?.state == "offered") trust.transfers.action(row, decision)
            } finally { pending.finish() }
        }
    }
}

internal suspend fun Service.observeOffers(trust: TrustStore) {
    val manager = getSystemService(NotificationManager::class.java)
    val channel = "incoming_files"
    manager.createNotificationChannel(NotificationChannel(channel, "Incoming files", NotificationManager.IMPORTANCE_DEFAULT))
    var shown = emptySet<String>()
    trust.transfers.all.combine(trust.peers) { files, peers ->
        val names = peers.associateBy { it.id }
        files.filter { !it.outgoing && it.state == "offered" && names[it.peer]?.pin == it.pin }
            .associateWith { names.getValue(it.peer).name }
    }.distinctUntilChanged().collect { offers ->
        val current = offers.keys.map { it.peer + "/" + it.id }.toSet()
        (shown - current).forEach { manager.cancel(it, 1) }
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            offers.forEach { (row, name) ->
                val tag = row.peer + "/" + row.id
                if (tag !in shown) {
                    fun decision(state: String): PendingIntent {
                        val uri = Uri.Builder().scheme("lanchatshare").authority("transfer")
                            .appendPath(row.peer).appendPath(row.id).appendPath(state).build()
                        return PendingIntent.getBroadcast(this, 0, Intent(this, TransferDecision::class.java).setData(uri),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    }
                    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    manager.notify(tag, 1, Notification.Builder(this, channel).setSmallIcon(R.drawable.nav_chat)
                        .setContentTitle("$name wants to send a file").setContentText(row.name).setContentIntent(open)
                        .setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "Accept", decision("active")).build())
                        .addAction(Notification.Action.Builder(null, "Decline", decision("declined")).build()).build())
                }
            }
            shown = current
        }
    }
}
