// Media3's track formats and SubtitleView are marked unstable; the app pins its Media3 version.
@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package app.pureiptv.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaSession
import app.pureiptv.core.data.di.AppScope
import app.pureiptv.core.data.library.BrowseRepository
import app.pureiptv.core.data.library.LiveFilter
import app.pureiptv.core.data.library.PlayItem
import app.pureiptv.core.data.library.PlaybackRepository
import app.pureiptv.core.data.library.Progress
import app.pureiptv.core.data.library.TrackChoice
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.data.library.Zoom
import app.pureiptv.core.player.PlayerFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.pureiptv.core.data.db.ChannelRow
import app.pureiptv.core.data.db.ProgrammeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What to play. Row ids are fine here: a play request never outlives the session. */
sealed interface PlayRequest {
    data class Channel(val channelId: Long, val filter: LiveFilter?) : PlayRequest
    data class Movie(val movieId: Long, val fromStart: Boolean = false) : PlayRequest
    /**
     * [startMs]: start there (switching versions mid-episode). [exact]: this episode row, not the copy
     * picked for its series. [fallback]: another copy after this one failed (keeps what was tried).
     */
    data class Episode(
        val episodeId: Long,
        val fromStart: Boolean = false,
        val startMs: Long? = null,
        val exact: Boolean = false,
        val fallback: Boolean = false,
    ) : PlayRequest
}

data class PlayerUi(
    val item: PlayItem? = null,
    /** Index into item.streams currently tried. */
    val streamIndex: Int = 0,
    /** Every stream failed (or there are none): show the plain-language message + retry. */
    val failed: Boolean = false,
    /** Item vanished (source removed/refreshed meanwhile). */
    val notFound: Boolean = false,
    /** Live: 1-based position in the current list and its size, for "12 / 340". */
    val channelNumber: Int? = null,
    val channelCount: Int? = null,
    val favorite: Boolean = false,
    /** VOD finished with nothing to play next: leave the player. */
    val finished: Boolean = false,
    /** Remembered per feed (PlayableStream.streamKey). */
    val zoom: Zoom = Zoom.Fit,
    /** Live: position highlighted in the flip list while the user presses Up/Down (0-based). */
    val zapIndex: Int? = null,
    /** Live: now, next, later (empty without guide data). */
    val guide: List<ProgrammeEntity> = emptyList(),
    /** An episode ended: the next one is about to start (or, after a long unattended run, asks first). */
    val upNext: UpNext? = null,
    /** Audio tracks of what plays now (one entry = nothing to choose, but still shown as info). */
    val audio: List<TrackInfo> = emptyList(),
    /** Subtitle tracks; none selected = off (the default). */
    val subtitles: List<TrackInfo> = emptyList(),
) {
    /** Position of what plays in item.versions, or -1. */
    val versionIndex: Int
        get() {
            val item = item ?: return -1
            val key = item.streams.getOrNull(streamIndex)?.streamKey ?: return -1
            return item.versions.indexOfFirst { it.streamKey == key }
        }
}

/**
 * One audio or subtitle track, with everything the stream says about it. [codec] is the MIME type,
 * [codecs] the RFC 6381 string (e.g. "mp4a.40.2" = AAC-LC). Numbers are 0 when the stream doesn't
 * say (common for TS and MKV).
 */
data class TrackInfo(
    val id: Int,
    val language: String?,
    /** The track's own name, e.g. "Commentary" or "SDH". */
    val label: String?,
    val codec: String?,
    val codecs: String?,
    val channels: Int,
    val bitrate: Int,
    val sampleRate: Int,
    val isDefault: Boolean,
    val isForced: Boolean,
    /** C.ROLE_FLAG_* bits (commentary, description, hearing impaired...). */
    val roles: Int,
    val selected: Boolean,
)

/** [secondsLeft] counts down to autoplay; null = "Still watching?" (waits for OK). */
data class UpNext(val episodeId: Long, val label: String, val secondsLeft: Int?)

