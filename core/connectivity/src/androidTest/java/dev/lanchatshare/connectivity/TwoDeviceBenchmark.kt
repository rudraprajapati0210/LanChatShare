package dev.lanchatshare.connectivity

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.lanchatshare.data.Profile
import io.ktor.client.plugins.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.util.Base64
import java.util.UUID

/** Opt-in two-installation harness. Pins are exchanged by the test operator. */
class TwoDeviceBenchmark {
    @Test fun runPair() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("role")
        assumeTrue(role in listOf("pin","send","receive"))
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pin = Base64.getEncoder().encodeToString(digest(identityKeys().public.encoded))
        Log.i("LcsBench","PIN $pin")
        if (role == "pin") return@runBlocking
        val host = args.getString("host","127.0.0.1")
        val serverSide = role == "send"
        val ids = listOf("00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002")
        val local = Profile(role!!,ids[if (serverSide) 0 else 1],0)
        val peer = TrustedPeer(ids[if (serverSide) 1 else 0],"peer",0,args.getString("pin")!!)
        val tls = deviceCredentials(local.id)
        val dbName = "bench-" + UUID.randomUUID()
        val store = TrustStore(ctx,dbName)
        store.dao.save(peer)
        val sizes = args.getString("sizes","10,30,100,500,1000").split(",").map { it.toLong()*1000000 }
        val monitor = launch(Dispatchers.IO) {
            var last = ""
            while (isActive) {
                val row = store.transfers.all.first().firstOrNull()
                val status = "${row?.size} ${row?.offset} ${row?.state}"
                if (last != status) Log.i("LcsBench","$role PROGRESS $status")
                last = status; delay(3000)
            }
        }
        try {
            if (args.getString("ceiling") == "true") {
                val remote = java.net.InetAddress.getByName(args.getString("remote")!!)
                val link = lanLinks(ctx).first { it.contains(remote) }
                val route = ProbeRoute(peer,tls,remote,link.address,link.network)
                if (serverSide) Log.i("LcsBench","READY CEILING")
                if (!serverSide) delay(1000)
                ProbeSockets().use { sockets ->
                    ceilingPair(route,serverSide,100,sockets) { rate ->
                        Log.i("LcsBench","CEILING ${rate.mode} bytes=${rate.bytes} ns=${rate.nanos} MBps=${rate.mbps}")
                    }
                }
                if (!serverSide) delay(2000)
            }
            if (serverSide) {
                val server = chatServer(listOf(host),47831,serverTls(tls) { mapOf(peer.id to peer.pin) },store.transfers) {
                    chat(authenticateIncoming(local.id,store),store,flowOf(local))
                }
                try {
                    server.start(false); Log.i("LcsBench","READY")
                    for (size in sizes) {
                        val row = Transfer(peer.id,true,UUID.randomUUID().toString(),"benchmark.bin","application/octet-stream",size,"",
                            peer.pin,UUID.randomUUID().toString(),"offered",0,System.currentTimeMillis())
                        val file = store.transfers.file(row)
                        try {
                            file.parentFile!!.mkdirs()
                            RandomAccessFile(file,"rw").use { it.setLength(size) }
                            store.transfers.dao.insert(row.copy(hash=fileHash(file.inputStream())))
                            var previous = -1L
                            var lastChange = SystemClock.elapsedRealtime()
                            while (true) {
                                val current = requireNotNull(store.transfers.dao.get(peer.id,true,row.id))
                                if (current.state == "done") break
                                if (current.offset != previous) {
                                    previous = current.offset
                                    lastChange = SystemClock.elapsedRealtime()
                                }
                                check(SystemClock.elapsedRealtime()-lastChange < 180000) {
                                    "Sender received no progress for three minutes at $previous of $size bytes"
                                }
                                delay(250)
                            }
                        } finally { file.delete() }
                    }
                    delay(2000)
                } finally { server.stop(100,1000) }
            } else {
                val origin = "https://$host:47831"
                chatClient(peer,tls).use { http ->
                    val socket = launch(Dispatchers.IO) {
                        http.webSocket(urlString="wss://$host:47831/v1/chat") {
                            authenticateOutgoing(local.id,peer,identityKeys(),null)
                            chat(peer,store,flowOf(local)) { row,token ->
                                val window = args.getString("windowBytes")?.toInt() ?: RANGE_BLOCK
                                val streams = args.getString("streams")?.toInt() ?: 1
                                val timings = RangeTimings().takeIf { streams == 1 }
                                while (!store.transfers.receive(row,http,origin,token,window,streams,timings)) { Log.i("LcsBench","RETRY ${row.id}"); delay(1000) }
                                if (timings != null) Log.i("LcsBench","PROFILE ${row.size} ${timings.summary()}")
                            }
                        }
                    }
                    try {
                        for (size in sizes) {
                            val row = withTimeout(180000) { store.transfers.all.first { rows -> rows.any { it.size==size && it.state=="offered" } }
                                .first { it.size==size && it.state=="offered" } }
                            val start = SystemClock.elapsedRealtime()
                            store.transfers.action(row,"active")
                            val probes = launch {
                                var n = 0
                                while (isActive) {
                                    val text = "probe-$size-" + n++
                                    val sent = SystemClock.elapsedRealtime()
                                    store.enqueue(peer.id,text)
                                    withTimeout(20000) { store.conversation(peer.id).first { list -> list.any { it.text==text && it.delivered } } }
                                    Log.i("LcsBench","CHAT $size " + (SystemClock.elapsedRealtime()-sent))
                                    delay(1000)
                                }
                            }
                            try {
                                var changed = start
                                var offset = -1L
                                while (true) {
                                    val current = store.transfers.dao.get(peer.id,false,row.id)!!
                                    if (current.offset != offset) { changed=SystemClock.elapsedRealtime(); offset=current.offset }
                                    if (current.state=="done") break
                                    check(current.state in listOf("active","retrying") && SystemClock.elapsedRealtime()-changed<45000) {
                                        "STALL size=$size offset=$offset ms=" + (SystemClock.elapsedRealtime()-start) + " state=" + current.state
                                    }
                                    delay(100)
                                }
                                Log.i("LcsBench","RESULT $size " + (SystemClock.elapsedRealtime()-start))
                            } finally { probes.cancelAndJoin(); store.transfers.file(row).delete() }
                        }
                        delay(1500)
                    } finally { socket.cancelAndJoin() }
                }
            }
        } finally { monitor.cancelAndJoin(); store.close(); ctx.deleteDatabase(dbName) }
    }
}
