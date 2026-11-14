package app.pureiptv.core.parsing

/**
 * Removes credentials from a URL before it is logged or shown in an error report: user:pass@host,
 * username/password-style query values, and the user/password path segments of Xtream stream URLs
 * are each replaced by three asterisks. See RedactTest for examples.
 */
fun redact(url: String): String {
    var s = url.replace(Regex("""://[^/@\s]+@"""), "://***@") // user:pass@host
    s = s.replace(
        Regex("""([?&](?:username|user|password|pass|pwd|token|key|auth)=)[^&#\s]*""", RegexOption.IGNORE_CASE),
        "$1***",
    )
    s = s.replace(Regex("""/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/"""), "/$1/***/***/")
    return s
}
