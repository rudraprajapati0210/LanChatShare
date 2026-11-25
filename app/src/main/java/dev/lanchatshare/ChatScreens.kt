package dev.lanchatshare

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.*
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.launch

internal fun peerLabel(peer: TrustedPeer, all: List<TrustedPeer>) =
    if (all.count { it.name == peer.name } > 1) peer.name + " · " + peer.id.takeLast(4).uppercase() else peer.name

@Composable internal fun ChatAccess() {
    val context = LocalContext.current
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    fun allowed() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LifecycleResumeEffect(granted) {
        granted = allowed()
        if (granted) context.startForegroundService(Intent(context, ChatService::class.java))
        onPauseOrDispose {}
    }
    if (!granted) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Allow local network access to exchange messages with your paired phones.")
        TextButton(onClick = {
            if (denied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + context.packageName).toUri()))
            else request.launch(permission)
        }) { Text(if (denied) "Open app settings" else "Allow local network") }
    }
}

@Composable internal fun ChatList(peers: List<TrustedPeer>, trust: TrustStore, chat: LanChat,
    open: (TrustedPeer) -> Unit, nearby: () -> Unit) {
    val previews by trust.latest.collectAsStateWithLifecycle(emptyList())
    val latest = previews.associateBy { it.peerId }
    val online by chat.online.collectAsStateWithLifecycle()
    if (peers.isEmpty()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.nearby_phones), null, Modifier.size(120.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
            Text("Start your first chat", style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = nearby) { Text("Find nearby devices") }
        }
    } else LazyColumn {
        items(peers.sortedByDescending { latest[it.id]?.time ?: 0 }, key = { it.id }) { peer ->
            val last = latest[peer.id]
            ListItem(modifier = Modifier.clickable { open(peer) }, headlineContent = { Text(peerLabel(peer, peers)) },
                supportingContent = { Text(last?.let { (if (it.outgoing) "You: " else "") + it.text } ?: "Start a conversation",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (peer.id in online) Text("Online", color = MaterialTheme.colorScheme.primary) })
        }
    }
}

@Composable internal fun Conversation(peer: TrustedPeer, trust: TrustStore, chat: LanChat, networkSettings: () -> Unit) {
    val history by remember(peer.id) { trust.conversation(peer.id) }.collectAsStateWithLifecycle(emptyList())
    val online by chat.online.collectAsStateWithLifecycle()
    val network by chat.networkStatus.collectAsStateWithLifecycle()
    val diagnostics by chat.diagnostics.collectAsStateWithLifecycle()
    var draft by rememberSaveable(peer.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val latest = history.lastOrNull()
    LaunchedEffect(peer.id, latest?.id, latest?.outgoing) {
        if (latest != null) scroll.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(if (peer.id in online) "Connected over Wi-Fi" else if (network == "On the local network") "Waiting for peer · messages will queue" else network,
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (peer.id !in online && diagnostics[peer.id] != null) {
            Text(diagnostics.getValue(peer.id), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = networkSettings) { Text("Network and hotspot") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, reverseLayout = true,
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(history.asReversed(), key = { it.id + it.outgoing }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.fillMaxWidth(.80f), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                        Surface(shape = RoundedCornerShape(20.dp, 20.dp, if (message.outgoing) 5.dp else 20.dp, if (message.outgoing) 20.dp else 5.dp),
                            color = if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer) {
                            Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                        if (message.outgoing) Text(if (message.delivered) "Delivered" else "Queued", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (error) Text("Couldn’t save the message. Try again.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) draft = it }, modifier = Modifier.weight(1f),
                label = { Text("Message") }, maxLines = 5, enabled = !busy, shape = RoundedCornerShape(18.dp))
            Button(enabled = draft.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try { trust.enqueue(peer.id, draft); draft = ""; error = false }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = true } finally { busy = false }
                }
            }) { Text("Send") }
        }
    }
}
