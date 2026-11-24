package com.maxrave.domain.spotifyimport

/** A version of a recording that makes it a different track for matching purposes. */
enum class VersionMarker(
    val queryWord: String,
    /** Strong markers almost never describe what someone saved on Spotify by accident. */
    val strong: Boolean,
) {
    LIVE("live", false),
    REMIX("remix", false),
    ACOUSTIC("acoustic", false),
    DEMO("demo", false),
    RERECORDED("re-recorded", false),
    INSTRUMENTAL("instrumental", true),
    KARAOKE("karaoke", true),
    COVER("cover", true),
    SPED_UP("sped up", true),
    SLOWED("slowed", true),
    ACAPELLA("acapella", true),
}

/**
 * Turns a track title into what is compared: a lowercase, accent-free, punctuation-free core, and
 * the version markers found around it.
 *
 * Spotify writes versions as a dash suffix ("Song - Remastered 2011", "Song - Live at Wembley"),
 * YouTube Music in brackets ("Song (Live)"). Both are cut off the core; only the words that change
 * the recording (live, remix, acoustic, …) are kept, as markers. Remasters, radio edits, mono/stereo
 * and "From the film" notes are dropped as noise. A dash suffix with none of the known words is part
 * of the real title ("Love - Hate") and stays in the core.
 */
object TitleNormalizer {
    data class Parsed(
        /** Compared title: folded, lowercase, single-spaced, no punctuation, no featured artists. */
        val core: String,
        /** The original title minus "feat." parts and noise qualifiers, for the search query. */
        val queryTitle: String,
        val markers: Set<VersionMarker>,
    )

    private val bracketRegex = Regex("""[(\[{（【]([^)\]}）】]*)[)\]}）】]""")
    private val featRegex = Regex("""(?:^|\s)(?:feat\.?|ft\.?|featuring)\s.*$""")
    private val featQualifier = Regex("""^\s*(?:feat\.?|ft\.?|featuring|with)\s""")
    private val dashSplit = Regex("""\s[-–—]\s""")

    /** Words that make a dash suffix a qualifier rather than part of the title. */
    private val qualifierWords =
        Regex(
            """\b(?:remaster\w*|live|remix\w*|mix|edit|version|acoustic|instrumental|karaoke|mono|stereo|demo|""" +
                """feat|ft|featuring|from|radio|extended|original|single|deluxe|bonus|session|unplugged|cover|""" +
                """sped up|slowed|reprise|re-?recorded|anniversary|edition|recorded|take \d+|acapella|a cappella|""" +
                """soundtrack|theme|bootleg|rework|vip|\d{4} mix)\b""",
        )

    private data class MarkerRule(
        val marker: VersionMarker,
        val regex: Regex,
        /** Strong markers are looked for in the whole title, the others only in qualifiers. */
        val anywhere: Boolean,
    )

    private val markerRules =
        listOf(
            MarkerRule(VersionMarker.LIVE, Regex("""\blive\b|\bunplugged\b"""), false),
            MarkerRule(VersionMarker.REMIX, Regex("""\bremix\b|\brmx\b|\brework\b|\bbootleg\b|\bvip\b|\b(?!original\b|radio\b|album\b|single\b|extended\b|mono\b|stereo\b)\w+ mix\b"""), false),
            MarkerRule(VersionMarker.ACOUSTIC, Regex("""\bacoustic\b|\bunplugged\b"""), false),
            MarkerRule(VersionMarker.DEMO, Regex("""\bdemo\b"""), false),
            MarkerRule(VersionMarker.RERECORDED, Regex("""\bre-?recorded\b|\btaylor'?s version\b"""), false),
            MarkerRule(VersionMarker.INSTRUMENTAL, Regex("""\binstrumental\b|\boff ?vocal\b"""), true),
            MarkerRule(VersionMarker.KARAOKE, Regex("""\bkaraoke\b"""), true),
            MarkerRule(VersionMarker.COVER, Regex("""\bcover\b"""), true),
            MarkerRule(VersionMarker.SPED_UP, Regex("""\bsped up\b|\bspeed up\b|\bnightcore\b"""), true),
            MarkerRule(VersionMarker.SLOWED, Regex("""\bslowed\b"""), true),
            MarkerRule(VersionMarker.ACAPELLA, Regex("""\ba ?cappella\b|\bacapella\b"""), true),
        )

    /** Qualifiers that only add noise to a search query. */
    private val noiseQualifier = Regex("""remaster|radio edit|mono|stereo|from\b|soundtrack|bonus|deluxe|edition|anniversary|single version|album version|explicit|clean""")

