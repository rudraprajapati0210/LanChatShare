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
        val grant = grants[key] ?: return Result.success()
        val read = grant.scope.async { grant.download() }
        return try {
            if (read.await()) {
                grant.finished.complete(Unit)
                Result.success()
            } else Result.retry()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.success()
        } finally { read.cancel() }
    }

    companion object {
        private class Grant(val scope: CoroutineScope, val download: suspend () -> Boolean,
            val finished: CompletableDeferred<Unit> = CompletableDeferred())
        private val grants = ConcurrentHashMap<String, Grant>()

        suspend fun queue(context: Context, row: Transfer, download: suspend () -> Boolean): Unit = supervisorScope {
            val key = row.peer + "/" + row.id
            val grant = Grant(this, download)
            grants[key] = grant
            try {
                val request = OneTimeWorkRequestBuilder<TransferWorker>()
                    .setInputData(workDataOf("transfer" to key))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, request)
                grant.finished.await()
            } finally { grants.remove(key, grant) }
        }
    }
}
