package dev.lanchatshare

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.lanchatshare.connectivity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.Locale

internal fun Transfer.meterKey() = "$peer/$id/$outgoing"
internal data class Rate(val speed: Double, val average: Double, val remainingSeconds: Long?, val path: String? = null) {
    fun label(done: Boolean) = if (done) "%.2f MB/s average".format(Locale.US, average)
        else "%.2f MB/s".format(Locale.US, speed) + (remainingSeconds?.let { " · ${it}s left" } ?: "")
}

internal object TransferMeter {
    val rates = MutableStateFlow<Map<String,Rate>>(emptyMap())
    val reportRevision = MutableStateFlow(0)
    private fun prefs(c: Context) = c.getSharedPreferences("transfer-measurements",Context.MODE_PRIVATE)
    fun saved(c: Context, row: Transfer): Rate? = prefs(c).getString(row.meterKey(),null)?.let {
        val avg = JSONObject(it).optDouble("averageMBps",0.0)
        Rate(avg,avg,0,JSONObject(it).optString("path"))
    }
    fun report(c: Context): String = org.json.JSONArray().apply {
        val rows = prefs(c).all.values.filterIsInstance<String>().map(::JSONObject)
            .filter { it.optBoolean("benchmark") }.sortedBy { it.optLong("endedAt") }
        val probes = rows.filter { it.optString("kind") == "memory" }
        for (row in rows) {
            if (row.optString("kind") == "file" && row.optLong("measuredFromOffset") == 0L) {
                val elapsed = row.optLong("elapsedMs")
                val on = when {
                    elapsed <= 0 -> null
                    row.optLong("screenOnMs") >= elapsed * 0.95 -> true
                    row.optLong("screenOffMs") >= elapsed * 0.95 -> false
                    else -> null
                }
                if (on != null) for (mode in listOf("TCP","TLS")) {
                    val match = probes.lastOrNull { probe ->
                        val age = row.optLong("endedAt") - probe.optLong("endedAt")
                        age in 0..7_200_000L && probe.optString("transport") == mode &&
                            listOf("peerId","direction","path","testSetup").all { row.optString(it) == probe.optString(it) } &&
                            probe.optLong("sizeBytes") == row.optLong("sizeBytes") &&
                            probe.optBoolean("screenOnStart") == on && probe.optBoolean("screenOnEnd") == on &&
                            probe.opt("frequencyMHz") == row.opt("frequencyMHz") && probe.optDouble("averageMBps") > 0
                    }
                    if (match != null) {
                        row.put("percentOf" + mode,100 * row.getDouble("averageMBps") / match.getDouble("averageMBps"))
                        row.put(mode + "ReferenceEndedAt",match.getLong("endedAt"))
                    }
                }
            }
            put(row)
        }
    }.toString(2)

    fun saveCeiling(c: Context, record: JSONObject) {
        prefs(c).edit().putString("ceiling/" + java.util.UUID.randomUUID(), record.put("benchmark",true).toString()).apply()
        reportRevision.update { it + 1 }
    }

