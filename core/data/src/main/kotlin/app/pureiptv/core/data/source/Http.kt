package app.pureiptv.core.data.source

import app.pureiptv.core.model.SourceErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** A failure with a user-facing category. The UI maps [kind] to a plain-language message. */
class SourceException(val kind: SourceErrorKind, cause: Throwable? = null) : Exception(kind.name, cause)

/**
 * How much the app accepts from a source. Far above anything a real provider sends (the largest
 * seen: 100k+ entries, guides of a few hundred MB), and there so that a broken or hostile server
 * can't fill the device's storage or memory, or keep an import running forever.
 */
internal object ImportLimits {
    /** One playlist or one Xtream list, as downloaded. */
    const val MAX_LIST_BYTES = 1L shl 30
    /** One guide, as downloaded (usually gzip). */
    const val MAX_GUIDE_BYTES = 2L shl 30
    /** Entries in one playlist or one Xtream list. */
    const val MAX_ITEMS = 2_000_000L
    /** Programmes stored from one guide; the rest of the guide is ignored. */
    const val MAX_PROGRAMMES = 5_000_000
    /** Answers read into memory at once: login, one series, one movie. */
    const val MAX_SMALL_BODY_BYTES = 16L shl 20
}

/** The body as text, for answers that are small by nature. A larger one is not what was asked for. */
internal fun Response.boundedString(maxBytes: Long = ImportLimits.MAX_SMALL_BODY_BYTES): String {
    if (body.source().request(maxBytes + 1)) throw SourceException(SourceErrorKind.NotAPlaylist)
    return body.string()
}

/** The same response, failing with [SourceException] once more than [maxBytes] have been read. */
private fun Response.capped(maxBytes: Long): Response {
    val original = body
    val limited = object : ForwardingSource(original.source()) {
        private var total = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0) {
                total += n
                if (total > maxBytes) throw SourceException(SourceErrorKind.NotAPlaylist)
            }
            return n
        }
    }
    return newBuilder().body(limited.buffer().asResponseBody(original.contentType(), original.contentLength())).build()
}

private fun OkHttpClient.open(url: String): Response {
    val request = try {
        Request.Builder().url(url).header("Accept", "*/*").build()
    } catch (e: IllegalArgumentException) {
        throw SourceException(SourceErrorKind.NotAPlaylist, e) // not a valid http(s) URL
    }
    return try {
        newCall(request).execute()
    } catch (e: IOException) {
        throw SourceException(SourceErrorKind.Unreachable, e)
    }
}

/** GET, then [block] reads the open response on the IO dispatcher. Cancellation interrupts the read. */
internal suspend fun <T> OkHttpClient.get(url: String, block: (Response) -> T): T =
    runInterruptible(Dispatchers.IO) {
        open(url).use {
            try {
                block(it)
            } catch (e: IOException) {
                throw SourceException(SourceErrorKind.Unreachable, e)
            }
        }
    }

/**
 * GET a large list and emit it in chunks while it downloads: the consumer writes chunk N to the
 * database while chunk N+1 is parsed, and the full list never sits in memory. Stops with
 * [SourceException] beyond [maxBytes] downloaded or [maxItems] parsed (see [ImportLimits]).
 */
internal fun <T> OkHttpClient.streamChunks(
    url: String,
    chunkSize: Int = 500,
    maxBytes: Long = ImportLimits.MAX_LIST_BYTES,
    maxItems: Long = ImportLimits.MAX_ITEMS,
    parse: (Response) -> Sequence<T>,
): Flow<List<T>> = flow {
    open(url).use { response ->
        if (!response.isSuccessful) throw SourceException(httpErrorKind(response.code))
        try {
            var items = 0L
            parse(response.capped(maxBytes)).chunked(chunkSize).forEach {
                items += it.size
                if (items > maxItems) throw SourceException(SourceErrorKind.NotAPlaylist)
                emit(it)
            }
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.Unreachable, e)
        } catch (e: SerializationException) {
            throw SourceException(SourceErrorKind.NotAPlaylist, e)
        }
    }
}.flowOn(Dispatchers.IO)

/** Maps an unsuccessful HTTP status to the most likely user-facing cause. */
internal fun httpErrorKind(code: Int): SourceErrorKind = when (code) {
    401, 403 -> SourceErrorKind.InvalidCredentials
    404, 410 -> SourceErrorKind.NotAPlaylist
    in 500..599 -> SourceErrorKind.Unreachable
    else -> SourceErrorKind.Unknown
}

/** Host for display, without credentials or path. */
internal fun displayHost(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url.substringBefore('/').take(60)
