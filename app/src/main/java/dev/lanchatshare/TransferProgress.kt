package dev.lanchatshare

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import dev.lanchatshare.connectivity.TrustStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@OptIn(FlowPreview::class)
internal suspend fun Service.observeProgress(trust: TrustStore) {
    val nm = getSystemService(NotificationManager::class.java)
    val samples = mutableMapOf<String,Pair<Long,Long>>()
    var posted = nm.activeNotifications.filter { it.id == 43 }.mapNotNull { it.tag }.toSet()
    combine(trust.transfers.all,alerts,alertPermission) { rows, options, _ ->
        rows.filter { options.transfers && it.state == "active" && mayNotify() } to options
    }.sample(750).collect { (rows, options) ->
        val current = mutableSetOf<String>()
        for (row in rows) {
            val key = row.peer + "/" + row.id + "/" + row.outgoing
            current.add(key)
            val now = SystemClock.elapsedRealtime()
            val old = samples.put(key,now to row.offset)
            val speed = TransferMeter.rates.value[row.meterKey()]?.let { " · " + it.label(false) } ?: if (old == null) "" else {
                val rate = ((row.offset-old.second).coerceAtLeast(0)*1000.0 / (now-old.first).coerceAtLeast(1)) / 1e6
                " · %.1f MB/s".format(rate)
            }
            val percent = if (row.size == 0L) 0 else (row.offset.toDouble()/row.size*100).toInt().coerceIn(0,100)
            fun action(name: String) = PendingIntent.getBroadcast(this,0,Intent(this,TransferDecision::class.java)
                .setData(Uri.Builder().scheme("lanchatshare").authority("transfer").appendPath(row.peer).appendPath(row.id)
                    .appendPath(name).appendPath(if (row.outgoing) "sent" else "received").build()),PendingIntent.FLAG_IMMUTABLE)
            nm.notify(key,43,Notification.Builder(this,"transfer_progress").setSmallIcon(R.drawable.nav_chat)
                .setContentTitle(if (options.preview) row.name else "File transfer").setContentText("$percent%$speed")
                .setVisibility(if (options.hideLocked) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPeer(row.peer)).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,percent,false)
                .addAction(Notification.Action.Builder(null,"Pause",action("paused")).build())
                .addAction(Notification.Action.Builder(null,"Cancel",action("cancelled")).build()).build())
        }
        (posted-current).forEach { nm.cancel(it,43); samples.remove(it) }
        posted = current
    }
}
