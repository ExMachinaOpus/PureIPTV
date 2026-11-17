# Architecture & decisions

Status: version 1.0. Sections and items marked **planned** are agreed design not yet implemented.

## 1. Goals

- Netflix/Google/Apple-level "it just works": one input, everything else automatic, almost no settings.
- Google TV Streamer first (remote only), phones/tablets fully supported, **one APK**.
- Sources with tens of thousands of channels presented as a curated, searchable library.
- Several sources at once, each enable/disable-able, merged into one library.

Out of scope for v1: recording, parental PIN, catch-up playback (the import already stores
`catchup_days`), multi-view, Google TV home-screen integration (Engage/Watch Next — good v2
candidate), manual "these are not the same" override UI (schema supports it).

**Casting: decided against.** A Chromecast's default receiver is a browser: it can't play raw
MPEG-TS or most MKV, needs CORS headers that IPTV panels don't send, and can't send per-stream
headers. Direct casting would fail for most live TV. The only robust way is relaying through the
phone (remux to HLS on the LAN), which is a lot of work for devices that matter less: on Google TV
devices the app itself runs. The Cast SDK is also closed source (Play Services).

## 2. Sources

| Input | Handling |
|---|---|
| M3U/M3U8 URL or file | Streaming parse (`M3uParser`). `tvg-*`, `group-title`, `catchup*`, `#EXTVLCOPT`, `#KODIPROP`, `url\|Header=..` all honoured. Kind (live/movie/series) from `M3uClassifier`. |
| Xtream Codes (server + user + pass) | `player_api.php`: categories, live, VOD, series, series info, short EPG, catch-up. DTOs are fully lenient (`Lenient*`). |
| XMLTV EPG | From the playlist's `url-tvg`/`x-tvg-url` header, Xtream `xmltv.php`, or a guide link the user gives (optional field when adding a playlist, and "TV guide link" on an M3U source's card; it wins over the others). Streaming parse (`XmltvParser`), gzip unwrapped by caller. |

**Auto-upgrade:** `XtreamDetector.fromPlaylistUrl` recognises `get.php?username=..&password=..` M3U links;
`fromStreamUrl` recognises Xtream-shaped stream URLs inside a plain M3U. Both only produce *candidates*:
the importer must verify with an authenticated `player_api.php` call before switching to Xtream mode,
and silently stays on M3U if that fails.

**Credentials:** Xtream passwords (and M3U and guide URLs, which often embed credentials) are stored encrypted
(Tink AEAD with an Android Keystore key, persisted via DataStore), keyed by source id (`CredentialStore`).
Neither database holds a password. Logs never contain credentials or full stream URLs. Credentials are
excluded from backups: the Keystore key is never backed up, so restored secrets would be unreadable.

## 3. Name normalization & duplicate detection

All in `core/parsing/names`, run once at import, results stored in indexed columns.

`ChannelNameParser.parse(name, category)` → `ParsedName`:
- strips availability markers (`[Not 24/7]`, `[Geo-blocked]`) before anything else;
- strips stream descriptors: country/language prefix (`UK:`, `|DE|`, `[FR]`), quality (SD/HD/FHD/UHD/4K,
  superscripts like `ᴴᴰ`), codec/fps (`HEVC`, `50FPS`), noise (`VIP`, `RAW`, `(Backup)`, `#2`);
- keeps channel identity: `+1` timeshift, regional words, numbers in names, `+` in brands (`Canal+`);
- flags: `Separator` (fake header rows), `Event` (PPV/fixtures), `Loop247`, `Adult`, `Backup`, `Hevc`, `Timeshift`;
- `matchKey`: lowercase, accent-free, letters/digits/`+` of any script;
- `identityKey = country|matchKey` (falls back to language, then `xx`).

**Merge rule** (implemented in `ImportWriter.identityKey`, never fuzzy):
1. country or language known (from the name prefix, the category, or the country suffix of the guide id,
   `BBCOne.uk` / `CCTV1.cn@HD`): merge on `country|matchKey`,
   across sources and qualities;
