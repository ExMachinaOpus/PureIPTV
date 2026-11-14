# PureIPTV

An IPTV player for Google TV / Android TV and Android phones that just works.

Give it one thing (a playlist link, or a server with username and password) and it figures out the
rest: what kind of source it is, which entries are live channels, movies or series, which channels
are duplicates, and how to present tens of thousands of entries as a library you can actually browse.

PureIPTV is a player only. It does not include, sell or recommend any content or sources.

## Features

- **One-step setup.** Paste a playlist link or sign in with server and login, on the TV or by
  scanning a QR code and typing it on your phone. The source type is detected automatically, and
  playlist links from Xtream servers are upgraded to the richer Xtream API.
- **Multiple sources, one library.** Add several sources, switch them on and off, and choose which
  one is preferred. The same channel from two sources shows once, with automatic fallback to the
  other stream when one fails.
- **Live TV and guide.** Channels grouped by category, a programme guide with search, and channel
  switching with the remote's Up/Down keys.
- **Movies and series.** Posters, plots, seasons and episodes, resume where you stopped, and
  "Next episode" when one ends.
- **Home.** Continue watching, favourite channels and your list of movies and series, merged
  across all sources.
- **Search** across channels, programmes, movies and series.
- **Player.** Audio and subtitle track choice, picture size per stream, subtitles at one readable
  size, Dolby and DTS audio on devices without their own decoder, picture-in-picture on phones, and
  plain-language messages when a stream doesn't work.
- **Remote first.** Everything works with the D-pad, OK and Back; touch is fully supported on
  phones and tablets.
- **Big playlists.** Built for sources with 50,000+ channels and 100,000+ movies: imports stream to
  disk, and lists load page by page.
- **Private.** No account, no analytics, no ads. Sources and logins stay on the device (logins
  encrypted) and are only sent to the providers you add. See the [privacy policy](docs/privacy-policy.md).
- **35 languages**, with adult content hidden or shown by one setting.

## Supported sources

| Source | Notes |
|---|---|
| M3U / M3U8 playlists | `tvg-*` attributes, groups, per-stream headers (user agent, referrer) |
| Xtream Codes API | Server + username + password: live TV, movies, series and guide |
| XMLTV guide | From the playlist header, the Xtream server, or a guide link you add; channels without a guide id are matched by exact name |

## Requirements

Android 8.0 or newer. On TV: Google TV or Android TV with a D-pad remote.

## Building

Requirements: the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`). A JDK is not
required: Gradle downloads the pinned JDK 21 by itself.

```
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew test                 # unit tests
```

Emulators, installing on a Google TV device over Wi-Fi, and the dev helper script are described in
[dev/dev-setup.md](dev/dev-setup.md).

## Project layout

```
app/           Application entry point and manifest (TV + phone)
ui/            Screens and components (separate TV and touch layouts, shared ViewModels)
core/model     Domain types
core/parsing   M3U, Xtream, XMLTV parsing; channel-name normalisation and duplicate keys
core/data      Databases, import, background refresh, credentials
core/player    Media3 player setup and stream failover
```

Design decisions and their reasons: [dev/architecture.md](dev/architecture.md).
Contribution rules (for people and coding agents): [AGENTS.md](AGENTS.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## Tech

Kotlin, Jetpack Compose (with Compose for TV), Navigation 3, Media3 ExoPlayer with FFmpeg audio
decoders (NextLib), Room with bundled SQLite, Hilt, OkHttp, kotlinx.serialization, Coil,
WorkManager, DataStore with Tink.

## Licence

PureIPTV is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).
