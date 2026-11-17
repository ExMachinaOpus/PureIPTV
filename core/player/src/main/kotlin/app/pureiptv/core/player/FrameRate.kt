package app.pureiptv.core.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The frame rates video is made in. A measured or declared rate snaps to the nearest of these. */
private val STANDARD_RATES = floatArrayOf(
    24_000f / 1_001, 24f, 25f, 30_000f / 1_001, 30f, 48_000f / 1_001, 48f, 50f, 60_000f / 1_001, 60f,
)

/** The nearest standard rate, or null when [fps] is none of them (variable frame rate, a bad header). */
internal fun snapFrameRate(fps: Float): Float? =
    STANDARD_RATES.minBy { abs(it - fps) }.takeIf { abs(it - fps) / it < SNAP_TOLERANCE }

/**
 * Finds the frame rate of the video playing, for matching the display to it. Uses the rate the container
 * declares (MP4) and otherwise measures the timestamps of the frames as they are shown (MKV and TS rarely
 * declare one). Fed from the playback thread, reset from the main thread.
 */
internal class FrameRateDetector {
    private val deltas = LongArray(SAMPLE_FRAMES)
    private var count = 0
    private var first = UNSET
    private var last = UNSET
    private var done = true

    /** Starts over: a new film or episode. */
    @Synchronized
    fun restart() {
        count = 0
        first = UNSET
        last = UNSET
        done = false
    }

    /** Stops measuring (live TV). */
    @Synchronized
    fun stop() {
        done = true
    }

    /**
     * One frame about to be shown. Returns the result once: the frame rate, or [UNKNOWN] when the video
     * has none (variable frame rate). Null while measuring and after the result.
     */
    @Synchronized
    fun onFrame(presentationTimeUs: Long, declaredFps: Float): Float? {
        if (done) return null
        if (declaredFps > 0) snapFrameRate(declaredFps)?.let { return finish(it) }
        if (last != UNSET) {
            val delta = presentationTimeUs - last
            // A seek (or a gap in the stream): start the sample again from here.
            if (delta <= 0 || delta > MAX_FRAME_GAP_US) {
                count = 0
                first = presentationTimeUs
            } else {
                deltas[count++] = delta
            }
        } else {
            first = presentationTimeUs
        }
        last = presentationTimeUs
        return if (count == SAMPLE_FRAMES) finish(measure() ?: UNKNOWN) else null
    }

    private fun finish(fps: Float): Float {
        done = true
        return fps
    }

    /**
     * The rate from the whole span of the sample, so millisecond timestamps (MKV) still tell 23.976 from 24.
     * Frames the player dropped show up as a gap of two or more frame times and are counted as such; a
     * gap that is no whole number of frames means the video has no fixed rate.
     */
    private fun measure(): Float? {
        val frameUs = deltas.sorted()[SAMPLE_FRAMES / 2].toDouble()
        var frames = 0L
        for (delta in deltas) {
            val n = (delta / frameUs).roundToLong()
            if (n < 1 || abs(delta - n * frameUs) > frameUs * FRAME_JITTER) return null
            frames += n
        }
        val fps = frames * 1_000_000.0 / (last - first)
        return snapFrameRate(fps.toFloat())
    }

    companion object {
        /** Measured but no fixed rate: show it at the display's own rate. */
        const val UNKNOWN = 0f
    }
}

/**
 * The refresh rate (from the display's [available] ones) that shows [fps] video evenly: [home] (the
 * display's own) when it already does (30 fps on 60 Hz), else the lowest that is a whole multiple (24 Hz
 * for 24 fps, 50 Hz for 25 fps). Null when none fits.
 */
fun refreshRateFor(fps: Float, available: List<Float>, home: Float): Float? {
    fun fits(refresh: Float): Boolean {
        val multiple = refresh / fps
        val whole = multiple.roundToInt()
        return whole >= 1 && abs(multiple - whole) / whole < REFRESH_TOLERANCE
    }
    if (fits(home)) return home
    return available.filter(::fits).minOrNull()
}

/** Frames measured before deciding: about 2.5 s of film. */
private const val SAMPLE_FRAMES = 60

/** Longer than any frame time; a bigger jump between two frames is a seek. */
private const val MAX_FRAME_GAP_US = 250_000L

/** How far one frame's timestamp may be off (rounding, encoder jitter), as a share of a frame time. */
private const val FRAME_JITTER = 0.25

/**
 * 23.976 and 24 are 0.1 % apart; within half of that a rate is one of them and not the other. Millisecond
 * timestamps over the sample are at most 1 ms (0.04 %) off.
 */
private const val SNAP_TOLERANCE = 0.0005f

/** Display refresh rates are reported as e.g. 23.976025 Hz. */
private const val REFRESH_TOLERANCE = 0.0004f

private const val UNSET = Long.MIN_VALUE