2. otherwise, with a guide id (tvg-id / `epg_channel_id`): merge on guide id **and** `matchKey` together.
   Guide id alone is not enough: providers reuse one guide id for regional variants and +1 channels;
3. otherwise: merge only within the same source (HD/SD copies), never across sources;
4. reference database match (**planned**: iptv-org channel DB — ~40k channels with alt names, country,
   categories, logos. Downloaded at first run, refreshed monthly; license to be confirmed before shipping).

Movies/series merge only on TMDB id, or normalized title + year when both present.

A merged item has several **streams** (per source, per quality). Playback picks by: source priority →
quality (prefer FHD on the Streamer; UHD only when the device decodes it) → non-backup → non-HEVC on
weak devices; on failure it fails over to the next stream automatically.

## 4. Presentation

Built (first version):
- Home tabs: Live TV, Movies, Series (only when a source has them), Search, centred, and Settings (a
  gear, far right, still the last tab for the remote). On TV, focusing a tab opens it (Google TV
  style); Back from content returns to the tabs, Back on the tabs exits.
- Settings = a permanent sidebar (General, Sources) and the page next to it. General holds the
  interface language: the device's when the app has that translation, else English (UK), unless the
  viewer picked one. It is applied by replacing Compose's context/resources (`ProvideAppLanguage`), so
  a change is instant and keeps focus; `PureDialog` windows re-provide it. A new translation is a
  `res/values-xx` folder plus an entry in `APP_LANGUAGES`. English (UK) in `res/values` is what every
  English gets except the US: `res/values-en-rUS` overrides only the 18 strings that differ
  ("Movies", "Favorites", "program"). `res/values-en-rGB` repeats those 18 in UK wording, because
  Android 7+ otherwise resolves en-GB (and en-AU, en-IN...) to the closest English folder, the US one. Right-to-left languages (Arabic, Persian)
  mirror the app, except the player and guide (`KeepLeftToRight`): there Left means back in time.
- Live TV = three columns: group (Favorites, Recently watched, then country/language with the device's
  country first, then biggest first) → genre → channels. A "Sort" tile above the groups switches the
  countries to A–Z in the interface language and back. The guide's group picker and the Films/Series
  language list have the same tile and share the one choice (`groups_by_name` in settings). The tile
  shows only above more than 8 entries (`MIN_ENTRIES_TO_SORT`): a short list is easy to scan either way.
  Why an option: reviewers found the size order hard to scan for one country in a list of 100+, while
  biggest-first is still the right start for most users.
  Selection follows focus; queries are debounced. Channels are ordered by how many streams/providers
  carry them (a proxy for popularity), then provider order. Each row shows what's on now when the guide
  has it.
- Language of a title or channel: a language stated in a defined field wins (M3U `tvg-language`, read by
  `DeclaredLanguage`); otherwise it is deduced from the title's label, its prefix, then the category name.
  The Xtream lists have no such field. It never affects channel matching. The same rule everywhere: a typed
  field beats pattern matching (the Xtream `year` / `release_date` beat a year in the title).
- Movies/Series = groups (Continue watching, My list, Recently added, provider categories) → poster grid.
- Search = substring over cleaned names (`match_key` / `search_key`), prefix matches first. FTS5 later.

Still planned:

- Region first: device country/language first, "More countries" collapsed; learns from viewing.
- Browse = country → genre (`GenreClassifier`: News, Sports, Kids, Movies, Entertainment, Documentary, Music, Religion).
- Live tab leads with *what's on* (EPG-driven rows: On now in Sports/News/Movies), then Your channels
  (favorites + frequently watched, numbered for predictable zapping), Events (date-sorted).
- Separators never shown; adult hidden until opted in; 24/7 loops grouped.
- VOD/series: one entry per title, language/quality versions behind it, user's language first.
  Rows: Continue watching, Recently added, by genre.
- Search: one box over channels (+ alt names), movies, series and **EPG programme titles**.
  Typo tolerant (FTS5 trigram). Ranking: exact/prefix → user's regions → watched before → live now → has EPG.

