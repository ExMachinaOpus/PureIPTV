package app.pureiptv.core.data.source

import app.pureiptv.core.data.db.ContentStore
import app.pureiptv.core.data.db.ProgrammeEntity
import app.pureiptv.core.parsing.names.ChannelNameParser
import app.pureiptv.core.parsing.xmltv.XmltvChannel
import app.pureiptv.core.parsing.xmltv.XmltvParser
import app.pureiptv.core.parsing.xmltv.XmltvProgramme
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject

/**
 * Imports a source's XMLTV guide while it downloads. Only programmes of channels the source
 * actually has, and only a short window around now, are stored: provider guides are often
 * hundreds of MB and cover channels the playlist doesn't even contain.
 */
class EpgImporter @Inject constructor(
    private val http: OkHttpClient,
    private val content: ContentStore,
    private val parser: XmltvParser,
) {
    /** @return number of programmes stored. Throws [SourceException] when the guide can't be read. */
    suspend fun import(
        sourceId: Long,
        url: String,
        generation: Long,
        nowMs: Long = System.currentTimeMillis(),
        /** 0..1, or null when the size is unknown (no Content-Length and no previous guide). */
        onProgress: (Float?) -> Unit = {},
    ): Int {
        val dao = content.importDao
        val previous = dao.programmeCount(sourceId)
        val read = java.util.concurrent.atomic.AtomicLong()
        var length = -1L
        val wanted = HashSet(dao.epgKeysOf(sourceId))
        // Channels without a guide id may still be found by their exact cleaned name.
        val byName = HashSet(dao.unguidedMatchKeys(sourceId))
        val from = nowMs - KEEP_PAST_MS
        val until = nowMs + KEEP_FUTURE_MS
        var stored = 0
        var unmatched = 0

        // Every programme in the file is parsed (most are skipped), so only bytes and stored rows are capped.
        http.streamChunks(url, chunkSize = 1_000, maxBytes = ImportLimits.MAX_GUIDE_BYTES, maxItems = Long.MAX_VALUE) { response ->
            length = response.body.contentLength()
            parser.parse(unwrap(Counting(response.body.byteStream(), read)))
        }
            .collect { chunk ->
                val rows = ArrayList<ProgrammeEntity>(chunk.size)
                for (item in chunk) {
                    when (item) {
                        is XmltvChannel -> {
                            val key = ImportWriter.epgKey(item.id)
                            if (key in wanted || byName.isEmpty()) continue
                            val names = item.displayNames.map { ChannelNameParser.parse(it).matchKey }.filter { it in byName }
                            for (name in names) {
                                dao.setEpgKeyByName(sourceId, name, key)
                                byName.remove(name)
                                wanted += key
                            }
                        }
                        is XmltvProgramme -> {
                            val key = ImportWriter.epgKey(item.channelId)
                            if (stored + rows.size >= ImportLimits.MAX_PROGRAMMES) continue
                            // Channels the source doesn't have (yet) are kept too, within a smaller
                            // budget: the content refresh that follows the guide may add them. Broad
                            // third-party guides cover thousands of channels a source never has.
                            if (key !in wanted) {
                                if (unmatched >= MAX_UNMATCHED_PROGRAMMES) continue
                                unmatched++
                            }
                            val end = item.stopEpochMs ?: (item.startEpochMs + DEFAULT_LENGTH_MS)
                            if (end < from || item.startEpochMs > until) continue
                            rows += ProgrammeEntity(
                                sourceId = sourceId,
                                channelKey = key,
                                startMs = item.startEpochMs,
                                endMs = end,
                                title = item.title,
                                subtitle = item.subtitle,
                                description = item.description,
                                episode = item.episode,
                                searchKey = ChannelNameParser.matchKey(
                                    if (item.subtitle == null) item.title else item.title + " " + item.subtitle,
                                ),
                                generation = generation,
                            )
                        }
                    }
                }
                if (rows.isNotEmpty()) {
                    dao.insertProgrammes(rows)
                    stored += rows.size
                }
                onProgress(
                    when {
                        length > 0 -> (read.get().toFloat() / length).coerceIn(0f, 0.99f)
                        previous > 0 -> (stored.toFloat() / previous).coerceIn(0f, 0.99f)
                        else -> null
                    },
                )
            }
        dao.deleteStaleProgrammes(sourceId, generation)
        dao.deleteEndedProgrammes(from)
        return stored
    }

    /** Guides are often served as .xml.gz without a gzip Content-Encoding: detect by magic bytes. */
    private fun unwrap(raw: InputStream): InputStream {
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024) else input
    }

    /** Counts the raw bytes read (before gunzip), for progress against Content-Length. */
    private class Counting(input: InputStream, private val count: java.util.concurrent.atomic.AtomicLong) : java.io.FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) count.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count.addAndGet(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { count.addAndGet(it) }
    }

    private companion object {
        const val KEEP_PAST_MS = 2 * 60 * 60 * 1000L
        const val KEEP_FUTURE_MS = 3 * 24 * 60 * 60 * 1000L

        /** Programmes kept for guide ids no stream has: about 1,000 channels over the stored window. */
        const val MAX_UNMATCHED_PROGRAMMES = 200_000
        const val DEFAULT_LENGTH_MS = 60 * 60 * 1000L
    }
}
