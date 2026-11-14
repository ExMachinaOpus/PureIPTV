# PureIPTV: instructions for coding agents

These rules apply to every AI coding agent working in this repo (Claude Code reads them via
`CLAUDE.md`, which imports this file). Human contributors should follow them too.

## Git

- Branch is `master`. Never add AI attribution to commits or PRs (no `Co-Authored-By`, no "generated with").
- Never commit personal or machine-specific details (names, emails, paths, IPs, OS specifics, credentials).
- `docs/` is the public website (GitHub Pages: privacy policy, support, legal). Developer documentation goes in `dev/`.

## Project

IPTV player for Android with the Netflix/Google/Apple standard: **it just works**. The user gives one
thing (a playlist URL, or server + username + password) and the app figures out the rest. Primary
device: **Google TV Streamer** (Android 14, remote only). Phones/tablets are fully supported too.

Design and rationale live in `dev/architecture.md`. Read it before changing data model, import,
matching, or navigation. Update it when a decision changes.

## Product rules (non-negotiable)

1. **Zero-config.** No setting may be added if the app can decide correctly itself. Every new
   option needs a written reason in the PR/commit. Prefer smart defaults over toggles.
2. **Remote-first.** The remote is the primary input; touch is the adaptation.
   - Every action reachable with D-pad + OK + Back. No swipe-only, long-press-only or hover-only actions.
   - Every TV screen sets initial focus on its primary action and **restores focus** to the item the
     user came from when returning.
   - Focus is always visible (scale + border), readable from 3 m. No focus traps, no focus jumps.
     Selected (the tab, season, category or page showing) is an underline, never a shade, so it can't
     be mistaken for focus (`Tile(selected = true)`).
   - A screen that loads its content appears once, complete, and gives initial focus only after the
     navigation transition has settled (focus given during it is lost when it ends: a blink). Its
     buttons don't change label after appearing. See `DetailsPage`.
   - Back: close overlay → previous screen → exit. Never swallowed, never confirm dialogs on exit.
   - Never disable a control that can have focus (it drops TV focus to nowhere). Keep it enabled and
     ignore the action while busy. Request initial focus only after the target is composed (e.g. after
     the list has loaded), never in a `LaunchedEffect(Unit)` that can run before it exists.
   - Lists whose content is replaced by a selection elsewhere (categories, channels, posters, search
     results) are keyed by **position**, not by id: an id key removes the focused item when the list
     swaps, Android drops focus, and the next key lands on a tab. Enter such lists with
     `rememberListEntry`/`rememberGridEntry` (last item if visible, else first visible), not
     `focusRestorer`. Never remove or swap a focused view (a field that replaces a button, etc.).
   - No navigation or click sounds. Dialogs use `PureDialog` (each window needs sound effects off).
   - Avoid text entry on TV. Offer "Set up from your phone" (QR); the system keyboard's microphone covers voice input.
   - Player keys (Streamer remote has no channel/number keys): Live: Up/Down = channel, OK = info/guide,
     Left/Right = channel list. VOD: Left/Right = seek, OK = play/pause, Up/Down = controls.
     While the "Next episode" button shows, OK presses it and Back puts it away.
3. **Multiple sources, one library.** Sources can be enabled/disabled (data kept, instant) and ordered.
   Home, search, favorites, continue watching and EPG are merged across enabled sources. Category
   browsing stays per source. User state (favorites, progress) survives refresh, disable and delete.
4. **Duplicates are merged only on evidence** (tvg-id, country+normalized name, reference DB).
   Never fuzzy-merge. A wrong merge (plays the wrong channel) is worse than a visible duplicate.
5. **Scale.** Sources can have 50k+ channels and 100k+ VOD entries. Never load a full list into
   memory. Parse streaming, write in batched transactions, read via Paging, precompute at import.
   Budgets: 50k-entry import ≤ 15 s on the Streamer, search results ≤ 100 ms.
6. **Plain-language errors.** "This channel is offline right now", never an exception message.
   Failures in one source never block others.

## Stack (decided, don't swap without updating dev/architecture.md)

Kotlin 2.4 · AGP 9 (built-in Kotlin: never apply `org.jetbrains.kotlin.android`) · Jetpack Compose
(`androidx.tv:tv-material` for TV, `material3` for touch) · Navigation 3 · Media3 ExoPlayer + session ·
Room with **bundled SQLite** (FTS5) + Paging 3 · Hilt (KSP) · OkHttp (streaming downloads) + kotlinx.serialization ·
Coil 3 · WorkManager · DataStore + Tink. Versions only in `gradle/libs.versions.toml`.
minSdk 26, compileSdk 37, targetSdk 36. Gradle downloads its own JDK 21 (`gradle/gradle-daemon-jvm.properties`).

