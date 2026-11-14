package app.pureiptv.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns content.db, which is disposable: when it is missing, corrupt, or its schema changed, it is
 * deleted and recreated empty, and [consumeRebuildFlag] tells the caller to re-import all sources.
 * user.db is never touched by this.
 */
@Singleton
class ContentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val rebuilt = AtomicBoolean(false)
    private val mutex = Mutex()

    private val _database = MutableStateFlow(build())

    /** The current content.db. Emits a new instance after a rebuild; UI flows should follow it. */
    val database: StateFlow<ContentDatabase> = _database.asStateFlow()
    private val current: ContentDatabase get() = _database.value

    val importDao: ImportDao get() = current.importDao()

    /**
     * Opens content.db and checks it is readable. A corrupt file is deleted and recreated.
     * Call once at startup before using the DAOs.
     */
    suspend fun ensureHealthy() = mutex.withLock {
        try {
            current.importDao().ping()
        } catch (e: SQLiteException) {
            recreate()
        } catch (e: IllegalStateException) {
            // Room reports some unreadable/mismatched files this way.
            recreate()
        }
    }

    /**
     * Shrinks the write-ahead log after an import. An import writes hundreds of MB through it, and
     * while screens keep reading during the import SQLite can't start the log over, so it stays at its
     * largest size on disk. Best effort: when a reader is still busy, [LOG_LIMIT_BYTES] trims it later.
     */
    suspend fun trimLog() {
        runCatching {
            current.useWriterConnection { it.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { s -> s.step() } }
        }
    }

    /** True once after content.db was (re)created empty: all sources need a fresh import. */
    fun consumeRebuildFlag(): Boolean = rebuilt.getAndSet(false)

    private suspend fun recreate() {
        current.close()
        context.deleteDatabase(ContentDatabase.NAME) // also removes -wal / -shm / -journal
        _database.value = build().also { it.importDao().ping() }
        rebuilt.set(true)
    }

    private fun build(): ContentDatabase =
        Room.databaseBuilder(context, ContentDatabase::class.java, ContentDatabase.NAME)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // Rebuildable cache of provider data: schema changes simply drop it.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(object : RoomDatabase.Callback() {
                // SQLite truncates the log back to this size whenever it starts it over.
                override fun onOpen(connection: SQLiteConnection) = connection.execSQL("PRAGMA journal_size_limit = $LOG_LIMIT_BYTES")
                override fun onCreate(connection: SQLiteConnection) = rebuilt.set(true)
                override fun onDestructiveMigration(connection: SQLiteConnection) = rebuilt.set(true)
            })
            .build()

    private companion object {
        const val LOG_LIMIT_BYTES = 32L * 1024 * 1024
    }
}
