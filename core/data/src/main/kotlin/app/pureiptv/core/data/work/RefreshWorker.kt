package app.pureiptv.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pureiptv.core.data.source.SourceManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.joinAll
import java.util.concurrent.TimeUnit

/**
 * Keeps sources (and their guides) fresh without the user doing anything: once a day, on any
 * network, while the device is idle-ish. Opening the app also refreshes stale sources.
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: SourceManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        sources.refreshStale(maxAgeMs = TimeUnit.HOURS.toMillis(20)).joinAll()
        return Result.success()
    }

    companion object {
        private const val NAME = "refresh-sources"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
