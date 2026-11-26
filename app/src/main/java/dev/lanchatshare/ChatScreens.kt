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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.animateContentSize
import dev.lanchatshare.design.LocalAppMotion
import dev.lanchatshare.design.LocalWallpaper
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

internal data class ContactTransition(val shared: androidx.compose.animation.SharedTransitionScope,
    val visibility: androidx.compose.animation.AnimatedVisibilityScope)
internal val LocalContactTransition = staticCompositionLocalOf<ContactTransition?> { null }
@Composable private fun ContactMark(peer: TrustedPeer) {
    val transition = LocalContactTransition.current
    val mark = if (transition == null) Modifier else with(transition.shared) {
        Modifier.sharedElement(rememberSharedContentState("contact-" + peer.id),transition.visibility)
    }
    Box(mark.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer,androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center) {
        Text(peer.name.take(Character.charCount(peer.name.codePointAt(0))),color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

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
                leadingContent = { ContactMark(peer) },
                supportingContent = { Text(last?.let { (if (it.outgoing) "You: " else "") + it.text } ?: "Start a conversation",
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (peer.id in online) Text("Online", color = MaterialTheme.colorScheme.primary) })
        }
    }
}

@Composable internal fun Conversation(peer: TrustedPeer, trust: TrustStore, chat: LanChat, appearance: () -> Unit, networkSettings: () -> Unit) {
    LifecycleResumeEffect(peer.id) {
        openConversation.value = peer.id
        onPauseOrDispose { openConversation.compareAndSet(peer.id,null) }
    }
    val history by remember(peer.id) { trust.conversation(peer.id) }.collectAsStateWithLifecycle(emptyList())
    val online by chat.online.collectAsStateWithLifecycle()
    val network by chat.networkStatus.collectAsStateWithLifecycle()
    val diagnostics by chat.diagnostics.collectAsStateWithLifecycle()
    var draft by rememberSaveable(peer.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val attachments by remember(peer.id, peer.pin) { trust.transfers.forPeer(peer) }.collectAsStateWithLifecycle(emptyList())
    val byMessage = remember(attachments) { attachments.associateBy { it.id to it.outgoing } }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> picked = uri?.toString() }
    picked?.let { path -> AlertDialog(onDismissRequest = { picked = null }, title = { Text("Share with " + peer.name) },
        text = { QualityPicker() }, dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            picked = null
            scope.launch {
            busy = true
            try { trust.transfers.add(listOf(peer), android.net.Uri.parse(path)); error = false }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = true } finally { busy = false }
            }
        }) { Text("Send media") } }) }
    val latest = history.lastOrNull()
    LaunchedEffect(peer.id, latest?.id, latest?.outgoing) {
        if (latest != null) scroll.scrollToItem(0)
    }
    val motion = LocalAppMotion.current
    val paper = LocalWallpaper.current
    val ink = MaterialTheme.colorScheme.primary
    val typing by trust.typing.collectAsStateWithLifecycle()
    var composing by remember(peer.id) { mutableStateOf(false) }
    LaunchedEffect(typing[peer.id]) {
        val remaining = (typing[peer.id] ?: 0L) + 2500 - android.os.SystemClock.elapsedRealtime()
        composing = remaining > 0
        if (composing) { kotlinx.coroutines.delay(remaining); composing = false }
    }
    Column(Modifier.fillMaxSize().drawBehind {
        if (paper == "Tint") drawRect(ink.copy(alpha = .045f))
        if (paper == "Dotted") {
            val pitch = 22.dp.toPx()
            var y = pitch / 2
            while (y < size.height) {
                var x = pitch / 2
                while (x < size.width) { drawCircle(ink.copy(alpha = .12f),1.dp.toPx(),androidx.compose.ui.geometry.Offset(x,y)); x += pitch }
                y += pitch
            }
        }
    }.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactMark(peer)
            TextButton(onClick = appearance) { Text("Chat appearance") }
        }
        if (composing) Text(peer.name + " is typing…",style = MaterialTheme.typography.labelSmall)
        Text(if (peer.id in online) "Connected over Wi-Fi" else if (network == "On the local network") "Waiting for peer · messages will queue" else network,
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (peer.id !in online && diagnostics[peer.id] != null) {
            Text(diagnostics.getValue(peer.id), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = networkSettings) { Text("Network and hotspot") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, reverseLayout = true,
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(history.asReversed(), key = { it.id + it.outgoing }) { message ->
                Row(Modifier.fillMaxWidth().then(if (motion) Modifier.animateItem() else Modifier), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.fillMaxWidth(.80f), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                        Surface(shape = RoundedCornerShape(20.dp, 20.dp, if (message.outgoing) 5.dp else 20.dp, if (message.outgoing) 20.dp else 5.dp),
                            color = if (message.attachment && message.outgoing) MaterialTheme.colorScheme.secondaryContainer
                            else if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer) {
                            val transfer = byMessage[message.id to message.outgoing]
                            if (message.attachment && transfer != null) TransferItem(transfer, trust.transfers, preview = true)
                            else Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                        if (message.outgoing && !message.attachment) Text(if (message.delivered) "Delivered" else "Queued", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (error) Text("Couldn’t save the message. Try again.", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            TextButton(enabled = !busy, onClick = {
                photos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }) { Text("Photo") }
            OutlinedTextField(value = draft, onValueChange = { if (it.length <= 4096) { draft = it; if (it.isNotBlank()) trust.composing(peer.id) } }, modifier = Modifier.weight(1f),
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
