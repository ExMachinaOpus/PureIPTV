package app.pureiptv.core.model

/**
 * Structured interpretation of a raw provider entry name such as `|UK| BBC One FHD (Backup)`.
 * Produced once at import time and stored, so browsing/search/matching never re-parse.
 */
data class ParsedName(
    /** Cleaned name for display: prefix, quality and noise tags removed, original casing kept. */
    val displayName: String,
    /**
     * Matching key for the core name: lowercase ASCII, no spaces/punctuation, distinct-channel
     * markers such as `+1` kept. Example: "bbcone", "bbcone+1", "skysportsf1".
     */
    val matchKey: String,
    /** ISO 3166-1 alpha-2 country (e.g. "GB") when detected from name or category. */
    val country: String?,
    /** ISO 639-1 language (e.g. "ar") when the prefix denotes a language rather than a country. */
    val language: String?,
    val quality: Quality,
    val flags: Set<NameFlag>,
) {
    /**
     * Identity used to merge duplicates when no tvg-id is available. Country is part of it:
     * "BBC One" under UK and under an unknown country are NOT considered the same.
     */
    val identityKey: String get() = "${country ?: language ?: "xx"}|$matchKey"
}

enum class NameFlag {
    /** Fake "channel" used as a visual header (`##### SPORTS #####`). Never shown as a channel. */
    Separator,
    /** PPV / one-off event channel. Shown in the Events section. */
    Event,
    /** 24/7 loop channel (a show or film on repeat). */
    Loop247,
    /** Adult content. Hidden unless the user opts in. */
    Adult,
    /** Marked as a backup/alternative feed by the provider. Ranked below the primary. */
    Backup,
    /** HEVC/H.265 encoded. Ranked lower on devices without reliable HEVC decode. */
    Hevc,
    /** Timeshifted channel (`+1`, `+2`). Distinct channel, not a duplicate. */
    Timeshift,
}
