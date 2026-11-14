package app.pureiptv.core.data.library

import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.SourceEntity
import app.pureiptv.core.data.source.ImportState
import app.pureiptv.core.data.source.SourceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

data class SourceSummary(
    val source: SourceEntity,
    val live: Int,
    val movies: Int,
    val series: Int,
    val import: ImportState?,
)

data class LibrarySummary(val sources: List<SourceSummary> = emptyList(), val mergedChannels: Int = 0)

/**
 * Read model for the UI. Combines user.db (sources) with content.db (counts) and follows
 * content.db when it is rebuilt. Room types never leave core:data.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val content: ContentStore,
    private val sources: SourceManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun summary(): Flow<LibrarySummary> = content.database.flatMapLatest { db ->
        val dao = db.libraryDao()
        val rows = combine(
            sources.sources,
            dao.observeLiveStreamCounts(),
            dao.observeMovieCounts(),
            dao.observeSeriesCounts(),
            sources.imports,
        ) { list, live, movies, series, imports ->
            val l = live.associate { it.sourceId to it.count }
            val m = movies.associate { it.sourceId to it.count }
            val s = series.associate { it.sourceId to it.count }
            list.map { SourceSummary(it, l[it.id] ?: 0, m[it.id] ?: 0, s[it.id] ?: 0, imports[it.id]) }
        }
        val merged = sources.sources.flatMapLatest { list ->
            dao.observeMergedChannelCount(list.filter { it.enabled }.map { it.id })
        }
        combine(rows, merged) { r, count -> LibrarySummary(r, count) }
    }
}