    private class Sample(val start: Long,val initial: Long,val cpu: Long, var on: Boolean) {
        var last = start; var offset = initial; var min = Double.POSITIVE_INFINITY; var max = 0.0
        var onMs = 0L; var offMs = 0L; var onBytes = 0L; var offBytes = 0L
        var lastProgress = start; var stalls = 0; var stalled = false; var retries = 0; var state = ""
        var publishNanos = 0L; var compared = false; var updates = 0; var fullOffsetAt: Long? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe(c: Context, files: Transfers, chat: LanChat) {
        val pm = c.getSystemService(PowerManager::class.java)
        val samples = mutableMapOf<String,Sample>()
        files.all.conflate().transformLatest { rows ->
            emit(rows)
            while (rows.any { it.state in listOf("active","retrying") }) { delay(1000); emit(rows) }
        }.collect { rows ->
            val now = SystemClock.elapsedRealtime()
            for (row in rows) {
                val key = row.meterKey()
                if (row.state !in listOf("active","retrying","done")) continue
                val s = samples[key] ?: if (row.state=="done") continue else Sample(now,row.offset,Process.getElapsedCpuTime(),pm.isInteractive).also { samples[key]=it }
                if (row.state=="retrying" && s.state!="retrying") s.retries++
                s.state=row.state
                val dt=now-s.last
                if (dt < 750 && row.state!="done") continue
                val bytes=(row.offset-s.offset).coerceAtLeast(0)
                val speed=bytes/1000.0/dt.coerceAtLeast(1)
                val avg=(row.offset-s.initial).coerceAtLeast(0)/1000.0/(now-s.start).coerceAtLeast(1)
                if (bytes>0) { s.lastProgress=now; s.stalled=false }
                if (!s.stalled && now-s.lastProgress>=8000 && row.offset<row.size) { s.stalls++; s.stalled=true }
                val screen=pm.isInteractive
                if (screen==s.on) {
                    if (screen) { s.onMs+=dt; s.onBytes+=bytes } else { s.offMs+=dt; s.offBytes+=bytes }
                }
                s.on=screen; s.last=now; s.offset=row.offset; s.min=minOf(s.min,speed); s.max=maxOf(s.max,speed)
                val rate=Rate(speed,avg,if(speed>0) kotlin.math.ceil((row.size-row.offset)/1e6/speed).toLong() else null,chat.paths.value[row.peer])
                if (!s.compared && !row.outgoing && now-s.start >= 15000 && row.offset-s.initial >= 8_000_000 &&
                    row.state == "active" && rate.path in listOf("Wi-Fi Direct","Local hotspot")) {
                    s.compared = true
                    val history = prefs(c).all.entries.asSequence().filter { it.key.startsWith(row.peer + "/") }
                        .mapNotNull { (it.value as? String)?.let(::JSONObject) }
                        .filter { it.optBoolean("benchmark") && it.optString("direction") == "receive" &&
                            it.optString("path") == "Wi-Fi client (router or hotspot)" && it.optLong("sizeBytes") == row.size &&
                            (if (screen) it.optLong("screenOnMs") else it.optLong("screenOffMs")) >= it.optLong("elapsedMs") * 0.9 }
                        .maxByOrNull { it.optLong("endedAt") }
                    if (history != null) TurboLink.compareSpeed(row.peer,avg,history.optDouble("averageMBps",0.0))
                }
                s.updates++
                if (row.offset == row.size && row.state != "done" && s.fullOffsetAt == null) s.fullOffsetAt = now
                val publishing = System.nanoTime()
                rates.update { it + (key to rate) }
                s.publishNanos += System.nanoTime() - publishing
                if (row.state=="done") {
                    val info = radioReading(c,row.peer)
                    val record=JSONObject().put("benchmark",row.name.startsWith("LAN benchmark ") && row.mime=="application/octet-stream")
                        .put("sizeBytes",row.size).put("direction",if(row.outgoing) "send" else "receive")
                        .put("measuredFromOffset",s.initial).put("elapsedMs",now-s.start).put("averageMBps",avg)
                        .put("testSetup",row.name.substringAfter(" MB - ","Unspecified").removeSuffix(".bin"))
                        .put("peerId",row.peer).put("kind","file").put("transferId",row.id).put("averageMbitPerSec",avg*8)
                        .put("minimumMbitPerSec",s.min*8).put("peakMbitPerSec",s.max*8)
                        .put("stages",JSONObject(files.benchmarkStages(row))).put("progressSamples",s.updates)
                        .put("observedFinalReceiptWaitMs",if(row.outgoing) s.fullOffsetAt?.let { now-it } ?: JSONObject.NULL else JSONObject.NULL)
                        .put("publishProgressMs",s.publishNanos/1_000_000).put("tlsEncryptMs",JSONObject.NULL).put("tlsDecryptMs",JSONObject.NULL)
                        .put("compression","none").put("minimumMBps",s.min).put("peakMBps",s.max).put("screenOnMs",s.onMs).put("screenOffMs",s.offMs)
                        .put("screenOnBytes",s.onBytes).put("screenOffBytes",s.offBytes).put("screenOnMBps",if(s.onMs>0)s.onBytes/1000.0/s.onMs else JSONObject.NULL)
                        .put("screenOffMBps",if(s.offMs>0)s.offBytes/1000.0/s.offMs else JSONObject.NULL)
                        .put("retriesObserved",s.retries).put("stallsObserved",s.stalls).put("processCpuMs",Process.getElapsedCpuTime()-s.cpu)
                        .put("frequencyMHz",info.frequency ?: JSONObject.NULL).put("linkMbps",info.mbps ?: JSONObject.NULL)
                        .put("rssiDbm",info.rssi ?: JSONObject.NULL)
                        .put("path",chat.paths.value[row.peer] ?: "unknown").put("endedAt",System.currentTimeMillis())
                    prefs(c).edit().putString(key,record.toString()).apply()
                    samples.remove(key); reportRevision.update { it + 1 }
                }
            }
            val live=rows.filter { it.state in listOf("active","retrying","paused") }.map { it.meterKey() }.toSet()
            samples.keys.retainAll(live)
            rates.update { current -> current.entries.filter { it.key !in live }.takeLast(32).associate { it.toPair() } + current.filterKeys { it in live } }
        }
    }
}
@Suppress("DEPRECATION")
internal fun wifiInfo(c: Context) = try { c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo }
catch (_: SecurityException) { null }

internal fun radioReading(c: Context, peer: String): RadioReading = TurboLink.reading(c,peer) ?: wifiInfo(c).let {
    RadioReading(it?.frequency?.takeIf { f -> f > 0 },it?.linkSpeed?.takeIf { speed -> speed > 0 },it?.rssi?.takeIf { signal -> signal in -126..0 })
}

