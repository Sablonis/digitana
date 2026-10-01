package ch.digitana.dienstplan.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.digitana.dienstplan.DienstplanApp
import java.util.concurrent.TimeUnit

/**
 * Abgleich bei geschlossener App, etwa alle 15 Minuten (kürzer erlaubt Android nicht) und
 * nur mit Netz. Android kann ihn im Energiesparmodus verschieben.
 */
class BackgroundSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as DienstplanApp).container
        if (!container.awaitReady(READY_TIMEOUT_MILLIS)) return Result.retry()
        when (container.syncController.backgroundSync()) {
            BackgroundSyncResult.NO_TEAM, BackgroundSyncResult.SKIPPED -> Unit
            BackgroundSyncResult.DONE, BackgroundSyncResult.TIMEOUT -> container.shiftAlerts.afterBackgroundSync()
        }
        return Result.success()
    }

    private companion object {
        const val READY_TIMEOUT_MILLIS = 15_000L
    }
}

object BackgroundSyncScheduler {
    private const val WORK_NAME = "dienstplan-hintergrund-abgleich"
    private const val INTERVAL_MINUTES = 15L

    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
