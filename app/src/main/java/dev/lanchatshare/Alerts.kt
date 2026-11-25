package dev.lanchatshare

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.AndroidEntryPoint
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalTime
import javax.inject.Inject

internal val Context.alertStore by preferencesDataStore("local_alerts")
internal data class AlertOptions(val messages: Boolean = true, val files: Boolean = true, val transfers: Boolean = true,
    val preview: Boolean = true, val hideLocked: Boolean = true, val quiet: Boolean = false, val from: Int = 22,
    val until: Int = 7, val reachable: Boolean = true, val batterySeen: Boolean = false) {
    fun muted(hour: Int) = quiet && if (from < until) hour in from until until else hour >= from || hour < until
}
internal val Context.alerts get() = alertStore.data.map { p ->
    fun flag(name: String, default: Boolean = true) = p[booleanPreferencesKey(name)] ?: default
    AlertOptions(flag("messages"),flag("files"),flag("transfers"),flag("preview"),flag("hideLocked"),
        flag("quiet",false),p[intPreferencesKey("from")] ?: 22,p[intPreferencesKey("until")] ?: 7,
        flag("reachable"),flag("batterySeen",false))
}
internal suspend fun Context.flag(key: String, value: Boolean) { alertStore.edit { it[booleanPreferencesKey(key)] = value } }
internal val openConversation = MutableStateFlow<String?>(null)
internal val alertPermission = MutableStateFlow(0)
internal fun Context.mayNotify() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
internal fun Context.channels() {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannels(listOf(NotificationChannel("messages","Messages",4),
        NotificationChannel("file_requests","File requests",4),NotificationChannel("transfer_progress","Transfers",2),
        NotificationChannel("local_chat","Connection status",2)))
}
internal fun Context.openPeer(id: String) = PendingIntent.getActivity(this, 0,
    Intent(this, MainActivity::class.java).setAction("dev.lanchatshare.OPEN_CHAT")
        .setData(Uri.Builder().scheme("lanchatshare").authority("chat").appendPath(id).build()),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

private fun avatar(row: UnreadText): Icon {
    val bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = listOf(0xff195950,0xff864728,0xff495b82,0xff75495b)[row.colour.coerceIn(0,3)].toInt()
    canvas.drawCircle(32f,32f,32f,paint)
    paint.color = Color.WHITE; paint.textSize = 32f; paint.textAlign = Paint.Align.CENTER
    canvas.drawText(row.name.take(row.name.offsetByCodePoints(0,1)),32f,32f-(paint.ascent()+paint.descent())/2,paint)
    return Icon.createWithBitmap(bitmap)
}

internal suspend fun Service.observeTexts(trust: TrustStore) {
    val nm = getSystemService(NotificationManager::class.java)
    var posted = nm.activeNotifications.filter { it.id == 42 }.mapNotNull { it.tag }.toSet()
    val previous = mutableMapOf<String, Pair<Long,AlertOptions>>()
    combine(trust.unread, alerts, openConversation, alertPermission) { rows, options, visible, _ ->
        Triple(rows,options,visible)
    }.collect { (rows, options, visible) ->
        val groups = rows.groupBy { it.peerId }
        groups[visible]?.lastOrNull()?.let { trust.markRead(it.peerId,it.sequence) }
        val wanted = if (options.messages && mayNotify()) groups.keys - listOfNotNull(visible).toSet() else emptySet()
        (posted - wanted).forEach { nm.cancel(it,42); previous.remove(it) }
        for (peer in wanted) {
            val messages = groups.getValue(peer)
            val last = messages.last()
            if (previous[peer] == (last.sequence to options)) continue
            val person = Person.Builder().setName(last.name).setKey(peer).setIcon(avatar(last)).build()
            val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
            messages.takeLast(8).forEach { style.addMessage(if (options.preview) it.text else "New message",it.time,person) }
            fun action(kind: String): PendingIntent {
                val intent = Intent(this,MessageAction::class.java).setAction(kind)
                    .setData(Uri.Builder().scheme("lanchatshare").authority("message").appendPath(peer).appendPath(last.sequence.toString()).build())
                val flag = if (kind == "reply" && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
                    else if (kind == "reply") 0 else PendingIntent.FLAG_IMMUTABLE
                return PendingIntent.getBroadcast(this,0,intent,flag or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val builder = Notification.Builder(this,"messages").setSmallIcon(R.drawable.nav_chat)
                .setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).setContentIntent(openPeer(peer))
                .setVisibility(if (options.hideLocked) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC)
                .setPublicVersion(Notification.Builder(this,"messages").setSmallIcon(R.drawable.nav_chat).setContentTitle("New LanChatShare message").build())
                .setAutoCancel(true).addAction(Notification.Action.Builder(null,"Reply",action("reply"))
                    .addRemoteInput(RemoteInput.Builder("text").setLabel("Reply").build()).build())
                .addAction(Notification.Action.Builder(null,"Mark as read",action("read")).build())
            if (options.muted(LocalTime.now().hour)) builder.setGroup("quiet-messages").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            nm.notify(peer,42,builder.build())
            previous[peer] = last.sequence to options
        }
        posted = wanted
    }
}

@AndroidEntryPoint class MessageAction : BroadcastReceiver() {
    @Inject lateinit var trust: TrustStore
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 2) return
        val sequence = parts[1].toLongOrNull() ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == "reply") {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("text")?.toString()?.trim().orEmpty()
                    if (text.isBlank() || text.length > 4096) return@launch
                    trust.enqueue(parts[0],text)
                } else if (intent.action != "read") return@launch
                trust.markRead(parts[0],sequence)
            } catch (_: IllegalArgumentException) { /* Peer was forgotten. */ }
            finally { result.finish() }
        }
    }
}
