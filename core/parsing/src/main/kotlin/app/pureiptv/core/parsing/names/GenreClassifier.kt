package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.Genre

/**
 * Maps a live channel to a coarse [Genre] using its category first, then its name.
 * Multilingual keyword lists. Order matters: Kids before Movies ("Disney Cinema" is kids),
 * News before Entertainment.
 */
object GenreClassifier {

    private val rules: List<Pair<Genre, Regex>> = listOf(
        Genre.Kids to words(
            "KIDS?", "CHILDREN", "ENFANTS?", "JEUNESSE", "KINDER", "INFANTIL", "BAMBINI", "BARN", "CARTOONS?",
            "DISNEY", "NICK\\w*", "BOOMERANG", "BABY ?TV", "JUNIOR", "TOONS?", "ANIMATION", "ANIME", "CBEEBIES", "CBBC",
        ),
        Genre.News to words(
            "NEWS", "NACHRICHTEN", "NOTICIAS", "INFOS?", "NOTIZIE", "NYHETER", "NIEUWS", "AKTUELL", "CNN", "BLOOMBERG",
            "CNBC", "EURONEWS", "AL ?JAZEERA", "FRANCE ?24", "DW", "MSNBC", "FOX NEWS", "SKY NEWS", "BFM\\w*",
        ),
        Genre.Sports to words(
            "SPORTS?", "SPORTIF", "DEPORTES?", "DEPORTIVO", "CALCIO", "FOOTBALL", "SOCCER", "FUTBOL", "FUSSBALL",
            "NBA", "NFL", "NHL", "MLB", "UFC", "WWE", "F1", "FORMULA ?1", "MOTOGP", "GOLF", "TENNIS", "CRICKET",
            "RUGBY", "ESPN\\w*", "BEIN\\w*", "DAZN", "EUROSPORT\\w*", "ARENA ?SPORT", "SUPERSPORT", "TSN", "SPORTSNET",
            "PREMIER LEAGUE", "LALIGA", "SERIE A", "BUNDESLIGA", "LIGUE ?1", "RACING",
        ),
        Genre.Documentary to words(
            "DOCUMENTAR(Y|IES|IO|IOS)", "DOCS?", "DOKU\\w*", "DISCOVERY", "NAT ?GEO\\w*", "NATIONAL GEOGRAPHIC",
            "HISTORY", "HISTOIRE", "ANIMAL PLANET", "SCIENCE", "WILD", "NATURE",
        ),
        Genre.Music to words(
            "MUSIC", "MUSIK", "MUSICA", "MUSIQUE", "MTV\\w*", "VH1", "RADIO", "HITS", "CONCERTS?", "KISS",
        ),
        Genre.Religion to words(
            "RELIGIO\\w*", "CHRISTIAN", "GOSPEL", "CATHOLIC", "ISLAM\\w*", "QURAN", "CHURCH", "GOD", "EWTN", "BIBLE",
        ),
        Genre.Movies to words(
            "MOVIES?", "CINEMA", "CINE\\w*", "FILMS?", "FILME", "KINO", "PELICULAS", "HBO", "SHOWTIME", "STARZ",
            "TCM", "AMC", "PARAMOUNT", "SKY CINEMA", "CANAL\\+ ?CINEMA",
        ),
        Genre.Entertainment to words(
            "ENTERTAINMENT", "GENERAL", "GENERALIST\\w*", "VARIETY", "UNTERHALTUNG", "ENTRETENIMIENTO",
            "INTRATTENIMENTO", "SERIES", "REALITY", "LIFESTYLE", "COMEDY", "DRAMA",
        ),
    )

    fun classify(category: String?, name: String): Genre {
        if (category != null) categoryGenre(category)?.let { return it }
        val n = fold(name)
        return rules.firstOrNull { it.second.containsMatchIn(n) }?.first ?: Genre.Other
    }

    /** Genre stated by a category, cached: big playlists repeat a few hundred categories thousands of times. */
    private fun categoryGenre(category: String): Genre? {
        categoryCache[category]?.let { return it.orNull() }
        val cat = fold(category)
        val genre = rules.firstOrNull { it.second.containsMatchIn(cat) }?.first
        if (categoryCache.size > 20_000) categoryCache.clear()
        categoryCache[category] = java.util.Optional.ofNullable(genre)
        return genre
    }

    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Genre>>()

    private fun <T : Any> java.util.Optional<T>.orNull(): T? = if (isPresent) get() else null

    private fun fold(s: String) = ChannelNameParser.stripAccents(ChannelNameParser.normalizeUnicode(s)).uppercase()

    private fun words(vararg patterns: String): Regex =
        Regex("""(?<![\p{L}\p{N}])(${patterns.joinToString("|")})(?![\p{L}\p{N}])""")
}
