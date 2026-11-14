package app.pureiptv.core.parsing.names

import app.pureiptv.core.model.NameFlag
import app.pureiptv.core.model.Quality
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelNameParserTest {

    private fun p(name: String, category: String? = null) = ChannelNameParser.parse(name, category)

    // ---- duplicates that MUST merge ----------------------------------------------------------

    @Test
    fun `same channel in many provider spellings shares one identity`() {
        val spellings = listOf(
            "UK: BBC One",
            "|UK| BBC ONE HD",
            "[UK] BBC One FHD",
            "UK | BBC One ᴴᴰ",
            "UK - BBC One (Backup)",
            "VIP UK: BBC One 4K HEVC",
            "UK: BBC One 50FPS",
            "UK: BBC One #2",
            "UK: BBC One [FHD/HEVC]",
            "UK : BBC One Full HD",
            "BBC One",  // country from category below
        )
        val keys = spellings.map { p(it, category = "UK | Entertainment").identityKey }.toSet()
        assertThat(keys).containsExactly("GB|bbcone")
    }

    @Test
    fun `display name keeps original casing without tags`() {
        assertThat(p("|UK| Sky Sports Main Event ᴴᴰ").displayName).isEqualTo("Sky Sports Main Event")
        assertThat(p("DE: Das Erste FHD").displayName).isEqualTo("Das Erste")
        assertThat(p("FR: TF1 (Backup)").displayName).isEqualTo("TF1")
    }

    @Test
    fun `accents and case do not matter for matching`() {
        assertThat(p("ES: Telecinco").matchKey).isEqualTo(p("ES: TELECINCO HD").matchKey)
        assertThat(p("FR: France Ô").matchKey).isEqualTo(p("FR: FRANCE O").matchKey)
        assertThat(p("DE: ZDFneo").matchKey).isEqualTo(p("DE: ZDF Neo").matchKey)
    }

    // ---- different channels that must NOT merge ----------------------------------------------

    @Test
    fun `timeshift channels are distinct`() {
        val plain = p("UK: Channel 4 HD")
        val plus1 = p("UK: Channel 4 +1 HD")
        val plus1b = p("UK: Channel 4 + 1")
        assertThat(plus1.identityKey).isNotEqualTo(plain.identityKey)
        assertThat(plus1.identityKey).isEqualTo(plus1b.identityKey)
        assertThat(plus1.flags).contains(NameFlag.Timeshift)
    }

    @Test
    fun `regional variants are distinct`() {
        assertThat(p("UK: BBC One Scotland").identityKey).isNotEqualTo(p("UK: BBC One").identityKey)
        assertThat(p("UK: ITV1 London").identityKey).isNotEqualTo(p("UK: ITV1 Granada").identityKey)
    }

    @Test
    fun `numbers that are part of the name are kept`() {
        assertThat(p("UK: Channel 5").matchKey).isEqualTo("channel5")
        assertThat(p("DE: Sport1").matchKey).isEqualTo("sport1")
        assertThat(p("UK: Sky Sports F1 UHD").matchKey).isEqualTo("skysportsf1")
        assertThat(p("NL: NPO 1").identityKey).isNotEqualTo(p("NL: NPO 2").identityKey)
    }

    @Test
    fun `plus in brand names is kept`() {
        assertThat(p("FR: Canal+").matchKey).isEqualTo("canal+")
        assertThat(p("FR: Canal+ Sport").matchKey).isEqualTo("canal+sport")
        assertThat(p("FR: Canal").matchKey).isEqualTo("canal")
    }

    @Test
    fun `same name in different countries is distinct`() {
        assertThat(p("UK: Discovery").identityKey).isNotEqualTo(p("DE: Discovery").identityKey)
        assertThat(p("Discovery").identityKey).isNotEqualTo(p("UK: Discovery").identityKey)
    }

    @Test
    fun `channel names that look like prefixes are not stripped`() {
        assertThat(p("BBC: News at Ten").matchKey).isEqualTo("bbcnewsatten")
        assertThat(p("E4").matchKey).isEqualTo("e4")
        assertThat(p("CNN International").country).isNull()
        assertThat(p("Sky Sports: F1").matchKey).isEqualTo("skysportsf1")
    }

    // ---- quality / flags ---------------------------------------------------------------------

    @Test
    fun `quality is detected from all tag styles`() {
        assertThat(p("UK: BBC One").quality).isEqualTo(Quality.Unknown)
        assertThat(p("UK: BBC One SD").quality).isEqualTo(Quality.SD)
        assertThat(p("UK: BBC One ᴴᴰ").quality).isEqualTo(Quality.HD)
        assertThat(p("UK: BBC One ᶠᴴᴰ").quality).isEqualTo(Quality.FHD)
        assertThat(p("4K | UK: BBC One").quality).isEqualTo(Quality.UHD)
        assertThat(p("UK: BBC One 1080p").quality).isEqualTo(Quality.FHD)
        assertThat(p("UK: BBC One HEVC").flags).contains(NameFlag.Hevc)
        assertThat(p("UK: BBC One BACKUP 2").flags).contains(NameFlag.Backup)
        assertThat(p("UK: BBC One ALT").flags).contains(NameFlag.Backup)
    }

    @Test
    fun `separators are recognized`() {
        assertThat(p("##### SPORTS #####").flags).contains(NameFlag.Separator)
        assertThat(p("------ UK ------").flags).contains(NameFlag.Separator)
        assertThat(p("★★★ VIP ★★★").flags).contains(NameFlag.Separator)
        assertThat(p("===").flags).contains(NameFlag.Separator)
        assertThat(p("UK: BBC One").flags).doesNotContain(NameFlag.Separator)
    }

    @Test
    fun `events are recognized from category or name`() {
        assertThat(p("Arsenal vs Chelsea 20:00").flags).contains(NameFlag.Event)
        assertThat(p("Lakers @ Celtics 01:30").flags).contains(NameFlag.Event)
        assertThat(p("PPV 1: UFC 300").flags).contains(NameFlag.Event)
        assertThat(p("Event 03", category = "UK | PPV EVENTS").flags).contains(NameFlag.Event)
        assertThat(p("UK: Sky Sports Main Event").flags).doesNotContain(NameFlag.Event)
    }

    @Test
    fun `adult content is flagged but Adult Swim is not`() {
        assertThat(p("Something", category = "XXX | Adults").flags).contains(NameFlag.Adult)
        assertThat(p("Movies 18+").flags).contains(NameFlag.Adult)
        assertThat(p("US: Adult Swim").flags).doesNotContain(NameFlag.Adult)
    }

    @Test
    fun `24-7 loops are flagged`() {
        assertThat(p("24/7 Friends").flags).contains(NameFlag.Loop247)
        assertThat(p("The Simpsons", category = "24-7 Shows").flags).contains(NameFlag.Loop247)
    }

    // ---- regions -----------------------------------------------------------------------------

    @Test
    fun `country and language detection`() {
        assertThat(p("UK: BBC One").country).isEqualTo("GB")
        assertThat(p("|DE| ARD").country).isEqualTo("DE")
        assertThat(p("AR: MBC 1").language).isEqualTo("ar")
        assertThat(p("AR: MBC 1").country).isNull()
        assertThat(p("Some Channel", category = "Germany HD").country).isEqualTo("DE")
        assertThat(p("Some Channel", category = "UK SPORTS").country).isEqualTo("GB")
        assertThat(p("Some Channel", category = "IN CINEMAS").country).isNull()
        assertThat(p("Some Channel", category = "IT | Sport").country).isEqualTo("IT")
    }

    @Test
    fun `country from name wins over category`() {
        assertThat(p("FR: TF1", category = "UK | Mixed").country).isEqualTo("FR")
    }

    @Test
    fun `non latin names produce non empty keys`() {
        val arabic = p("AR: قناة الجزيرة HD")
        assertThat(arabic.matchKey).isNotEmpty()
        assertThat(arabic.quality).isEqualTo(Quality.HD)
        assertThat(p("RU: Первый канал").matchKey).isEqualTo("первыиканал")
    }

    // ---- iptv-org style: "Name (720p) [Not 24/7]" + tvg-id "Name.cc@Feed" ------------------

    @Test
    fun `status markers are removed and do not set flags`() {
        val p = p("CCTN 47 (1080p) [Not 24/7]")
        assertThat(p.displayName).isEqualTo("CCTN 47")
        assertThat(p.flags).doesNotContain(NameFlag.Loop247)
        assertThat(p("RTL (720p) [Geo-blocked]").displayName).isEqualTo("RTL")
        assertThat(p("24/7 Friends").flags).contains(NameFlag.Loop247) // real 24/7 channels still flagged
    }

    @Test
    fun `any resolution tag is a quality`() {
        assertThat(p("CCTV+ 1 (600p)").quality).isEqualTo(Quality.SD)
        assertThat(p("CCTV+ 1 (600p)").displayName).isEqualTo("CCTV+ 1")
        assertThat(p("X (360p)").quality).isEqualTo(Quality.SD)
        assertThat(p("X (1440p)").quality).isEqualTo(Quality.FHD)
    }

    @Test
    fun `country from guide id merges quality feeds`() {
        val sd = ChannelNameParser.parse("CCTV-1 (720p)", "General", tvgId = "CCTV1.cn@SD")
        val hd = ChannelNameParser.parse("CCTV-1 HD (1080p)", "General", tvgId = "CCTV1.cn@HD")
        assertThat(sd.country).isEqualTo("CN")
        assertThat(sd.identityKey).isEqualTo(hd.identityKey)
        assertThat(ChannelNameParser.parse("BBC One", tvgId = "BBCOne.uk").country).isEqualTo("GB")
    }

    @Test
    fun `regional feeds stay distinct`() {
        val base = ChannelNameParser.parse("NatureTime (1080p)", tvgId = "NatureTime.ca@SD")
        val au = ChannelNameParser.parse("NatureTime AU (1080p)", tvgId = "NatureTime.ca@AU")
        assertThat(base.identityKey).isNotEqualTo(au.identityKey)
        assertThat(ChannelNameParser.normalizeTvgId("NatureTime.ca@AU")).isEqualTo("naturetime.ca@au")
        assertThat(ChannelNameParser.normalizeTvgId("CCTV1.cn@HD")).isEqualTo("cctv1.cn")
    }

    @Test
    fun `guide id country is only evidence when nothing better exists`() {
        assertThat(ChannelNameParser.parse("FR: TF1", tvgId = "TF1.de").country).isEqualTo("FR")
        assertThat(ChannelNameParser.countryFromTvgId("Something.tv")).isNull()
        assertThat(ChannelNameParser.countryFromTvgId("no-dot-id")).isNull()
        assertThat(ChannelNameParser.countryFromTvgId("Foo.zz")).isNull()
    }

    @Test
    fun `never returns an empty display name`() {
        assertThat(p("UK: HD").displayName).isNotEmpty()
        assertThat(p("").displayName).isEqualTo("")
    }
}
