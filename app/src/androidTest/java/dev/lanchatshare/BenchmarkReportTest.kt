package dev.lanchatshare

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkReportTest {
    @Test fun matchesTheSameDirectionRouteSizeAndScreenState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("transfer-measurements",0)
        val peer = UUID.randomUUID().toString()
        fun sample(kind: String, ended: Long) = JSONObject()
            .put("benchmark",true).put("kind",kind).put("peerId",peer)
            .put("direction","receive").put("testSetup","Router").put("path","Wi-Fi")
            .put("sizeBytes",10_000_000L).put("frequencyMHz",5180).put("endedAt",ended)
        val probe = sample("memory",1).put("averageMBps",20).put("transport","TCP")
            .put("screenOnStart",true).put("screenOnEnd",true)
        val other = JSONObject(probe.toString()).put("endedAt",2).put("testSetup","Hotspot").put("averageMBps",1)
        val file = sample("file",3).put("averageMBps",5).put("elapsedMs",2000).put("screenOnMs",2000)
        val resumed = JSONObject(file.toString()).put("measuredFromOffset",262144).put("endedAt",4)
        val records = listOf(probe,other,file,resumed)
        try {
            val edit = prefs.edit()
            records.forEachIndexed { i, row -> edit.putString(peer+i,row.toString()) }
            assertTrue(edit.commit())
            val report = JSONArray(TransferMeter.report(context))
            val matches = (0 until report.length()).map(report::getJSONObject)
                .filter { it.optString("peerId") == peer && it.optString("kind") == "file" }
            assertEquals(25.0,matches.first().getDouble("percentOfTCP"),0.001)
            assertFalse(matches.last().has("percentOfTCP"))
        } finally {
            val edit = prefs.edit()
            records.indices.forEach { edit.remove(peer+it) }
            edit.commit()
        }
    }
}