/**
 * Owns the player for one playback screen: resolves streams, fails over to the next stream when
 * one breaks or hangs, zaps through the channel list, saves progress and history.
 */
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted request: PlayRequest,
    @ApplicationContext context: Context,
    private val factory: PlayerFactory,
    private val playback: PlaybackRepository,
    private val browse: BrowseRepository,
    private val user: UserRepository,
    @AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(request: PlayRequest): PlayerViewModel
    }

    private val pure = factory.create()
    val player: Player get() = pure.player

    /** The film's or episode's frame rate, for [MatchFrameRate]; null for live TV. */
    val frameRate: StateFlow<Float?> get() = pure.frameRate

    /** Media session: system "now playing", play/pause from the remote, Google Assistant. */
    private val session = MediaSession.Builder(context, pure.player).setId("player-${System.nanoTime()}").build()

    /** The list the channel was started from, for the side panel. */
    val channelList: Flow<PagingData<ChannelRow>> =
        (request as? PlayRequest.Channel)?.filter?.let { browse.channels(it).cachedIn(viewModelScope) } ?: emptyFlow()

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var request: PlayRequest = request
    private var channelIds: List<Long>? = null
    private var triedAsHls = false
    private var startedOnce = false

    /** The saved resume point applies to the first start only; later ones continue from where it stopped. */
    private var firstStart = true
    private var retriedSame = false
    private var watchdog: Job? = null
    private var favoriteJob: Job? = null

    /** Versions (choice keys) that failed for what the user opened; kept across episode fallbacks. */
    private val failedChoices = mutableSetOf<String>()

    /** Episodes (in other copies of the series) already tried for what the user opened. */
    private val triedEpisodes = mutableSetOf<Long>()

    init {
        pure.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onStreamFailed(error)

            override fun onTracksChanged(tracks: Tracks) = readTracks(tracks)

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        watchdog?.cancel()
                        if (!startedOnce) {
                            startedOnce = true
                            val item = _ui.value.item ?: return
                            viewModelScope.launch { user.recordWatched(*listOfNotNull(item.itemKey, item.parentKey).toTypedArray()) }
                            forgetDeadPick(item)
                        }
                    }
                    Player.STATE_ENDED -> onEnded()
                    else -> Unit
                }
            }
        })
        load(request)
        viewModelScope.launch { saveProgressLoop() }
    }

    private fun load(request: PlayRequest) {
        this.request = request
        // A picked track belongs to the previous title; subtitles start off. A channel's remembered
        // choice is applied below, once the item is known.
        pure.player.trackSelectionParameters = pure.player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        retriedSame = false
        if (!(request is PlayRequest.Episode && request.fallback)) {
            failedChoices.clear()
            triedEpisodes.clear()
        }
        viewModelScope.launch {
            val item = when (request) {
                is PlayRequest.Channel -> playback.channel(request.channelId, factory.hevcSupported)
                is PlayRequest.Movie -> playback.movie(request.movieId)?.let { if (request.fromStart) it.copy(resumeMs = 0) else it }
                is PlayRequest.Episode -> playback.episode(request.episodeId, preferred = !request.exact)?.let {
                    when {
                        request.startMs != null -> it.copy(resumeMs = request.startMs)
                        request.fromStart -> it.copy(resumeMs = 0)
                        else -> it
                    }
                }
            }
            if (item == null) {
                _ui.update { it.copy(notFound = true) }
                return@launch
            }
            // The audio language and subtitles chosen on this channel or series before (as languages, so
            // they also apply to backup streams and the next episode). Films start with the defaults.
            val choice = trackMemoryKey(item)?.let { user.trackChoice(it) }
            trackChoice = choice ?: TrackChoice()
            pure.player.trackSelectionParameters = pure.player.trackSelectionParameters.buildUpon()
                .clearOverrides() // track picks of the previous channel or episode
                .setPreferredAudioLanguage(choice?.audioLanguage)
                .setPreferredTextLanguage(choice?.subtitleLanguage)
                .setSelectUndeterminedTextLanguage(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choice?.subtitleLanguage == null)
                .build()
            // Here and not earlier: the previous title can still report "ready" until it is replaced.
            startedOnce = false
            firstStart = true
            _ui.update { it.copy(item = item, streamIndex = 0, failed = item.streams.isEmpty(), notFound = false) }
            observeFavorite(item.itemKey)
            // The episode that plays (the series' picked copy can differ from the one asked for).
            item.versions.firstOrNull { it.streamKey == item.streams.firstOrNull()?.streamKey }?.episodeId?.let { triedEpisodes += it }
            if (request is PlayRequest.Channel) {
                updateChannelNumber(request)
                watchGuide(request.channelId)
            }
            playStream(0)
            if (request is PlayRequest.Episode) findEpisodeVersions(item)
        }
    }

    private var versionsJob: Job? = null

    /**
     * Copies of the series whose episodes weren't loaded yet may hold this episode too: load them
     * while it plays (one request per copy) and add what they have to the versions.
     */
    private fun findEpisodeVersions(item: PlayItem) {
        val key = item.streams.firstOrNull()?.streamKey ?: return
        val episodeId = item.versions.firstOrNull { it.streamKey == key }?.episodeId ?: return
        versionsJob?.cancel()
        versionsJob = viewModelScope.launch {
            val versions = runCatching { playback.episodeVersions(episodeId, fetch = true) }.getOrNull() ?: return@launch
            _ui.update { ui -> if (ui.item?.streams?.firstOrNull()?.streamKey == key) ui.copy(item = ui.item.copy(versions = versions)) else ui }
        }
    }

    /**
     * The next version of what plays (another copy, quality or source), remembered for this
     * channel, film or series. Films and episodes continue where they were.
     */
    fun nextVersion() {
        val ui = _ui.value
        val item = ui.item ?: return
        if (item.versions.size < 2) return
        val next = item.versions[(ui.versionIndex + 1).mod(item.versions.size)]
        val position = pure.player.currentPosition
        viewModelScope.launch {
            // Saved before switching: an episode in another copy is loaded by looking the choice up.
            user.setVersionChoice(versionMemoryKey(item), next.choiceKey)
            val episodeId = next.episodeId
            if (episodeId != null) {
                saveProgressNow()
                load(PlayRequest.Episode(episodeId, startMs = position, exact = true))
            } else {
                // The picked one first; the others stay behind it for failover.
                val streams = item.streams.sortedBy { if (it.streamKey == next.streamKey) 0 else 1 }
                _ui.update { it.copy(item = item.copy(streams = streams)) }
                retriedSame = false
                startedOnce = false
                playStream(0)
            }
        }
    }

    /** Where the picked version is kept: per channel, film or series (all its episodes). */
    private fun versionMemoryKey(item: PlayItem): String = item.parentKey ?: item.itemKey

    private var guideJob: Job? = null

    /** Keeps now/next current while a channel plays. */
    private fun watchGuide(channelId: Long) {
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            while (isActive) {
                val guide = browse.upcoming(channelId)
                _ui.update { it.copy(guide = guide) }
                val nextChange = guide.firstOrNull()?.endMs?.minus(System.currentTimeMillis()) ?: 300_000
                delay(nextChange.coerceIn(5_000, 300_000))
            }
        }
    }

    private fun observeFavorite(itemKey: String) {
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            user.isFavorite(itemKey).collect { fav -> _ui.update { it.copy(favorite = fav) } }
        }
    }

    private suspend fun updateChannelNumber(request: PlayRequest.Channel) {
        val filter = request.filter ?: return
        val ids = channelIds ?: browse.channelIds(filter).also { channelIds = it }
        val index = ids.indexOf(request.channelId)
        _ui.update { it.copy(channelNumber = if (index >= 0) index + 1 else null, channelCount = ids.size) }
    }

    private fun playStream(index: Int, asHls: Boolean = false) {
        val item = _ui.value.item ?: return
        val stream = item.streams.getOrNull(index) ?: run {
            _ui.update { it.copy(failed = true) }
            pure.player.stop()
            return
        }
        triedAsHls = asHls
        _ui.update { it.copy(streamIndex = index, failed = false) }
        viewModelScope.launch {
            val zoom = user.zoom(stream.streamKey)
            _ui.update { if (it.item?.streams?.getOrNull(it.streamIndex)?.streamKey == stream.streamKey) it.copy(zoom = zoom) else it }
        }
        val resume = when {
            item.isLive -> 0
            firstStart -> item.resumeMs
            else -> pure.player.currentPosition
        }
        firstStart = false
        pure.play(stream.url, stream.headers.userAgent, stream.headers.referrer, asHls, item.isLive, resume, item.title, item.artwork)
        // A dead IPTV stream often just hangs instead of failing: give it a fair chance, then move on.
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(STALL_TIMEOUT_MS)
            if (pure.player.playbackState == Player.STATE_BUFFERING && !startedOnce) onStreamFailed(null)
        }
    }

    private fun onStreamFailed(error: PlaybackException?) {
        val index = _ui.value.streamIndex
        when {
            // Live window moved on (paused too long): just jump back to live.
            error?.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                pure.player.seekToDefaultPosition()
                pure.player.prepare()
            }
            // Unknown container: many playlists serve HLS without a .m3u8 extension.
            error?.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !triedAsHls ->
                playStream(index, asHls = true)
            // It was playing and the connection dropped: reconnect to the same stream once.
            startedOnce && !retriedSame -> {
                retriedSame = true
                startedOnce = false
                playStream(index, asHls = triedAsHls)
            }
            // Next alternative (other source/quality); after the last one, an episode tries the
            // other copies of its series, then the message shows.
            else -> {
                val item = _ui.value.item ?: return
                item.streams.getOrNull(index)?.let { failedChoices += choiceKeyOf(item, it.streamKey) }
                if (index + 1 < item.streams.size || request !is PlayRequest.Episode) playStream(index + 1)
                else viewModelScope.launch { if (!playOtherCopy(item)) playStream(index + 1) }
            }
        }
    }

    /** The key a version is remembered by (an episode: its series copy; otherwise the stream). */
    private fun choiceKeyOf(item: PlayItem, streamKey: String): String =
        item.versions.firstOrNull { it.streamKey == streamKey }?.choiceKey ?: streamKey

    /**
     * The episode failed in this copy of the series: plays the same episode from the next copy not
     * tried yet, from the same point. False when there is none left.
     */
    private suspend fun playOtherCopy(item: PlayItem): Boolean {
        val episodeId = (request as? PlayRequest.Episode)?.episodeId ?: return false
        val versions = runCatching { playback.episodeVersions(episodeId, fetch = true) }.getOrNull() ?: return false
        val failed = item.streams.firstOrNull()?.streamKey
        versions.firstOrNull { it.streamKey == failed }?.let { failedChoices += it.choiceKey }
        val next = versions.firstOrNull { it.episodeId != null && it.episodeId !in triedEpisodes } ?: return false
        val position = pure.player.currentPosition.takeIf { it > 0 } ?: item.resumeMs
        saveProgressNow()
        load(PlayRequest.Episode(next.episodeId!!, startMs = position, exact = true, fallback = true))
        return true
    }

    /**
     * Something other than the remembered version plays because that one failed: forget the pick,
     * so it isn't tried (and waited for) first every time. Picking it again remembers it again.
     */
    private fun forgetDeadPick(item: PlayItem) {
        if (failedChoices.isEmpty()) return
        val key = versionMemoryKey(item)
        val playing = item.streams.getOrNull(_ui.value.streamIndex)?.streamKey ?: return
        val now = choiceKeyOf(item, playing)
        viewModelScope.launch {
            val pick = user.versionChoice(key) ?: return@launch
            if (pick != now && pick in failedChoices) user.clearVersionChoice(key)
        }
    }

    fun retry() {
        if (_ui.value.item == null) load(request) else playStream(0)
    }

    val currentChannelId: Long? get() = (request as? PlayRequest.Channel)?.channelId

    /** Live: next/previous channel in the list the user came from (wraps around). */
    private var zapJob: Job? = null

    /**
     * Up/Down while watching: the highlight in the flip list moves right away; the channel is only
     * tuned once the user pauses, so flipping fast doesn't open a stream per channel passed
     * (providers often allow a single connection).
     */
    fun zap(delta: Int) {
        val req = request as? PlayRequest.Channel ?: return
        val ids = channelIds ?: return
        if (ids.isEmpty()) return
        val from = _ui.value.zapIndex ?: ids.indexOf(req.channelId).coerceAtLeast(0)
        val target = (from + delta).mod(ids.size)
        _ui.update { it.copy(zapIndex = target) }
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MS)
            if (ids[target] != (request as? PlayRequest.Channel)?.channelId) playChannel(ids[target])
            _ui.update { it.copy(zapIndex = null) }
        }
    }

    fun playChannel(channelId: Long) {
        val req = request as? PlayRequest.Channel ?: return
        saveProgressNow()
        load(req.copy(channelId = channelId))
    }

    fun toggleFavorite() {
        val item = _ui.value.item ?: return
        val fav = !_ui.value.favorite
        viewModelScope.launch { user.setFavorite(item.itemKey, fav) }
    }

    /** Fit -> Fill -> Stretch -> Fit, remembered for the feed that is playing. */
    /** Media3 track groups behind [PlayerUi.audio] / [PlayerUi.subtitles], by [TrackInfo.id]. */
    private var audioRefs: List<Pair<TrackGroup, Int>> = emptyList()
    private var subtitleRefs: List<Pair<TrackGroup, Int>> = emptyList()

    private fun readTracks(tracks: Tracks) {
        fun collect(type: Int): Pair<List<TrackInfo>, List<Pair<TrackGroup, Int>>> {
            val refs = ArrayList<Pair<TrackGroup, Int>>()
            val infos = ArrayList<TrackInfo>()
            for (group in tracks.groups) {
                if (group.type != type) continue
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val f = group.getTrackFormat(i)
                    infos += TrackInfo(
                        id = refs.size,
                        language = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it.isNotBlank() },
                        label = f.label?.takeIf { it.isNotBlank() },
                        codec = f.sampleMimeType,
                        codecs = f.codecs,
                        channels = f.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
                        bitrate = listOf(f.bitrate, f.averageBitrate, f.peakBitrate).firstOrNull { it != Format.NO_VALUE } ?: 0,
                        sampleRate = f.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
                        isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        roles = f.roleFlags,
                        selected = group.isTrackSelected(i),
                    )
                    refs += group.mediaTrackGroup to i
                }
            }
            return infos to refs
        }
        val (audio, aRefs) = collect(C.TRACK_TYPE_AUDIO)
        val (subs, sRefs) = collect(C.TRACK_TYPE_TEXT)
        audioRefs = aRefs
        subtitleRefs = sRefs
        _ui.update { it.copy(audio = audio, subtitles = subs) }
        if (app.pureiptv.ui.BuildConfig.DEBUG) {
            // Formats only (no URLs): what the provider actually sends, for diagnosing quality.
            for (group in tracks.groups) for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                android.util.Log.d(
                    "PureTracks",
                    "type=${group.type} mime=${f.sampleMimeType} codecs=${f.codecs} lang=${f.language} label=${f.label} ch=${f.channelCount} " +
                        "rate=${f.sampleRate} bitrate=${f.bitrate}/${f.averageBitrate}/${f.peakBitrate} size=${f.width}x${f.height} " +
                        "flags=${f.selectionFlags} roles=${f.roleFlags} supported=${group.isTrackSupported(i)} selected=${group.isTrackSelected(i)}",
                )
            }
        }
    }

    /** What this channel or series remembers. */
    private var trackChoice = TrackChoice()

    /** Where the track choice is kept: per channel, per series (all its episodes); films don't keep one. */
    private fun trackMemoryKey(item: PlayItem): String? = if (item.isLive) item.itemKey else item.parentKey

    /** Channels and series remember the choice (by language); a film keeps it for this viewing only. */
    private fun rememberChoice(update: (TrackChoice) -> TrackChoice) {
        val key = _ui.value.item?.let(::trackMemoryKey) ?: return
        trackChoice = update(trackChoice)
        val choice = trackChoice
        appScope.launch { user.setTrackChoice(key, choice) }
    }

    /** The viewer picked an audio track. */
    fun selectAudio(id: Int) {
        val (group, index) = audioRefs.getOrNull(id) ?: return
        val p = pure.player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, index))
            .build()
        val language = _ui.value.audio.getOrNull(id)?.language
        rememberChoice { it.copy(audioLanguage = language) }
    }

    /** Subtitles on with track [id], or off (null, the default). */
    fun selectSubtitles(id: Int?) {
        val p = pure.player
        val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val ref = id?.let { subtitleRefs.getOrNull(it) }
        p.trackSelectionParameters = if (ref == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(ref.first, ref.second)).build()
        }
        // A track without a language can't be remembered by language: then nothing is remembered.
        val language = id?.let { _ui.value.subtitles.getOrNull(it)?.language }
        rememberChoice { it.copy(subtitleLanguage = language) }
    }

    fun cycleZoom() {
        val stream = _ui.value.item?.streams?.getOrNull(_ui.value.streamIndex) ?: return
        val next = Zoom.entries[(_ui.value.zoom.ordinal + 1) % Zoom.entries.size]
        _ui.update { it.copy(zoom = next) }
        viewModelScope.launch { user.setZoom(stream.streamKey, next) }
    }

    fun togglePlayPause() {
        val p = pure.player
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun seekBy(ms: Long) {
        val p = pure.player
        if (_ui.value.item?.isLive == true) return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0, duration))
    }

    fun onBackground() {
        saveProgressNow()
        pure.player.pause()
    }

    fun onForeground() {
        if (_ui.value.item?.isLive == true) {
            pure.player.seekToDefaultPosition()
        }
        if (!_ui.value.failed) pure.player.play()
    }

    private fun onEnded() {
        val item = _ui.value.item ?: return
        if (item.isLive) {
            // A live stream that "ends" has dropped: reconnect.
            playStream(_ui.value.streamIndex)
            return
        }
        // A connection cut off mid-file can look like the end of it. That is not the end of the episode.
        val duration = pure.player.duration
        if (!retriedSame && duration > 0 && pure.player.currentPosition < duration * Progress.FINISHED) {
            onStreamFailed(null)
            return
        }
        saveProgressNow()
        val next = item.nextEpisodeId
        if (next == null) {
            _ui.update { it.copy(finished = true) }
            return
        }
        val label = item.nextEpisodeLabel.orEmpty()
        upNextJob?.cancel()
        if (autoPlays >= MAX_UNATTENDED_EPISODES) {
            // Nobody has touched the remote for a while: don't play through the night.
            _ui.update { it.copy(upNext = UpNext(next, label, null)) }
            return
        }
        upNextJob = viewModelScope.launch {
            for (s in UP_NEXT_SECONDS downTo 1) {
                _ui.update { it.copy(upNext = UpNext(next, label, s)) }
                delay(1_000)
            }
            playNext(auto = true)
        }
    }

    private var upNextJob: Job? = null

    /** Episodes started by the countdown since the last key press. */
    private var autoPlays = 0

    /** Any key press: the viewer is there. */
    fun userActivity() {
        autoPlays = 0
    }

    /** OK on "Up next" / "Still watching?", the "Next episode" button, or the countdown ending. */
    fun playNext(auto: Boolean = false) {
        val next = _ui.value.upNext?.episodeId ?: _ui.value.item?.nextEpisodeId ?: return
        upNextJob?.cancel()
        if (!auto) saveProgressNow()
        autoPlays = if (auto) autoPlays + 1 else 0
        _ui.update { it.copy(upNext = null) }
        load(PlayRequest.Episode(next))
    }

    /** Back on "Up next": stop here and leave the player. */
    fun stopAfterEpisode() {
        upNextJob?.cancel()
        _ui.update { it.copy(upNext = null, finished = true) }
    }

    private suspend fun saveProgressLoop() {
        while (viewModelScope.isActive) {
            delay(10_000)
            if (pure.player.isPlaying) saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val item = _ui.value.item ?: return
        if (item.isLive) return
        val position = pure.player.currentPosition
        val duration = pure.player.duration
        if (duration <= 0 || !startedOnce) return
        appScope.launch { user.saveProgress(item.itemKey, position, duration) }
    }

    override fun onCleared() {
        saveProgressNow()
        session.release()
        pure.release()
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 15_000L
        const val ZAP_SETTLE_MS = 600L
        const val UP_NEXT_SECONDS = 5
        /** Asks "Still watching?" before the next episode after this many in a row without a key press. */
        const val MAX_UNATTENDED_EPISODES = 5
    }
}