    fun parse(title: String): Parsed {
        val text = foldPunctuation(fold(title.lowercase()))
        val qualifiers = mutableListOf<String>()

        // Bracketed parts: always qualifiers.
        var core = bracketRegex.replace(text) { m ->
            qualifiers += m.groupValues[1]
            " "
        }
        // Dash suffixes: qualifiers only when they read like one.
        val parts = core.split(dashSplit)
        if (parts.size > 1) {
            val keep = StringBuilder(parts.first())
            parts.drop(1).forEach { part ->
                if (qualifierWords.containsMatchIn(part)) qualifiers += part else keep.append(' ').append(part)
            }
            core = keep.toString()
        }
        core = featRegex.replace(core, " ")

        val markers = mutableSetOf<VersionMarker>()
        val qualifierText = qualifiers.filterNot { featQualifier.containsMatchIn(it) }.joinToString(" | ")
        markerRules.forEach { rule ->
            val scope = if (rule.anywhere) "$core | $qualifierText" else qualifierText
            if (rule.regex.containsMatchIn(scope)) markers += rule.marker
        }

        val cleanCore = clean(core).ifEmpty { clean(text) }
        val queryTitle = buildQueryTitle(title)
        return Parsed(cleanCore, queryTitle, markers)
    }

    /** Lowercase, accent-free, "the "-less, punctuation-free artist name. */
    fun normalizeName(name: String): String {
        val cleaned = clean(foldPunctuation(fold(name.lowercase())))
        return cleaned.removePrefix("the ").trim()
    }

    /** "A & B", "A, B", "A x B", "A feat. B" → A, B. A single name stays whole. */
    fun splitArtists(names: String): List<String> =
        names
            .split(Regex("""\s*(?:,|&|\band\b|\bx\b|\bfeat\.?|\bft\.?|\bvs\.?|/)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(names) }

    /** Keeps the original casing and script but drops "feat." parts and noise qualifiers. */
    private fun buildQueryTitle(title: String): String {
        var q = bracketRegex.replace(foldPunctuation(title)) { m ->
            val inner = m.groupValues[1].lowercase()
            if (featQualifier.containsMatchIn(inner) || noiseQualifier.containsMatchIn(inner)) " " else m.value
        }
        val parts = q.split(dashSplit)
        if (parts.size > 1) {
            q =
                parts.first() +
                parts.drop(1).joinToString("") { part ->
                    val lower = part.lowercase()
                    when {
                        noiseQualifier.containsMatchIn(lower) || lower.trimStart().startsWith("feat") -> ""
                        else -> " $part"
                    }
                }
        }
        q = Regex("""\s(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE).replace(q, " ")
        return q.replace(Regex("""\s+"""), " ").trim()
    }

    private fun clean(s: String): String {
        val out = StringBuilder(s.length)
        var lastSpace = true
        s.replace("&", " and ").forEach { c ->
            when {
                c == '\'' -> Unit // "don't" == "dont"
                c.isLetterOrDigit() -> {
                    out.append(c)
                    lastSpace = false
                }
                !lastSpace -> {
                    out.append(' ')
                    lastSpace = true
                }
            }
        }
        return out.toString().trim()
    }

    private fun foldPunctuation(s: String): String =
        buildString(s.length) {
            s.forEach { c ->
                append(
                    when (c) {
                        '’', '‘', '`', '´' -> '\''
                        '“', '”' -> '"'
                        '–', '—', '‐', '‑' -> '-'
                        ' ' -> ' '
                        else -> c
                    },
                )
            }
        }

    private val foldMap: Map<Char, String> by lazy {
        val groups =
            listOf(
                "àáâãäåāăąạảấầẩẫậắằẳẵặǎ" to "a",
                "çćĉċč" to "c",
                "ďđ" to "d",
                "èéêëēĕėęěẹẻẽếềểễệ" to "e",
                "ĝğġģ" to "g",
                "ĥħ" to "h",
                "ìíîïĩīĭįıỉịǐ" to "i",
                "ĵ" to "j",
                "ķ" to "k",
                "ĺļľŀł" to "l",
                "ñńņňŉ" to "n",
                "òóôõöøōŏőơọỏốồổỗộớờởỡợǒ" to "o",
                "ŕŗř" to "r",
                "śŝşšș" to "s",
                "ţťŧț" to "t",
                "ùúûüũūŭůűųưụủứừửữựǔ" to "u",
                "ŵ" to "w",
                "ýÿŷỳỵỷỹ" to "y",
                "źżž" to "z",
            )
        buildMap {
            groups.forEach { (chars, base) -> chars.forEach { put(it, base) } }
            put('ß', "ss")
            put('æ', "ae")
            put('œ', "oe")
            put('þ', "th")
            put('ð', "d")
        }
    }

    /** Latin diacritics only; other scripts are kept as they are. Expects lowercase input. */
    fun fold(s: String): String {
        if (s.all { it.code < 128 }) return s
        return buildString(s.length) {
            s.forEach { c -> append(foldMap[c] ?: c.toString()) }
        }
    }
}