Voice input: the search field takes dictation through the system keyboard's microphone (Gboard on
Google TV); there is no separate voice search in the app.

## 5. Data model (Room + bundled SQLite, two databases)

Storage is split by **who owns the data**. Both databases live in normal app data storage
(`databases/`), never in the cache folder.

| | `user.db` (`UserDatabase`) | `content.db` (`ContentDatabase`, owned by `ContentStore`) |
|---|---|---|
| Holds | sources, favorites, watch progress, history, settings | everything imported: categories, channels, live streams, movies, series, episodes; later EPG and the search index |
| Owner | the user | the providers (rebuildable at any time) |
| Schema changes | from the first release: version bump + tested migration, **never destructive** | destructive migration allowed (`fallbackToDestructiveMigration`) |
| Missing / corrupt | must not happen; covered by backup | `ContentStore` deletes it, recreates it empty, and every source is re-imported; user.db is untouched |
| Backup | included | excluded |

Credentials are in neither database: they stay in the encrypted DataStore (section 2).
Images are cached by Coil's disk cache in the cache folder, not in either database.

```
-- user.db
source(id, name, type, host, enabled, priority, live_format, last_refresh_ms, last_error, generation)
favorite(item_key PK, added_ms, position)
watch_progress(item_key PK, position_ms, duration_ms, updated_ms)
history(id, item_key, watched_ms)
setting(key PK, value)

-- content.db
category(id, source_id, kind, provider_id, name, country, language, genre, hidden, sort_order, generation)
channel(id, identity_key UNIQUE, display_name, match_key, country, language, genre, logo, tvg_id, flags)  -- merged
live_stream(id, source_id, channel_id -> channel, category_id, provider_key, raw_name, url, quality, flags,
            user_agent, referrer, catchup_days, sort_order, generation)
movie(id, source_id, category_id, provider_key, title, year, tmdb_id, poster, rating, added_sec, url, …)
series(id, source_id, category_id, provider_key, title, year, tmdb_id, cover, plot, …)
episode(id, series_id -> series, provider_key, season, episode, title, url, …)
programme(id, source_id, channel_key, start_ms, end_ms, title, subtitle, description, episode, search_key, generation)
-- planned: search_fts (FTS5 trigram); cross-source movie/series merge
```

Rules:
- **No foreign keys across databases.** Content rows reference a source by `source_id` only.
  "Delete source → delete its content" is done in code (`SourceManager.delete` →
  `ImportDao.deleteSourceContent`); at startup, content of sources that no longer exist is removed.
  Foreign keys are used only *inside* content.db (stream → channel, episode → series).
- **User state uses stable item keys, never content.db row ids** (those change on every refresh,
  and the whole file can be rebuilt):
  `channel:<identity key>`, `movie:<tmdb id>` or `movie:<source id>:<provider key>`,
  `series:<tmdb id>` or `series:<source id>:<provider key>`, `episode:<series key>:<season>:<episode>`.
- **Enabled filtering**: queries take the enabled source ids from user.db as a parameter (no joins
  across databases); disabling a source never deletes content.
- `provider_key` is stable per source: Xtream stream/series id, else the stream URL (M3U).
- **Import** = streaming parse → normalize → batched inserts (one transaction per ~500-row chunk) into a
  new *generation* → when the whole import succeeded, delete rows of older generations and orphan
  channels. A failed import keeps the previous data. One source's failure never touches other sources.
- Xtream episodes are fetched per series on demand (`get_series_info`), not during import.

## 6. Playback

- Media3 ExoPlayer, configured only in `PlayerFactory` (OkHttp data source, tuned buffers, decoder fallback).
- Per-stream headers (`User-Agent`, `Referer`) from the playlist are applied per `MediaItem`.
- Xtream live plays MPEG-TS (`.ts`) first and HLS (`.m3u8`, when `allowed_output_formats` has it) as
  fallback: real panels often advertise HLS but return 403 on its segments (seen with a real provider).
