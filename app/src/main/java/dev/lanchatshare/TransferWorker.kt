package dev.lanchatshare

import android.content.Context
import androidx.work.*
import dev.lanchatshare.connectivity.Transfer
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Room keeps the offer and partial file. Only a live authenticated session can supply a range grant. */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString("transfer") ?: return Result.failure()
        // A fresh grant schedules a successor after process death or a network change.
        retries[key]?.complete(Unit)
        return Result.success()
    }

    companion object {
        private val retries = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        suspend fun queue(context: Context, row: Transfer, download: suspend () -> Boolean): Unit = supervisorScope {
            val key = row.peer + "/" + row.id
            val manager = WorkManager.getInstance(context)
            var seconds = 10L
            // The authenticated session belongs to ChatService. A scheduler stop may cancel a retry
            // signal, but must never cancel the transfer or strand the grant.
            while (isActive && !download()) {
                val signal = CompletableDeferred<Unit>()
                retries[key] = signal
                val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("transfer" to key))
                    .setInitialDelay(seconds, TimeUnit.SECONDS).build()
                try {
                    manager.enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, request)
                    withTimeoutOrNull(seconds * 1000) { signal.await() }
                } finally {
                    retries.remove(key,signal)
                    manager.cancelWorkById(request.id)
                }
                seconds = (seconds * 2).coerceAtMost(60)
            }
        }
    }
}
