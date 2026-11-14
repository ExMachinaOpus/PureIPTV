package app.pureiptv.core.parsing.names

/** A provider prefix resolves to a country, a language, or both. */
internal data class Region(val country: String?, val language: String?)

/**
 * Prefix codes and names providers use for countries/languages. Keys are uppercase.
 * Only unambiguous entries belong here: a false positive strips part of a real channel name.
 *
 * Note: providers use "AR" for Arabic (not Argentina) and "UK" for Great Britain.
 */
internal object Regions {

    private fun c(iso: String, lang: String? = null) = Region(iso, lang)
    private fun l(lang: String) = Region(null, lang)

    /** Short codes, matched only when followed by a delimiter (`UK:`, `|DE|`, `[FR]`). */
    val codes: Map<String, Region> = mapOf(
        "UK" to c("GB", "en"), "GB" to c("GB", "en"), "ENG" to c("GB", "en"),
        "US" to c("US", "en"), "USA" to c("US", "en"),
        "CA" to c("CA"), "CAN" to c("CA"),
        "AU" to c("AU", "en"), "AUS" to c("AU", "en"),
        "NZ" to c("NZ", "en"), "IE" to c("IE", "en"), "IRL" to c("IE", "en"),
        "DE" to c("DE", "de"), "GER" to c("DE", "de"),
        "AT" to c("AT", "de"), "CH" to c("CH"),
        "FR" to c("FR", "fr"), "FRA" to c("FR", "fr"),
        "BE" to c("BE"), "NL" to c("NL", "nl"), "LU" to c("LU"),
        "ES" to c("ES", "es"), "ESP" to c("ES", "es"),
        "PT" to c("PT", "pt"), "POR" to c("PT", "pt"), "BR" to c("BR", "pt"), "BRA" to c("BR", "pt"),
        "IT" to c("IT", "it"), "ITA" to c("IT", "it"),
        "SE" to c("SE", "sv"), "SWE" to c("SE", "sv"),
        "NO" to c("NO", "no"), "NOR" to c("NO", "no"),
        "DK" to c("DK", "da"), "DEN" to c("DK", "da"),
        "FI" to c("FI", "fi"), "FIN" to c("FI", "fi"),
        "IS" to c("IS", "is"),
        "PL" to c("PL", "pl"), "POL" to c("PL", "pl"),
        "CZ" to c("CZ", "cs"), "SK" to c("SK", "sk"), "HU" to c("HU", "hu"),
        "RO" to c("RO", "ro"), "BG" to c("BG", "bg"), "GR" to c("GR", "el"), "GRE" to c("GR", "el"),
        "TR" to c("TR", "tr"), "TUR" to c("TR", "tr"),
        "RU" to c("RU", "ru"), "RUS" to c("RU", "ru"), "UA" to c("UA", "uk"), "UKR" to c("UA", "uk"),
        "HR" to c("HR", "hr"), "RS" to c("RS", "sr"), "SRB" to c("RS", "sr"),
        "SI" to c("SI", "sl"), "BA" to c("BA"), "MK" to c("MK", "mk"), "AL" to c("AL", "sq"),
        "EXYU" to l("sh"), "EX-YU" to l("sh"), "YU" to l("sh"),
        "MX" to c("MX", "es"), "MEX" to c("MX", "es"),
        "CO" to c("CO", "es"), "CL" to c("CL", "es"), "PE" to c("PE", "es"),
        "LAT" to l("es"), "LATINO" to l("es"), "LATAM" to l("es"),
        "IN" to c("IN"), "IND" to c("IN"), "PK" to c("PK"), "PAK" to c("PK"), "BD" to c("BD", "bn"),
        "AR" to l("ar"), "ARA" to l("ar"), "ARAB" to l("ar"), "ARABIC" to l("ar"),
        "IR" to c("IR", "fa"), "IL" to c("IL", "he"),
        "AF" to c("AF"), "KU" to l("ku"), "KURD" to l("ku"),
        "CN" to c("CN", "zh"), "HK" to c("HK", "zh"), "TW" to c("TW", "zh"),
        "JP" to c("JP", "ja"), "KR" to c("KR", "ko"), "PH" to c("PH"), "TH" to c("TH", "th"),
        "VN" to c("VN", "vi"), "ID" to c("ID", "id"), "MY" to c("MY"),
        "ZA" to c("ZA"), "NG" to c("NG"),
        "EN" to l("en"), "FR-CA" to c("CA", "fr"),
    )

    /**
     * Codes that are also common English words. In category names these only count when followed
     * by a real delimiter (`IT | Sport`), never by a plain space (`IN CINEMAS`, `NO SIGNAL`).
     */
    val wordLikeCodes: Set<String> = setOf("IN", "IS", "NO", "IT", "MY", "ID", "AT", "BE", "CO", "SI", "AL", "BA", "EN")

    /** Full names, matched as whole words in category names only. */
    val names: Map<String, Region> = mapOf(
        "UNITED KINGDOM" to codes.getValue("UK"), "ENGLAND" to codes.getValue("UK"),
        "UNITED STATES" to codes.getValue("US"), "CANADA" to codes.getValue("CA"),
        "AUSTRALIA" to codes.getValue("AU"), "IRELAND" to codes.getValue("IE"),
        "GERMANY" to codes.getValue("DE"), "DEUTSCHLAND" to codes.getValue("DE"),
        "AUSTRIA" to codes.getValue("AT"), "SWITZERLAND" to codes.getValue("CH"),
        "FRANCE" to codes.getValue("FR"), "BELGIUM" to codes.getValue("BE"),
        "NETHERLANDS" to codes.getValue("NL"), "NEDERLAND" to codes.getValue("NL"), "HOLLAND" to codes.getValue("NL"),
        "SPAIN" to codes.getValue("ES"), "ESPANA" to codes.getValue("ES"),
        "PORTUGAL" to codes.getValue("PT"), "BRAZIL" to codes.getValue("BR"), "BRASIL" to codes.getValue("BR"),
        "ITALY" to codes.getValue("IT"), "ITALIA" to codes.getValue("IT"),
        "SWEDEN" to codes.getValue("SE"), "SVERIGE" to codes.getValue("SE"),
        "NORWAY" to codes.getValue("NO"), "NORGE" to codes.getValue("NO"),
        "DENMARK" to codes.getValue("DK"), "DANMARK" to codes.getValue("DK"),
        "FINLAND" to codes.getValue("FI"), "SUOMI" to codes.getValue("FI"),
        "POLAND" to codes.getValue("PL"), "POLSKA" to codes.getValue("PL"),
        "TURKEY" to codes.getValue("TR"), "TURKIYE" to codes.getValue("TR"),
        "GREECE" to codes.getValue("GR"), "ROMANIA" to codes.getValue("RO"),
        "RUSSIA" to codes.getValue("RU"), "UKRAINE" to codes.getValue("UA"),
        "MEXICO" to codes.getValue("MX"), "INDIA" to codes.getValue("IN"), "PAKISTAN" to codes.getValue("PK"),
        "ARABIC" to codes.getValue("AR"), "LATINO" to codes.getValue("LAT"),
    )
}