- The player screen owns its ExoPlayer + MediaSession (ViewModel scoped). There is no playback
  service: an exported `MediaSessionService` lets any app on the device connect and hand it addresses
  to play. If background audio is added later, the service must check the caller in `onConnect`.
- The player opens http(s) addresses only (`PlayerFactory`), and images are loaded from http(s) only
  (`ImageLoading.kt`): playlists must not be able to point either at `file://` or `content://`.
- **Failover**: a merged channel has streams from several sources/qualities; `PlaybackRepository`
  orders them (source priority → non-HEVC if no HW decoder → primary before backup → quality) and the
  player moves to the next on error or after a 15 s stall. A stream that played and dropped is retried once.
  Unknown containers are retried as HLS (many providers serve HLS on `.ts`/`.mp4`/extension-less URLs).
- Guide (EPG): XMLTV from Xtream `xmltv.php` or the playlist's `x-tvg-url`, streaming, gzip detected by
  magic bytes. A refresh imports the guide **first** (the stored one may have run out while the device
  was off; the channels to match against are already there), then the content. A first import needs the
  channels, so there the guide comes after. Only a window of now−2 h … now+3 days is stored
  (`programme` table, one row per source, channel and start time, overwritten in place). Programmes for
  the source's own streams are all kept; those for guide ids no stream has (yet: the content refresh
  that follows may add them) up to `MAX_UNMATCHED_PROGRAMMES`. Guide ids are per stream
  (`live_stream.epg_key`), because each provider uses its own ids; streams without a guide id are matched
  to XMLTV channels by exact cleaned name within the same source only. A refresh keeps a stream's
  earlier name match while it rewrites the streams. Old programmes are deleted only after the new guide
  is in, so the guide never goes blank.
- Refresh in place: every imported row (category, stream, film, series) is updated under its existing row
  id when the provider sends it again (`ImportDao.upsert*`), so what points at it (streams at categories,
  episodes at series) stays valid and nothing disappears while a refresh runs. Rows the provider no longer
  sends are deleted at the end (`generation`). Episodes fetched on demand survive a refresh.
  A merged channel shows **one** guide (the best stream's that covers now): mixing providers' guides gives
  a "now" and "next" that don't line up.
- Guide tab: a time grid (2 h visible, half-hour steps) of one channel group (favourites by default, all
  channels, recently watched, or a country). Each
  row is a single focusable item: Up/Down move between channels natively and keep the selected time;
  Left/Right move between programmes; Back returns to now. While the remote is in the grid, the selected
  programme's info takes the place of the search row, so the grid keeps its height and never moves. Search covers the picked group's guide (title +
  sub-title, `programme.search_key`, substring) and is shown in the same grid: one row per channel with a
  match, matches highlighted, focusing a row jumps to its first match. Search is a plain scan over the
  stored window; move to FTS5 trigram if it exceeds the 100 ms budget on big guides.
- Picture: Fit (letter/pillarbox, never crop) by default. Zoom (Fit / Fill screen / Stretch) is
  remembered per feed (`stream:<source>:<provider id>` in user.db settings), not per merged channel:
  copies of one channel are encoded differently (e.g. burned-in bars on the SD backup only).
- Audio and subtitles: the player's own default picks the audio (device language, then quality);
  subtitles start **off** (some providers flag full tracks as default/forced). The info panel shows the
  playing audio; Audio/Subtitles buttons list every playable track with all the stream states (language,
  name, codec and AAC profile, channels, sample rate, bitrate, default/forced/commentary/SDH). A choice
  lasts for the title; channels and series remember it as languages (per channel, per series for all
  its episodes). Subtitles are drawn by Media3's `SubtitleView` (system caption style, larger on TV).
- Progress saved every 10 s and on exit (VOD); ≥95 % counts as finished. History feeds "Recently
  watched" and "Continue watching". A series continues with its last watched episode unless that one
  was finished, then with the next (`continueAt`, used by Home and the series page). One item key can
  have several series rows (qualities, one row per season); Home picks the row holding that episode.
- **FFmpeg audio decoders** (NextLib `nextlib-media3ext`, GPL-3.0, FFmpeg itself LGPL-3.0+; the app
  is GPL-3.0 too). Its `FfmpegAudioRenderer` is added **after** the platform audio renderer: a device
  that decodes or passes through Dolby/DTS itself (TV boxes) keeps doing so; one that can't (many
  phones) plays the track through FFmpeg instead of silently. ExoPlayer gives a whole track group to
  one renderer, so in an HLS stream whose AAC and AC3 renditions share a group the platform renderer
  wins and AC3 shows as unplayable; files (MKV/MP4/TS) have one group per track and are unaffected.
  The NextLib version must match Media3's (`nextlib` in `libs.versions.toml`).
