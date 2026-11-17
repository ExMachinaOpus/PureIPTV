package app.pureiptv.core.player

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An ExoPlayer plus the request headers of whatever it plays right now. IPTV streams often need
 * their own User-Agent/Referer (from the playlist); Media3 has no per-item headers, so every
 * request is resolved against [headers] at load time.
 */
@OptIn(UnstableApi::class)
class PurePlayer internal constructor(
    val player: ExoPlayer,
    private val headers: AtomicReference<Map<String, String>>,
) {
    private val detector = FrameRateDetector()
    private val _frameRate = MutableStateFlow<Float?>(null)

    /**
     * The frame rate of the film or episode playing, for matching the display to it. Null for live TV
     * and for video without a fixed rate. Keeps the previous value until the next video's rate is known,
     * so episodes at the same rate don't switch the display back and forth.
     */
    val frameRate: StateFlow<Float?> = _frameRate.asStateFlow()

    init {
        player.setVideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
            detector.onFrame(presentationTimeUs, format.frameRate)?.let { fps ->
                _frameRate.value = fps.takeIf { it != FrameRateDetector.UNKNOWN }
            }
        }
    }

    /**
     * Plays [url]. [asHls] forces HLS for URLs without a recognizable extension (a fallback when
     * container sniffing fails). [startMs] resumes VOD.
     */
    fun play(
        url: String,
        userAgent: String?,
        referrer: String?,
        asHls: Boolean,
        live: Boolean,
        startMs: Long,
        title: String,
        artwork: String?,
    ) {
        headers.set(
            buildMap {
                put("User-Agent", userAgent ?: PlayerFactory.DEFAULT_USER_AGENT)
                if (referrer != null) put("Referer", referrer)
            },
        )
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .apply {
                if (live) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { artwork?.let { setArtworkUri(Uri.parse(it)) } }
                    .build(),
            )
            .build()
        // Live TV keeps the display's own rate: a switch blanks the screen, and zapping must not.
        if (live) {
            detector.stop()
            _frameRate.value = null
        } else {
            detector.restart()
        }
        player.setMediaItem(item, if (live) C.TIME_UNSET else startMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun release() = player.release()
}

/**
 * The one place ExoPlayer is configured. Every screen and the playback service get their player
 * from here so behaviour (buffering, headers, codecs) is identical everywhere.
 */
@Singleton
class PlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    @OptIn(UnstableApi::class)
    fun create(): PurePlayer {
        val headers = AtomicReference<Map<String, String>>(mapOf("User-Agent" to DEFAULT_USER_AGENT))
        // No factory-level user agent: OkHttpDataSource would add it as a second header.
        val httpFactory = OkHttpDataSource.Factory(okHttpClient)
        // HTTP(S) only, on purpose: stream addresses come from playlists, and a playlist has no
        // business making the player open file://, content:// or asset:// addresses on this device.
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
            val scheme = spec.uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw java.io.IOException("Unsupported stream address")
            spec.withAdditionalHeaders(headers.get())
        }

        val renderers = object : DefaultRenderersFactory(context) {
            // FFmpeg audio after the platform decoders: a device that plays Dolby/DTS itself (or
            // passes it to the receiver, like TV boxes) keeps doing so; one that can't (many
            // phones) decodes it in software instead of playing the film silently.
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: androidx.media3.exoplayer.audio.AudioSink,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                out += FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            }
        }
            // Use platform decoders first, fall back to others if the first one fails to init.
            .setEnableDecoderFallback(true)

        // Live IPTV benefits from a slightly larger buffer than the default: fewer stalls on
        // overloaded provider servers, at the cost of ~1-2 s longer channel start in worst case.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        return PurePlayer(player, headers)
    }

    /** True when the device has a hardware HEVC decoder (most TVs; many cheap phones don't). */
    val hevcSupported: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) } &&
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
        }.getOrDefault(false)
    }

    companion object {
        /** Neutral UA. Some providers block unknown agents; per-stream UA from the playlist wins. */
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android) PureIPTV"
    }
}
