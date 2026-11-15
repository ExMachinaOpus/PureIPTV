package app.pureiptv.core.data.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pureiptv.core.data.R
import app.pureiptv.core.data.source.SourceManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Keeps the app alive while imports run. Imports themselves run in [SourceManager]'s app scope;
 * without this, leaving the app (Home button, screen saver) lets Android freeze the process and a
 * big import stalls halfway. Holds a "data sync" foreground service until no import is running.
 */
@HiltWorker
class ImportKeepAliveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sources: SourceManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        sources.busy.first { !it }
        // Normally removed with the foreground service; this also covers a copy posted by
        // showNotification() just as the import ended, or when the service couldn't start.
        applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notification(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NAME = "import-keep-alive"
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 41

        /**
         * Posts the import notification again. Android drops it while the app may not post
         * notifications, and doesn't bring it back when the permission is granted afterwards (the
         * first import on a phone starts before the user answers the permission request).
         */
        fun showNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(context))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            val title = context.getString(R.string.import_notification)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW),
                )
            }
            // Tapping it opens the app.
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                // Android 12+ otherwise holds a foreground service's notification back for ~10 s.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ImportKeepAliveWorker>().build(),
            )
        }
    }
}