- **Frame rate matching** (films and episodes): 24 fps film on a 60 Hz output judders (frames alternate
  between 2 and 3 refreshes). `FrameRateDetector` takes the rate the container declares (MP4) or measures
  it from the frame timestamps (MKV and TS rarely declare one; about 2.5 s of video), and
  `MatchFrameRate` asks for a display mode at the same resolution whose refresh rate is a whole multiple
  of it (24 Hz for 23.976/24 fps, 50 Hz for 25 fps). Rates that already fit (30 fps on 60 Hz) don't
  switch. A switch blanks the screen for a second or two on most TVs, so it is not our setting: the
  system's "Match content frame rate" (Android 12+) decides, as for every streaming app, and we switch
  only when it is "Always" (Media3 itself handles "Seamless only"). Decided per episode; the display
  keeps its mode until the next episode's rate is known, so a series at one rate doesn't blank between
  episodes. Leaving the player returns to the display's own mode. Live TV is left alone: zapping must
  never blank the screen, and European channels are 50 Hz, close enough on 60 Hz (revisit if needed).
- **Picture-in-picture** (phones and tablets only): leaving the app while something plays continues
  in a small window with the video only (`rememberPictureInPicture`); a paused player, or any other
  screen, goes to the background as usual. Closing the window stops playback (the player pauses on
  `ON_STOP`).

## 7. UI architecture

- `FormFactor` (Tv/Touch) detected once per device (UiModeManager / leanback / touchscreen), not by
  window size, provided via `LocalFormFactor`.
- Each screen: one ViewModel (shared), `XxxTv` and `XxxTouch` layouts where layout differs; shared
  small components (poster, channel row, EPG cell).
- Navigation 3: back stack is plain state (`PureApp`). Back pops; at root the system exits.
- Onboarding: "Set up from your phone" = TV shows QR → phone opens a page served by a tiny local
  HTTP server on the TV (`PhoneSetupServer`, same Wi-Fi, LAN address only, one-time path) → user
  pastes link (and optional guide link) or login → TV continues. Typing on the TV is the fallback.

## 8. Background work

- `RefreshWorker` (WorkManager, every 24 h, any network, battery not low) re-imports enabled sources
  not refreshed in the last 20 h; a refresh imports the guide first, then the content.
- At app start, sources older than 12 h are refreshed too. A source whose import started less than
  15 minutes ago is left alone, so a source that breaks the import isn't retried at every start.
- Imports keep running after the user leaves the app (`ImportKeepAliveWorker`, a `dataSync`
  foreground service with a notification). "Refresh" per source in Settings.
- **Planned:** reference DB refresh (monthly), with the reference database (section 3).

## 9. Distribution

Google Play; sideloaded APK during development (wireless ADB, see `dev/dev-setup.md`). No bundled
content, neutral wording. The store listing (text per language, graphics, screenshots) is in
`fastlane/metadata/android/` (fastlane's layout, which F-Droid reads too); `scripts/store-graphics.py`
draws the icon, feature graphic and TV banner from the app mark. Every Play build is tagged
`v<versionName>` on the commit it was built from. Public pages (privacy policy, support,
legal) are in `docs/`, published by GitHub Pages.
