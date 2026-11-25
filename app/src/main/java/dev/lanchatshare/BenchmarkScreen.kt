package dev.lanchatshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.*

@Composable internal fun BenchmarkScreen(peers: List<TrustedPeer>, files: Transfers, chat: LanChat) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf("") }
    var mb by rememberSaveable { mutableIntStateOf(10) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val revision by TransferMeter.reportRevision.collectAsStateWithLifecycle()
    val report by produceState("[]",revision) { value = withContext(Dispatchers.IO) { TransferMeter.report(context) } }
    val paths by chat.paths.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(CreateDocument("application/json")) { dest ->
        if (dest!=null) scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(dest)).bufferedWriter().use { it.write(report) } }
                message="Results exported" } catch (e: CancellationException) { throw e } catch (_: Exception) { message="Could not write the report" }
        }
    }
    val info = remember(revision,target) { wifiInfo(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Test a local transfer",style=MaterialTheme.typography.titleLarge)
        Text("Each run sends one generated file. Accept it on the other phone. Keep enough free space on both phones.")
        if(info!=null && info.frequency>0) Text("${info.frequency} MHz · negotiated link ${info.linkSpeed} Mbps")
        if(info?.frequency in 2400..2500) Text("This is 2.4 GHz. Try the 5 GHz network or a 5 GHz hotspot and compare.")
        peers.forEach { peer -> FilterChip(target==peer.id,{target=peer.id},{Text(peerLabel(peer,peers))}) }
        if(peers.isEmpty()) Text("Pair a phone in Nearby first.")
        paths[target]?.let { Text(it) }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(10,100,1000).forEach { size -> FilterChip(size==mb,{mb=size},{ Text(if(size==1000)"1 GB" else "$size MB") }) }
        }
        Button(enabled=!busy && peers.any { it.id==target },onClick={
            val peer=peers.first { it.id==target }
            busy=true
            scope.launch { try { files.addBenchmark(peer,mb); message="Queued for " + peer.name }
                catch(e: CancellationException){throw e} catch(_:Exception){message="Could not stage the file. Check available space."}
                finally {busy=false} }
        }) { Text(if(busy)"Preparing file…" else "Run benchmark") }
        if(report!="[]") Row {
            TextButton(onClick={save.launch("lanchatshare-benchmark.json")}) {Text("Export JSON")}
            TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LAN benchmark",report));message="Copied results"}) {Text("Copy results")}
        }
        Text("For screen-off measurements, lock the phone after accepting. Android can reduce Wi-Fi performance while locked.")
        if(message.isNotBlank()) Text(message)
    }
}
