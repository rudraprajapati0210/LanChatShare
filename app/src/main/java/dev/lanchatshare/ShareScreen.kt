package dev.lanchatshare

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.*
import java.io.IOException

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable internal fun TransferItem(row: Transfer, files: Transfers, preview: Boolean = false) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var problem by remember(row.id) { mutableStateOf(false) }
    val available = row.outgoing || row.state == "done"
    val save = rememberLauncherForActivityResult(CreateDocument(row.mime.ifBlank { "application/octet-stream" })) { dest ->
        if (dest != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(dest)).use { out -> files.file(row).inputStream().use { it.copyTo(out) } }
            }; problem = false } catch (e: CancellationException) { throw e } catch (_: Exception) { problem = true }
        }
    }
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (available && preview && row.mime.startsWith("image/")) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, row.peer, row.id, available) {
                value = withContext(Dispatchers.IO) { try {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(files.file(row))) { decoder, info, _ ->
                        decoder.setTargetSampleSize(maxOf(1, (maxOf(info.size.width, info.size.height) + 639) / 640))
                    }
                } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null } }
            }
            bitmap?.let { Image(it.asImageBitmap(), row.name, Modifier.fillMaxWidth().heightIn(max = 224.dp)) }
        }
        Text(row.name, style = MaterialTheme.typography.titleSmall)
        val state = when (row.state) {
            "offered" -> if (row.outgoing) "Waiting for acceptance" else "Ready to receive"
            "active" -> "Transferring"
            "done" -> if (row.outgoing) "Delivered" else "Received"
            "failed" -> "Transfer interrupted"
            "invalid" -> "File verification failed"
            "storage" -> "Free up space to receive this file"
            else -> row.state.replaceFirstChar { it.uppercase() }
        }
        Text(state + " · " + android.text.format.Formatter.formatFileSize(context, row.size), style = MaterialTheme.typography.labelMedium)
        if (row.state == "active" || row.state == "paused") {
            val fraction = { if (row.size == 0L) 0f else (row.offset.toDouble() / row.size).toFloat().coerceIn(0f, 1f) }
            if (!preview && row.state == "active" && android.animation.ValueAnimator.areAnimatorsEnabled())
                LinearWavyProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            Text("${row.offset} / ${row.size} bytes", style = MaterialTheme.typography.labelSmall)
        }
        val actions = when (row.state) {
            "offered" -> if (!row.outgoing) listOf("Accept" to "active", "Decline" to "declined") else emptyList()
            "active" -> listOf("Pause" to "paused")
            "paused" -> listOf("Resume" to "active")
            "failed", "storage", "invalid" -> listOf("Retry" to "active")
            else -> emptyList()
        }
        Row { actions.forEach { (label, next) ->
            TextButton(onClick = { scope.launch { files.action(row, next) } }) { Text(label) }
        }
            if (!row.outgoing && available) TextButton(onClick = { save.launch(row.name) }) { Text("Save a copy") }
        }
        if (problem) Text("Couldn’t save this copy. Choose another location.")
    }
}

@Composable internal fun ShareScreen(peers: List<TrustedPeer>, files: Transfers, incoming: IncomingShare?, consumed: () -> Unit, chatPeer: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var recipients by rememberSaveable { mutableStateOf<List<String>>(ArrayList(listOfNotNull(chatPeer))) }
    var text by rememberSaveable { mutableStateOf("") }
    var editingText by rememberSaveable { mutableStateOf(false) }
    var jobs by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var canNotify by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val notifications = rememberLauncherForActivityResult(RequestPermission()) { canNotify = it }
    val transfers by files.all.collectAsStateWithLifecycle(emptyList())
    fun add(uris: List<Uri>) { selected = ArrayList((selected + uris.map(Uri::toString)).distinct()) }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            selected = ArrayList((selected + incoming.uris).distinct())
            if (incoming.text.isNotBlank()) text = incoming.text
            jobs = false
            consumed()
        }
    }
    val documents = rememberLauncherForActivityResult(OpenMultipleDocuments(), ::add)
    val photos = rememberLauncherForActivityResult(PickMultipleVisualMedia(), ::add)
    val contact = rememberLauncherForActivityResult(GetContent()) { uri -> if (uri != null) add(listOf(uri)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!jobs, { jobs = false }, { Text("Send") })
            FilterChip(jobs, { jobs = true }, { Text("Transfers") })
        } }
        if (jobs) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !canNotify) item { TextButton(onClick = { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Notify me about incoming files") } }
            if (transfers.isEmpty()) item { Text("Your first transfer will appear here.") }
            val sections = transfers.groupBy { row -> when (row.state) {
                "active", "paused", "failed", "storage", "invalid" -> "In progress"
                "offered" -> "Queued"
                else -> "History"
            } }
            for (title in listOf("In progress", "Queued", "History")) {
                val section = sections[title].orEmpty()
                if (section.isNotEmpty()) item { Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
                items(section, key = { it.peer + it.id + it.outgoing }) { row ->
                    Text((if (row.outgoing) "To " else "From ") + (peers.find { it.id == row.peer }?.let { peerLabel(it, peers) } ?: "Forgotten phone"))
                    TransferItem(row, files)
                    HorizontalDivider()
                }
            }
        } else {
            item { Text("What’s going over?", style = MaterialTheme.typography.headlineMedium) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !sending, onClick = { photos.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)) }) { Text("Photos & videos") }
                OutlinedButton(enabled = !sending, onClick = { documents.launch(arrayOf("*/*")) }) { Text("Files") }
            } }
            item { Row {
                TextButton(enabled = !sending, onClick = { contact.launch("text/x-vcard") }) { Text("Contact") }
                TextButton(enabled = !sending, onClick = { editingText = !editingText }) { Text("Text") }
            } }
            if (editingText || text.isNotEmpty()) item { OutlinedTextField(text, { text = it }, enabled = !sending, label = { Text("Shared text") }) }
            items(selected, key = { it }) { uriValue ->
                val title by produceState("Selected file", uriValue) {
                    value = withContext(Dispatchers.IO) { runCatching {
                        context.contentResolver.query(uriValue.toUri(), arrayOf("_display_name"), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: "Selected file" }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    TextButton(enabled = !sending, onClick = { selected = ArrayList(selected - uriValue) }) { Text("Remove") }
                }
            }
            item { Text("Send to", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
            if (peers.isEmpty()) item { Text("Pair a phone in Nearby to share with it.") }
            items(peers, key = { it.id }) { peer ->
                val checked = peer.id in recipients
                Row(Modifier.fillMaxWidth().toggleable(checked, enabled = !sending, role = Role.Checkbox) {
                    recipients = ArrayList(if (it) recipients + peer.id else recipients - peer.id)
                }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null); Spacer(Modifier.width(12.dp)); Text(peerLabel(peer, peers))
                }
            }
            item { Button(shape = RoundedCornerShape(18.dp), enabled = !sending && (selected.isNotEmpty() || text.isNotBlank()) &&
                peers.any { it.id in recipients }, onClick = {
                val targets = peers.filter { it.id in recipients }
                sending = true
                scope.launch {
                    try {
                        for (uri in selected.toList()) { files.add(targets, uri.toUri()); selected = ArrayList(selected - uri) }
                        if (text.isNotBlank()) { files.addText(targets, text); text = "" }
                        jobs = true; error = ""
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Couldn’t prepare the file. Check free space and file access." }
                    finally { sending = false }
                }
            }) { Text(if (sending) "Preparing…" else "Send") } }
        }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
    }
}
