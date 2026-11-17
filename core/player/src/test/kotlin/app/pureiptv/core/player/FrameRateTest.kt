package app.pureiptv.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.roundToLong

class FrameRateTest {

    /** Feeds frames at [fps] from [startUs] and returns the result, if any. */
    private fun FrameRateDetector.feed(fps: Double, frames: Int, startUs: Long = 0, roundToMs: Boolean = false, skip: (Int) -> Boolean = { false }): Float? {
        var result: Float? = null
        for (i in 0 until frames) {
            if (skip(i)) continue
            var us = startUs + (i * 1_000_000.0 / fps).roundToLong()
            if (roundToMs) us = (us / 1_000.0).roundToLong() * 1_000
            onFrame(us, -1f)?.let { result = it }
        }
        return result
    }

    private fun detector() = FrameRateDetector().apply { restart() }

    @Test
    fun `MKV millisecond timestamps tell 23,976 from 24`() {
        assertThat(detector().feed(24_000.0 / 1_001, 80, roundToMs = true)).isWithin(0.001f).of(23.976f)
        assertThat(detector().feed(24.0, 80, roundToMs = true)).isEqualTo(24f)
    }

    @Test
    fun `measures the common rates`() {
        assertThat(detector().feed(25.0, 80)).isEqualTo(25f)
        assertThat(detector().feed(30.0, 80, roundToMs = true)).isEqualTo(30f)
        assertThat(detector().feed(50.0, 80)).isEqualTo(50f)
        assertThat(detector().feed(60_000.0 / 1_001, 80)).isWithin(0.001f).of(59.94f)
    }

    @Test
    fun `frames the player dropped don't change the rate`() {
        assertThat(detector().feed(24_000.0 / 1_001, 90, roundToMs = true) { it % 7 == 3 }).isWithin(0.001f).of(23.976f)
    }

    @Test
    fun `a seek while measuring starts the sample again`() {
        val d = detector()
        assertThat(d.feed(25.0, 20)).isNull()
        assertThat(d.feed(25.0, 80, startUs = 600_000_000)).isEqualTo(25f)
    }

    @Test
    fun `variable frame rate is unknown`() {
        val d = detector()
        var us = 0L
        var result: Float? = null
        for (i in 0 until 80) {
            us += if (i % 3 == 0) 41_708 else 27_000
            d.onFrame(us, -1f)?.let { result = it }
        }
        assertThat(result).isEqualTo(FrameRateDetector.UNKNOWN)
    }

    @Test
    fun `a declared rate is used at once, the result comes once`() {
        val d = detector()
        assertThat(d.onFrame(0, 23.976025f)).isWithin(0.001f).of(23.976f)
        assertThat(d.onFrame(41_708, 23.976025f)).isNull()
    }

    @Test
    fun `a declared rate that is no standard one is measured instead`() {
        val d = detector()
        assertThat(d.onFrame(0, 23.5f)).isNull()
    }

    @Test
    fun `stopped (live TV) reports nothing`() {
        val d = FrameRateDetector()
        assertThat(d.feed(25.0, 80)).isNull()
    }

    @Test
    fun `refresh rate keeps the display's own when it fits`() {
        val modes = listOf(23.976f, 24f, 25f, 50f, 59.94f, 60f)
        assertThat(refreshRateFor(30f, modes, home = 60f)).isEqualTo(60f)
        assertThat(refreshRateFor(60f, modes, home = 60f)).isEqualTo(60f)
    }

    @Test
    fun `refresh rate is the lowest whole multiple otherwise`() {
        val modes = listOf(23.976025f, 24f, 50f, 59.94006f, 60f)
        assertThat(refreshRateFor(24_000f / 1_001, modes, home = 60f)).isEqualTo(23.976025f)
        assertThat(refreshRateFor(24f, modes, home = 60f)).isEqualTo(24f)
        assertThat(refreshRateFor(25f, modes, home = 60f)).isEqualTo(50f)
        assertThat(refreshRateFor(24f, listOf(60f, 120f), home = 60f)).isEqualTo(120f)
    }

    @Test
    fun `no fitting mode is null`() {
        assertThat(refreshRateFor(24f, listOf(23.976f, 60f), home = 60f)).isNull()
        assertThat(refreshRateFor(25f, listOf(60f), home = 60f)).isNull()
    }
}