## Modules

```
app/           Application, MainActivity, manifest (TV + phone flags), DI root. Keep thin.
ui/            All screens + components. Each screen: shared ViewModel, separate Tv/Touch layouts.
core/model     Pure Kotlin domain types. No Android.
core/parsing   Pure Kotlin/JVM: M3U, Xtream DTOs/URLs, XMLTV, channel-name normalization, genres.
core/data      Room DB, repositories, source sync/import, WorkManager refresh, credentials.
core/player    Media3 ExoPlayer config (PlayerFactory), failover.
```
Dependencies point downward only: app → ui → data/player → parsing → model.
No per-feature modules. Every module using Hilt applies the Hilt plugin.

## Code conventions

- `core/parsing` runs on Android API 26 but compiles against the JVM: **don't use JDK APIs newer than
  Android 26** (e.g. `URLDecoder.decode(String, Charset)` is API 33 — use the `"UTF-8"` overload).
  java.time and java.util.Base64 are fine.
- Parsers must never throw on bad input: skip the broken item, keep going. Add the real-world sample
  that broke things as a test fixture.
- Xtream: all DTO fields optional with `Lenient*` serializers. All Xtream URLs come from `XtreamUrls`.
- Channel naming/matching logic lives only in `ChannelNameParser`; changes need tests for both
  "must merge" and "must not merge" cases (`ChannelNameParserTest`).
- DTOs never reach the UI; map to domain types in `core/data`.
- Storage is two Room databases (dev/architecture.md, section 5):
  - `user.db` (`UserDatabase`): sources, favorites, progress, history, settings. The user's data:
    from the first release every schema change needs a real migration; never destructive.
  - `content.db` (`ContentDatabase` via `ContentStore`): everything imported. Disposable: destructive
    migration allowed; when missing/corrupt it is recreated and all sources are re-imported.
  - No foreign keys across the two: content references sources by `source_id`; deleting a source deletes
    its content in code (`SourceManager.delete`). Get content DAOs from `ContentStore` (it can swap the DB).
  - User state uses stable item keys (`channel:<identity key>`, `movie:<tmdb id>`, ...), never content row ids.
  - Credentials only in the encrypted DataStore (`CredentialStore`), never in a database.
  - Backups: include only user.db (everything not included is left out: content.db, credentials, Tink keyset;
    don't add <exclude> lines for them, lint fails release builds on excludes outside includes)
    (`app/src/main/res/xml/data_extraction_rules.xml` + `backup_rules.xml`, keep both in sync).
  - Images: Coil's disk cache in the cache folder only.
- DB: all DAO functions `suspend` or `Flow` (bundled driver).
- Never log credentials or full stream URLs (they contain username/password). Use `redact()` helpers.
- Never commit real playlists or credentials. Private test files go in `fixtures-private/` (gitignored).
- User-visible strings in `strings.xml`, in **British English** ("favourites", "colour"). Code
  identifiers stay as they are.

## Commands

```
./gradlew :core:parsing:test          # fast JVM tests (parsers, matching) — run after every parsing change
./gradlew test                        # all unit tests
./gradlew assembleDebug               # build APK (app/build/outputs/apk/debug/)
scripts/dev.sh emu tv|phone           # start emulator (TV = Google TV API 34, like the Streamer)
scripts/dev.sh stop                   # stop emulators + Gradle daemon (free RAM)
scripts/dev.sh run [serial]           # install + launch debug build
scripts/dev.sh keys [serial] down down ok back   # drive it with remote keys
scripts/dev.sh shot [serial]          # screenshot to build/screens/
scripts/dev.sh connect <ip:port>      # real Streamer via wireless debugging (see dev/dev-setup.md)
```

**Memory:** the dev setup is kept lean so a build plus one emulator fits on a 16 GB machine. Never run
two emulators at once, never raise AVD RAM above 2 GB or the Gradle heap above 2 GB. Test TV first, `scripts/dev.sh emu phone` (stops the TV one), then phone.
Run `scripts/dev.sh stop` when done. Details: `dev/dev-setup.md`, "Memory budget".

## Definition of done for UI work

A feature is done when it works on the **Google TV emulator using only `scripts/dev.sh keys`**
(D-pad, OK, Back, media keys), verified with screenshots, and also smoke-tested on the phone emulator.
When the real Streamer is connected, test there as well.
