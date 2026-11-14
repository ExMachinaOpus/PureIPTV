package app.pureiptv.core.data.di

import android.content.Context
import android.util.Xml
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.pureiptv.core.data.db.SourceDao
import app.pureiptv.core.data.db.UserDao
import app.pureiptv.core.data.db.UserDatabase
import app.pureiptv.core.parsing.xmltv.XmltvParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Application-lifetime scope for work that must outlive a screen (imports, refreshes). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * user.db: the user's own data. No destructive migration, ever (see [UserDatabase]).
     * content.db is not provided here: it is owned by ContentStore, which can rebuild it.
     */
    @Provides
    @Singleton
    fun userDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME)
            // Bundled SQLite: identical FTS5/trigram support and performance on every Android version.
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .build()

    @Provides
    fun sourceDao(db: UserDatabase): SourceDao = db.sourceDao()

    @Provides
    fun userDao(db: UserDatabase): UserDao = db.userDao()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared HTTP client for APIs, playlists, EPG and images. IPTV servers are slow and flaky:
     * generous read timeout for large playlists, redirects followed (including http<->https).
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    fun xmltvParser(): XmltvParser = XmltvParser { Xml.newPullParser() }
}
