package app.pureiptv.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * user.db: the user's own data (sources, favorites, progress, history, settings).
 *
 * Must survive every app update. Pre-release the schema may still change freely; from the first
 * release on, every change bumps [version] and ships a tested Migration/AutoMigration.
 * Never use destructive migration here.
 */
@Database(
    entities = [
        SourceEntity::class,
        FavoriteEntity::class,
        WatchProgressEntity::class,
        HistoryEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun userDao(): UserDao

    companion object {
        const val NAME = "user.db"
        val MIGRATIONS = arrayOf(UserMigrations.V1_TO_V2)
    }
}

/**
 * content.db: everything imported from providers. Disposable: if it is missing, corrupt, or its
 * schema changes, it is deleted and rebuilt by re-importing all sources (see [ContentStore]).
 * Bump [version] freely when the schema changes; destructive migration is intended.
 */
@Database(
    entities = [
        CategoryEntity::class,
        ChannelEntity::class,
        LiveStreamEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProgrammeEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ContentDatabase : RoomDatabase() {
    abstract fun importDao(): ImportDao
    abstract fun libraryDao(): LibraryDao
    abstract fun browseDao(): BrowseDao

    companion object {
        const val NAME = "content.db"
    }
}
