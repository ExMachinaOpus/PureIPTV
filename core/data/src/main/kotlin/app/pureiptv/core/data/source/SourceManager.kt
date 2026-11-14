package app.pureiptv.core.data.source

import app.pureiptv.core.data.credentials.CredentialStore
import app.pureiptv.core.data.credentials.SourceSecret
import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.SourceDao
import app.pureiptv.core.data.db.SourceEntity
import android.content.Context
import app.pureiptv.core.data.di.AppScope
import app.pureiptv.core.data.work.ImportKeepAliveWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import app.pureiptv.core.model.SourceErrorKind
import app.pureiptv.core.parsing.xtream.XtreamCredentials
import app.pureiptv.core.parsing.xtream.XtreamUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds sources and runs imports. Imports run in the app scope, so leaving a screen never cancels
 * them; their live state is exposed in [imports]. One import per source at a time.
 */
@Singleton
class SourceManager @Inject constructor(
    private val sourceDao: SourceDao,
    private val credentials: CredentialStore,
    private val resolver: SourceResolver,
    private val m3uImporter: M3uImporter,
    private val xtreamImporter: XtreamImporter,
    private val epgImporter: EpgImporter,
    private val content: ContentStore,
    @AppScope private val scope: CoroutineScope,
    @ApplicationContext private val context: Context,
) {
    private val _imports = MutableStateFlow<Map<Long, ImportState>>(emptyMap())
    val imports: StateFlow<Map<Long, ImportState>> = _imports.asStateFlow()
    private val jobs = HashMap<Long, Job>()
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    val sources: Flow<List<SourceEntity>> = sourceDao.observeAll()

    /**
     * Call once at app start. Verifies content.db (rebuilding it if corrupt), re-imports every
     * source when content.db was (re)created, and removes content of sources that no longer exist.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            content.ensureHealthy()
            val sourceIds = sourceDao.observeAll().first().map { it.id }.toSet()
            if (content.consumeRebuildFlag()) {
                sourceIds.forEach(::refresh)
            } else {
                refreshStale(STALE_ON_START_MS)
            }
            val dao = content.importDao
            dao.sourceIdsWithContent().filterNot { it in sourceIds }.forEach { dao.deleteSourceContent(it) }
        }
    }

    /** Removes a source everywhere: running import, content (content.db), row (user.db), secret. */
    suspend fun delete(sourceId: Long) {
        synchronized(jobs) { jobs.remove(sourceId)?.cancel() }
        content.importDao.deleteSourceContent(sourceId)
        sourceDao.delete(sourceId)
        credentials.remove(sourceId)
        _imports.update { it - sourceId }
    }

    suspend fun setEnabled(sourceId: Long, enabled: Boolean) = sourceDao.setEnabled(sourceId, enabled)

    /** Moves a source one step up (earlier = preferred when the same channel/title exists twice). */
    suspend fun moveUp(sourceId: Long) {
        val all = sourceDao.observeAll().first()
        val i = all.indexOfFirst { it.id == sourceId }
        if (i <= 0) return
        val reordered = all.toMutableList().apply { add(i - 1, removeAt(i)) }
        reordered.forEachIndexed { priority, s -> if (s.priority != priority) sourceDao.update(s.copy(priority = priority)) }
    }

    suspend fun hasSources(): Boolean = sourceDao.count() > 0

    /** Checks the input (network). Throws [SourceException] with a user-facing reason. */
    suspend fun resolve(input: SourceInput): ResolvedSource = resolver.resolve(input)

    /**
     * Saves a resolved source and starts its first import. Returns the source id. Adding the same
     * playlist/login again just refreshes the existing source instead of creating a duplicate.
     */
    suspend fun add(resolved: ResolvedSource): Long {
        val existing = sourceDao.observeAll().first()
        existing.firstOrNull { credentials.get(it.id) == resolved.secret }?.let { same ->
            if (!same.enabled) sourceDao.setEnabled(same.id, true)
            resolved.guideUrl?.let { credentials.putGuideUrl(same.id, it) }
            refresh(same.id)
            return same.id
        }
        val id = sourceDao.insert(
            SourceEntity(
                name = uniqueName(resolved, existing.map { it.name }.toSet()),
                type = resolved.type.name,
                host = resolved.host,
                priority = sourceDao.nextPriority(),
                liveFormat = resolved.liveFormat,
            ),
        )
        credentials.put(id, resolved.secret)
        resolved.guideUrl?.let { credentials.putGuideUrl(id, it) }
        refresh(id)
        return id
    }

    /** The guide address in use for a playlist: the user's, or the one the playlist names. */
    suspend fun guideUrl(sourceId: Long): String? = credentials.guideUrl(sourceId)

    /**
     * Sets the guide address of a source (blank: back to the one the source offers, if any) and
     * refreshes it. For playlists that name no guide, or the wrong one.
     */
    suspend fun setGuideUrl(sourceId: Long, url: String) {
        if (url.isBlank()) credentials.removeGuideUrl(sourceId) else credentials.putGuideUrl(sourceId, SourceResolver.normalizeUrl(url))
        refresh(sourceId)
    }

    /**
     * "example.com", then "example.com 2". Nothing from the login or the link goes into the name
     * (no username, no playlist file name): names are shown on screen and stored in user.db.
     */
    private fun uniqueName(resolved: ResolvedSource, taken: Set<String>): String {
        if (resolved.name !in taken) return resolved.name
        return generateSequence(2) { it + 1 }.map { "${resolved.name} $it" }.first { it !in taken }
    }

    /**
     * Re-imports enabled sources not refreshed within [maxAgeMs] (and not importing right now).
     * A source whose last import started less than [RETRY_AFTER_MS] ago is left alone, whether it
     * failed or the app died during it: a source that breaks the import must not be retried at
     * every app start. "Refresh now" ([refresh]) is not held back by this.
     */
    suspend fun refreshStale(maxAgeMs: Long): List<Job> {
        val now = System.currentTimeMillis()
        return sourceDao.observeAll().first()
            .filter { it.enabled && (it.lastRefreshMs ?: 0) < now - maxAgeMs }
            .filter { (it.lastAttemptMs ?: 0) < now - RETRY_AFTER_MS }
            .filter { _imports.value[it.id] !is ImportState.Running }
            .mapNotNull { refresh(it.id); synchronized(jobs) { jobs[it.id] } }
    }

    /** Starts (or restarts) an import for [sourceId]. */
    fun refresh(sourceId: Long) {
        synchronized(jobs) {
            jobs[sourceId]?.cancel()
            running.update { it + 1 }
            jobs[sourceId] = scope.launch {
                try {
                    runImport(sourceId)
                } finally {
                    running.update { it - 1 }
                }
            }
        }
        ImportKeepAliveWorker.start(context)
    }

    private val running = MutableStateFlow(0)

    /** True while any import (including its guide) runs; keeps the app alive in the background. */
    val busy: Flow<Boolean> = running.map { it > 0 }

    /**
     * The guide is a bonus: it runs after the content import is reported done, and a missing or
     * broken guide never turns a working source into a failed one.
     */
    private suspend fun importGuide(sourceId: Long, secret: SourceSecret, generation: Long, onProgress: (Float?) -> Unit) {
        val url = when (secret) {
            // A guide address the user gave wins over the server's own.
            is SourceSecret.Xtream -> credentials.guideUrl(sourceId)
                ?: XtreamUrls(XtreamCredentials(secret.serverUrl, secret.username, secret.password)).xmltv()
            is SourceSecret.M3u -> credentials.guideUrl(sourceId)
        } ?: return
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val stored = epgImporter.import(sourceId, url, generation, onProgress = onProgress)
            android.util.Log.i(TAG, "guide source=$sourceId programmes=$stored in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Keep the previous guide; next refresh tries again. Throwable: a guide that exhausts
            // memory must not take the app (or the finished content import) down with it.
        }
    }

    private suspend fun runImport(sourceId: Long) {
        val publish: (ImportState) -> Unit = { state -> _imports.update { it + (sourceId to state) } }
        publish(ImportState.Running(ImportState.Phase.Connecting, ImportCounts()))
        // A refresh can show real progress: the previous import's size is a good estimate.
        val expected = runCatching {
            val lib = content.database.value.libraryDao()
            listOf(lib.observeLiveStreamCounts(), lib.observeMovieCounts(), lib.observeSeriesCounts())
                .sumOf { flow -> flow.first().firstOrNull { it.sourceId == sourceId }?.count ?: 0 }
        }.getOrDefault(0)
        val report: (ImportState) -> Unit = { state ->
            publish(
                if (state is ImportState.Running && expected > 0) {
                    val done = state.counts.live + state.counts.movies + state.counts.series
                    state.copy(
                        progress = when (state.phase) {
                            ImportState.Phase.Guide -> state.progress
                            ImportState.Phase.Connecting -> 0f
                            ImportState.Phase.Finishing -> 0.97f
                            else -> 0.95f * (done.toFloat() / expected).coerceAtMost(1f)
                        },
                    )
                } else {
                    state
                },
            )
        }
        try {
            val source = sourceDao.get(sourceId) ?: return
            sourceDao.markAttempt(sourceId, System.currentTimeMillis())
            val secret = credentials.get(sourceId) ?: throw SourceException(SourceErrorKind.InvalidCredentials)
            val generation = source.generation + 1
            // A refresh fetches the guide first: the stored one may have run out (the app was off
            // for a while), and the channels it matches against are already there. The content then
            // follows, updated in place. A first import needs the channels before the guide.
            val guideFirst = expected > 0
            if (guideFirst) {
                importGuide(sourceId, secret, generation) { p ->
                    publish(ImportState.Running(ImportState.Phase.Guide, ImportCounts(), p?.takeIf { it >= 0f }))
                }
                report(ImportState.Running(ImportState.Phase.Connecting, ImportCounts()))
            }
            val startedMs = android.os.SystemClock.elapsedRealtime()
            val counts = when (secret) {
                is SourceSecret.M3u -> {
                    val (counts, epgUrl) = m3uImporter.import(sourceId, secret.url, generation, report)
                    if (epgUrl != null && credentials.guideUrl(sourceId) == null) credentials.putGuideUrl(sourceId, epgUrl)
                    counts
                }
                is SourceSecret.Xtream -> xtreamImporter.import(
                    sourceId,
                    XtreamCredentials(secret.serverUrl, secret.username, secret.password),
                    generation,
                    report,
                )
            }
            sourceDao.markImported(sourceId, generation, System.currentTimeMillis())
            if (guideFirst) {
                report(ImportState.Done(counts))
                android.util.Log.i(TAG, "import source=$sourceId live=${counts.live} movies=${counts.movies} series=${counts.series} in ${android.os.SystemClock.elapsedRealtime() - startedMs} ms")
                return
            }
            report(ImportState.Done(counts, guide = ImportState.GUIDE_UNKNOWN))
            // Counts and timing only: never log URLs or credentials.
            android.util.Log.i(TAG, "import source=$sourceId live=${counts.live} movies=${counts.movies} series=${counts.series} in ${android.os.SystemClock.elapsedRealtime() - startedMs} ms")
            var lastShown = -1
            importGuide(sourceId, secret, generation) { p ->
                val percent = p?.let { (it * 100).toInt() } ?: -1
                if (percent != lastShown) {
                    lastShown = percent
                    publish(ImportState.Done(counts, guide = p ?: ImportState.GUIDE_UNKNOWN))
                }
            }
            publish(ImportState.Done(counts))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            sourceDao.setError(sourceId, e.kind.name)
            report(ImportState.Failed(e.kind))
        } catch (e: Throwable) {
            // Throwable, not Exception: whatever a source sends (even something that exhausts memory)
            // fails that source only, never the app.
            runCatching { sourceDao.setError(sourceId, SourceErrorKind.Unknown.name) }
            report(ImportState.Failed(SourceErrorKind.Unknown))
        } finally {
            withContext(NonCancellable) { content.trimLog() }
        }
    }

    private companion object {
        /** Opening the app refreshes sources older than this, in the background. */
        const val TAG = "PureImport"
        const val STALE_ON_START_MS = 12 * 60 * 60 * 1000L

        /** Automatic refreshes leave a source alone this long after its last attempt. */
        const val RETRY_AFTER_MS = 15 * 60 * 1000L
    }
}
