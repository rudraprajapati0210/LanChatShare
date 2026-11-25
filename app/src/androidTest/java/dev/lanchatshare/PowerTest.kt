package dev.lanchatshare

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.lanchatshare.connectivity.Transfer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class PowerTest {
    @Test fun stoppingScheduledWorkCannotStrandALiveGrant() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val row = Transfer(UUID.randomUUID().toString(),false,UUID.randomUUID().toString(),"sample","application/octet-stream",1,"","","","active",0,1)
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivered = async { TransferWorker.queue(ctx,row) { reading.complete(Unit); release.await(); true }; true }
        try {
            withTimeout(15000) { reading.await() }
            withContext(Dispatchers.IO) { androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork(row.peer + "/" + row.id).result.get() }
            delay(500)
            release.complete(Unit)
            val done = withTimeoutOrNull(4000) { delivered.await() } ?: false
            Log.i("LanAcceptance","Active grant finished after scheduler stopped its job=" + done)
            assertTrue("A stopped worker stranded the live grant",done)
        } finally { delivered.cancelAndJoin() }
    }
    @Test fun cpuLeaseFollowsActualIo() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val operations = kotlinx.coroutines.flow.MutableStateFlow(0)
        fun held() = ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("dumpsys power"))
            .bufferedReader().useLines { lines -> lines.any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("LanChatShare:transfer") } }
        val owner = launch(Dispatchers.IO) { ins.targetContext.holdTransferPower(operations) }
        try {
            operations.value = 2
            withTimeout(6000) { while (!held()) delay(100) }
            operations.value = 1
            assertTrue(held())
            operations.value = 0
            withTimeout(6000) { while (held()) delay(100) }
        } finally { owner.cancelAndJoin() }
    }

    @Test fun grantedTransferStartsWhileSchedulerIsIdle() = runBlocking {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val ins = InstrumentationRegistry.getInstrumentation()
        fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
        val ctx = ins.targetContext
        val row = Transfer(UUID.randomUUID().toString(),false,UUID.randomUUID().toString(),"sample","application/octet-stream",1,"","","","active",0,1)
        try {
            shell("dumpsys battery unplug"); shell("input keyevent 223"); shell("dumpsys deviceidle force-idle")
            val mode = shell("dumpsys deviceidle get deep").trim()
            Log.i("LanAcceptance","Device idle controller=" + mode)
            assertEquals("IDLE",mode)
            val started = withTimeoutOrNull(12000) { TransferWorker.queue(ctx,row) { true }; true } ?: false
            Log.i("LanAcceptance","deep idle, granted download started within 12 seconds=" + started)
            assertTrue("The live grant waited on background job scheduling", started)
        } finally {
            shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset"); shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }
}
