package app.pureiptv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.core.data.work.RefreshWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PureApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sourceManager: SourceManager

    override fun onCreate() {
        super.onCreate()
        app.pureiptv.ui.components.installImageLoader()
        // Checks content.db, rebuilds + re-imports it if needed, cleans up orphaned content.
        sourceManager.start()
        RefreshWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
