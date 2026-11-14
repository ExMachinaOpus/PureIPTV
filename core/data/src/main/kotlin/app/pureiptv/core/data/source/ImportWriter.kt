package app.pureiptv.core.data.source

import app.pureiptv.core.data.db.CategoryEntity
import app.pureiptv.core.data.db.ChannelEntity
import app.pureiptv.core.data.db.ImportDao
import app.pureiptv.core.data.db.LiveStreamEntity
import app.pureiptv.core.model.ContentKind
import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.model.ParsedName
import app.pureiptv.core.model.StreamHeaders
import app.pureiptv.core.parsing.names.ChannelNameParser
import app.pureiptv.core.parsing.names.GenreClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A live entry from any source format, before it is matched to a channel. */
internal data class LiveEntry(
    val providerKey: String,
    val rawName: String,
    val categoryName: String?,
    val categoryId: Long?,
    val tvgId: String?,
    /** Language stated in a defined field. For browsing only: matching goes by the name (identityKey). */
    val declaredLanguage: String? = null,
    val logo: String?,
    val url: String?,
    val headers: StreamHeaders,
    val adult: Boolean,
    val catchupDays: Int,
)

/**
 * Shared write path for all importers: categories, channel matching (duplicate detection) and
 * batched stream inserts. One instance per import run.
 */
internal class ImportWriter(
    private val dao: ImportDao,
    private val sourceId: Long,
    private val generation: Long,
) {
    private val categoryIds = HashMap<Pair<ContentKind, String>, Long>()
    private var categoryOrder = 0
    private var streamOrder = 0

    /** Inserts categories in one batch; remembers their row ids for [categoryId]. */
    suspend fun addCategories(kind: ContentKind, categories: List<Pair<String, String>>) {
        if (categories.isEmpty()) return
        val rows = categories.map { (providerId, name) -> categoryRow(kind, providerId, name) }
        dao.upsertCategories(sourceId, kind.name, rows).forEach { (providerId, id) -> categoryIds[kind to providerId] = id }
    }

    /** Category id for [providerId], creating it on first sight (M3U groups appear as we parse). */
    suspend fun categoryId(kind: ContentKind, providerId: String?, name: String? = providerId): Long? {
        if (providerId.isNullOrBlank()) return null
        categoryIds[kind to providerId]?.let { return it }
        val id = dao.upsertCategories(sourceId, kind.name, listOf(categoryRow(kind, providerId, name ?: providerId))).getValue(providerId)
        categoryIds[kind to providerId] = id
        return id
    }

    private fun categoryRow(kind: ContentKind, providerId: String, name: String): CategoryEntity {
        val region = ChannelNameParser.categoryRegion(name)
        return CategoryEntity(
            sourceId = sourceId,
            kind = kind.name,
            providerId = providerId,
            name = name,
            country = region.country,
            language = region.language,
            genre = GenreClassifier.classify(name, "").name,
            hidden = ChannelNameParser.isAdult(name),
            sortOrder = categoryOrder++,
            generation = generation,
        )
    }

    /** Matches a chunk of live entries to channels and inserts their streams. Returns entries kept. */
    suspend fun addLive(entries: List<LiveEntry>): Int {
        // Name parsing is the CPU-heavy part of an import: spread it over all cores.
        val rows = coroutineScope {
            entries.chunked(PARSE_SLICE).map { slice ->
                async(Dispatchers.Default) {
                    slice.mapNotNull { e ->
                        val name = ChannelNameParser.parse(e.rawName, e.categoryName, e.tvgId)
                        if (NameFlag.Separator in name.flags) null else Triple(e, name, channelRow(e, name))
                    }
                }
            }.awaitAll().flatten()
        }
        val parsed = rows.map { (e, name, _) -> e to name }
        if (parsed.isEmpty()) return 0

        val channels = rows.map { it.third }.distinctBy { it.identityKey }
        dao.insertChannelsIfAbsent(channels)
        val ids = dao.channelIds(channels.map { it.identityKey }).associate { it.identityKey to it.id }

        // A refresh rewrites every stream. Streams matched to the guide by name (no tvg-id) would lose
        // that link until the new guide is in (minutes, at the end of the refresh), and their channels
        // would show no "now". Keep the earlier match; the new guide import updates it.
        val earlierGuide = dao.existingEpgKeys(sourceId, parsed.map { it.first.providerKey }).associate { it.providerKey to it.epgKey }
        val streams = parsed.mapNotNull { (e, name) ->
            val channelId = ids[identityKey(e, name)] ?: return@mapNotNull null
            LiveStreamEntity(
                sourceId = sourceId,
                channelId = channelId,
                categoryId = e.categoryId,
                providerKey = e.providerKey,
                rawName = e.rawName,
                url = e.url,
                quality = name.quality.ordinal,
                flags = flagBits(name, e.adult),
                userAgent = e.headers.userAgent,
                referrer = e.headers.referrer,
                catchupDays = e.catchupDays,
                sortOrder = streamOrder++,
                generation = generation,
                epgKey = e.tvgId?.takeIf { it.isNotBlank() }?.let(::epgKey) ?: earlierGuide[e.providerKey],
            )
        }
        dao.upsertLiveStreams(sourceId, streams)
        return streams.size
    }

    private fun channelRow(e: LiveEntry, name: ParsedName) = ChannelEntity(
        identityKey = identityKey(e, name),
        displayName = name.displayName,
        matchKey = name.matchKey,
        country = name.country,
        language = e.declaredLanguage ?: name.language,
        region = regionKey(name.country, e.declaredLanguage ?: name.language),
        genre = GenreClassifier.classify(e.categoryName, name.displayName).name,
        logo = e.logo,
        tvgId = e.tvgId,
        flags = flagBits(name, e.adult),
    )

    suspend fun finish() {
        dao.deleteStaleCategories(sourceId, generation)
        dao.deleteStaleLiveStreams(sourceId, generation)
        dao.deleteStaleMovies(sourceId, generation)
        dao.deleteStaleSeries(sourceId, generation)
        dao.deleteOrphanChannels()
        dao.updateChannelStats()
    }

    private fun identityKey(e: LiveEntry, name: ParsedName) = identityKey(sourceId, e.tvgId, name)

    companion object {
        /**
         * Duplicate key (dev/architecture.md, "Duplicate detection"):
         * - known country/language: `GB|bbcone` merges across sources and qualities;
         * - else with a guide id: merges entries sharing guide id AND cleaned name (guide id alone is
         *   not enough: providers reuse one guide id for regional variants);
         * - else: merges only within the same source (e.g. HD/SD copies), never across sources.
         */
        internal fun identityKey(sourceId: Long, tvgId: String?, name: ParsedName): String = when {
            name.country != null || name.language != null -> name.identityKey
            tvgId != null -> "tvg:${ChannelNameParser.normalizeTvgId(tvgId)}|${name.matchKey}"
            else -> "src$sourceId|${name.matchKey}"
        }

        private const val PARSE_SLICE = 125

        /** Guide key for a tvg-id / XMLTV channel id: case-insensitive, quality feed (`@HD`) ignored. */
        fun epgKey(id: String): String = ChannelNameParser.normalizeTvgId(id.trim())

        /** Browse group for a channel: country, else `@language`, else "" (shown as "Other"). */
        fun regionKey(country: String?, language: String?): String = country ?: language?.let { "@$it" } ?: ""

        internal fun flagBits(name: ParsedName, adult: Boolean): Int {
            var bits = name.flags.fold(0) { acc, f -> acc or (1 shl f.ordinal) }
            if (adult) bits = bits or (1 shl NameFlag.Adult.ordinal)
            return bits
        }
    }
}
