package dev.lanchatshare

import android.content.ClipData
import android.content.Intent
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.*
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.*

@Composable private fun Choice(value: String, values: List<String>, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(value) }
        DropdownMenu(open,{ open = false }) { values.forEach { item ->
            DropdownMenuItem(text = { Text(item) },onClick = { open = false; select(item) })
        } }
    }
}

@Composable internal fun LibraryScreen(files: Transfers, peers: List<TrustedPeer>) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("All types") }
    var direction by rememberSaveable { mutableStateOf("Both ways") }
    var contact by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("Any date") }
    var sort by rememberSaveable { mutableStateOf(FileOrder.Date) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Transfer?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val storage by files.storageUsage.collectAsStateWithLifecycle(0L)
    val filter = remember(text,kind,direction,contact,date,sort) {
        val days = when (date) { "Past week" -> 7; "Past month" -> 30; else -> 0 }
        LibraryFilter(text,FileKind.entries.find { it.name == kind },when(direction) { "Sent" -> true; "Received" -> false; else -> null },
            contact.ifBlank { null },if (days == 0) null else System.currentTimeMillis() - days * 86400000L,sort)
    }
    val rows = remember(filter,files) { files.library(filter) }.collectAsLazyPagingItems()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(Formatter.formatFileSize(context,storage) + " stored locally",style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(text,{ text = it },singleLine = true,label = { Text("Find a file") },modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Choice(kind,listOf("All types") + FileKind.entries.map { it.name }) { kind = it }
            Choice(direction,listOf("Both ways","Sent","Received")) { direction = it }
            val names = peers.associate { peerLabel(it,peers) to it.id }
            Choice(names.entries.find { it.value == contact }?.key ?: "All contacts",listOf("All contacts") + names.keys) { contact = names[it].orEmpty() }
            Choice(date,listOf("Any date","Past week","Past month")) { date = it }
            Choice(sort.name,FileOrder.entries.map { it.name }) { sort = FileOrder.valueOf(it) }
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "List view" else "Grid view") }
        }
        if (rows.loadState.refresh is androidx.paging.LoadState.Error) TextButton(onClick = rows::retry) { Text("Couldn’t load files. Retry") }
        else if (rows.itemCount == 0 && rows.loadState.refresh is androidx.paging.LoadState.NotLoading) Text("No files here yet.",Modifier.padding(vertical = 24.dp))
        LazyVerticalGrid(if (grid) GridCells.Adaptive(144.dp) else GridCells.Fixed(1),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.itemCount,key = rows.itemKey { it.meterKey() }) { index ->
                rows[index]?.let { row ->
                    Column(Modifier.fillMaxWidth().clickable { selected = row; issue = ""; deleting = false }.padding(vertical = 8.dp)) {
                        if (grid) FilePreview(files,row,104)
                        Text(row.name,maxLines = 2,overflow = TextOverflow.Ellipsis,style = MaterialTheme.typography.titleSmall)
                        Text((if (row.outgoing) "Sent" else "Received") + " · " + Formatter.formatFileSize(context,row.size),style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    selected?.let { row ->
        fun launch(share: Boolean) {
            try {
                val uri = FileProvider.getUriForFile(context,context.packageName + ".library",files.file(row),row.name)
                val intent = if (share) Intent(Intent.ACTION_SEND).setType(row.mime).putExtra(Intent.EXTRA_STREAM,uri)
                    else Intent(Intent.ACTION_VIEW).setDataAndType(uri,row.mime)
                intent.clipData = ClipData.newRawUri(row.name,uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(if (share) Intent.createChooser(intent,row.name) else intent)
            } catch (_: Exception) { issue = "Couldn’t open this file on this phone." }
        }
        AlertDialog(onDismissRequest = { selected = null },title = { Text(if (deleting) "Delete this local copy?" else row.name) },
            text = { Column {
                if (!deleting) {
                    FilePreview(files,row,176)
                    Text((if (row.outgoing) "To " else "From ") + (peers.find { it.id == row.peer }?.name ?: "Forgotten phone"))
                    Text(Formatter.formatFileSize(context,row.size))
                    Row { TextButton(onClick = { launch(false) }) { Text("Open") }; TextButton(onClick = { launch(true) }) { Text("Share") } }
                    if (row.state in listOf("done","cancelled","declined","revoked")) TextButton(onClick = { deleting = true }) { Text("Delete local copy") }
                } else Text(row.name)
                if (issue.isNotEmpty()) Text(issue)
            } },confirmButton = { TextButton(onClick = {
                if (!deleting) selected = null else scope.launch {
                    try { if (files.removeLocal(row)) selected = null else issue = "Transfer is still active." }
                    catch (e: Exception) { if (e is CancellationException) throw e; issue = "Couldn’t remove the file." }
                }
            }) { Text(if (deleting) "Delete" else "Done") } },
            dismissButton = { if (deleting) TextButton(onClick = { deleting = false }) { Text("Keep file") } })
    }
}

@Composable private fun FilePreview(files: Transfers,row: Transfer,height: Int) {
    val image by produceState<android.graphics.Bitmap?>(null,row.meterKey(),height) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = files.file(row)
                when {
                    row.mime.startsWith("image/") -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        decoder.setTargetSampleSize(((maxOf(info.size.width,info.size.height)+511)/512).coerceAtLeast(1))
                    }
                    row.mime.startsWith("video/") -> MediaMetadataRetriever().use { media ->
                        media.setDataSource(file.path)
                        media.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,192,108)
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap.asImageBitmap(),row.name,Modifier.fillMaxWidth().height(height.dp))
    else Row(Modifier.heightIn(min = 48.dp),verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.primary
        Canvas(Modifier.padding(end = 8.dp).size(24.dp,30.dp)) {
            val w = size.width
            val h = size.height
            val page = androidx.compose.ui.graphics.Path().apply {
                moveTo(2f,2f); lineTo(w * .62f,2f); lineTo(w-2f,h * .28f)
                lineTo(w-2f,h-2f); lineTo(2f,h-2f); close()
                moveTo(w * .62f,2f); lineTo(w * .62f,h * .28f); lineTo(w-2f,h * .28f)
            }
            drawPath(page,ink,style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        Text(row.name.substringAfterLast('.',row.mime.substringBefore('/')).uppercase().take(8),
            style = MaterialTheme.typography.titleMedium,color = ink)
    }
}
