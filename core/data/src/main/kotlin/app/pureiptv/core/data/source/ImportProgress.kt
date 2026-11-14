package app.pureiptv.core.data.source

import app.pureiptv.core.model.SourceErrorKind

data class ImportCounts(val live: Int = 0, val movies: Int = 0, val series: Int = 0)

sealed interface ImportState {
    /**
     * [phase] says what is being downloaded right now; [counts] grows as chunks are written.
     * [progress] 0..1 when it can be estimated (a refresh: the previous import's size), else null.
     */
    data class Running(val phase: Phase, val counts: ImportCounts, val progress: Float? = null) : ImportState

    /**
     * Content is ready. [guide] is set while the TV guide still downloads afterwards, on a first import
     * (a refresh fetches the guide first, [Phase.Guide]): its progress 0..1, or [GUIDE_UNKNOWN] when it
     * can't be estimated.
     */
    data class Done(val counts: ImportCounts, val guide: Float? = null) : ImportState
    data class Failed(val kind: SourceErrorKind) : ImportState

    enum class Phase { Guide, Connecting, LiveTv, Movies, Series, Finishing }

    companion object {
        const val GUIDE_UNKNOWN = -1f
    }
}
