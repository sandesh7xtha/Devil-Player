package com.offline.musicplayer.data.parser

import java.text.Normalizer

/**
 * Result of [TrackNameParser.parse].
 *
 * @property artist The artist to use, or `null` when none could be determined.
 * @property title The cleaned track title.
 */
data class ParsedTrack(
    val artist: String?,
    val title: String
)

/**
 * Turns messy local file names into a clean artist and title, e.g.
 * `Adele_-_Turning_Tables(256k)` or `Turning_Tables_-_Adele (256k).mp3`.
 *
 * What it does, in order:
 *  1. Strips audio file extensions and noise tags: bitrates (`256k`, `320kbps`),
 *     sample rates, resolutions, web addresses and bracketed junk such as
 *     `(Official Video)`, `[HQ]` or `(Explicit)`. Meaningful brackets such as
 *     `(feat. X)`, `(Live)` or `(Taylor's Version)` are kept.
 *  2. Normalises separators (`_`, en/em dashes, `|`) and drops leading track numbers.
 *  3. Splits into "artist" and "title" parts and decides which is which, using in order:
 *     the artist already tagged on the file ([parse]'s `artistHint`), artists seen in the
 *     library tags ([knownArtists]), fragments that repeat across many file names
 *     ([nameCorpus]), and finally the usual `Artist - Title` convention. This is what
 *     lets a flipped `Title - Artist` name come out right.
 *  4. Falls back to clean title capitalisation when there is no artist separator.
 *
 * Without any library knowledge a flipped name such as `Turning Tables - Adele` is
 * genuinely ambiguous, so it is read as `Artist - Title`. Pass the library's tagged
 * artists (and optionally the untagged file names) so the parser can tell.
 *
 * Properly tagged data is left alone: capitalisation is only changed for text that was
 * derived from a file name, never for an existing tag.
 *
 * @param knownArtists Artist names from the library tags. Repeated names are fine and
 *   make that artist a stronger signal.
 * @param nameCorpus Raw file names or titles of the library, used to spot repeated
 *   artist fragments in untagged files.
 */
class TrackNameParser(
    knownArtists: Collection<String> = emptyList(),
    nameCorpus: Collection<String> = emptyList()
) {
    // Tagged-artist keys -> how many tracks carry them (strong signal).
    private val strongCounts = HashMap<String, Int>()

    // Preferred spelling of a tagged artist, so "adele" in a file name becomes "Adele".
    private val displayNames = HashMap<String, String>()

    // Name fragment key -> number of file names it appears in (weak signal).
    private val fragmentCounts = HashMap<String, Int>()

    init {
        for (name in knownArtists) {
            val clean = name.trim()
            if (isUnknownArtist(clean)) continue
            val wholeKey = keyOf(clean)
            if (wholeKey.isNotEmpty() && !displayNames.containsKey(wholeKey)) {
                displayNames[wholeKey] = clean
            }
            for (key in variantsOf(clean)) {
                strongCounts[key] = (strongCounts[key] ?: 0) + 1
            }
        }
        for (name in nameCorpus) {
            if (name.length > MAX_PARSE_LENGTH) continue
            val parts = splitParts(normalizeName(name), null)
            if (parts.size < 2) continue
            val keys = parts
                .map { keyOf(it) }
                .filter { it.isNotEmpty() && !isDescriptor(it) && !NUMERIC_PART.matches(it) }
                .toSet()
            for (key in keys) {
                fragmentCounts[key] = (fragmentCounts[key] ?: 0) + 1
            }
        }
    }

    /**
     * @param rawName A file name (with or without extension) or a title tag.
     * @param artistHint The artist tagged on the file, if any. "Unknown" values are ignored.
     * @param albumHint The album tagged on the file; a part equal to it is dropped from
     *   three-part names such as `Artist - Title - Album`.
     */
    fun parse(
        rawName: String,
        artistHint: String? = null,
        albumHint: String? = null
    ): ParsedTrack {
        val hint = artistHint?.trim()?.takeUnless { isUnknownArtist(it) }
        // Real file names are at most 255 characters. Refuse to run regexes over huge,
        // corrupt tags instead of risking slow backtracking.
        if (rawName.length > MAX_PARSE_LENGTH) return ParsedTrack(hint, rawName.trim())

        val normalized = normalizeName(rawName)
        if (normalized.isEmpty()) {
            return ParsedTrack(hint, AUDIO_EXTENSION.replace(rawName.trim(), ""))
        }

        var parts = splitParts(normalized, hint)
        if (parts.isEmpty()) return ParsedTrack(hint, normalized)
        if (parts.size >= 3) {
            parts = parts.filterNot { NUMERIC_PART.matches(it) }.ifEmpty { parts }
        }

        val albumKey = albumHint?.trim()
            ?.takeUnless { isUnknownAlbum(it) }
            ?.let { keyOf(it) }
            ?.takeIf { it.isNotEmpty() }

        return if (hint != null) {
            parseWithHint(parts, hint, albumKey)
        } else {
            parseWithoutHint(parts, albumKey)
        }
    }

    private fun parseWithHint(parts: List<String>, hint: String, albumKey: String?): ParsedTrack {
        val index = if (parts.size >= 2) parts.indexOfFirst { matchesArtist(it, hint) } else -1
        // The tag is trusted: if the name does not mention the artist it is just a title.
        if (index < 0) return ParsedTrack(hint, parts.joinToString(SEPARATOR))

        val rest = dropAlbum(parts.filterIndexed { i, _ -> i != index }, albumKey)
        return ParsedTrack(hint, fixCase(rest.joinToString(SEPARATOR)))
    }

    private fun parseWithoutHint(parts: List<String>, albumKey: String?): ParsedTrack {
        if (parts.size == 1) return ParsedTrack(null, fixCase(parts[0]))

        val artistIndex = pickArtistIndex(parts)
            ?: return ParsedTrack(null, fixCase(parts.joinToString(SEPARATOR)))
        val artistPart = parts[artistIndex]
        val rest = dropAlbum(parts.filterIndexed { i, _ -> i != artistIndex }, albumKey)
        val artist = displayNames[keyOf(artistPart)] ?: fixCase(artistPart)
        return ParsedTrack(artist, fixCase(rest.joinToString(SEPARATOR)))
    }

    /** Index of the part that is the artist, or `null` when the name has no artist. */
    private fun pickArtistIndex(parts: List<String>): Int? {
        // 1. An artist that is tagged elsewhere in the library wins.
        var bestIndex = -1
        var bestScore = 0
        parts.forEachIndexed { index, part ->
            val score = strongScore(part)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        if (bestIndex >= 0) return bestIndex

        // 2. "Hello - Remastered 2011" is a title with a suffix, not "artist - title".
        if (parts.size == 2 && (isDescriptor(parts[0]) || isDescriptor(parts[1]))) return null

        // 3. A fragment that repeats across file names is probably an artist.
        val weights = parts.map { weakWeight(it) }
        val max = weights.maxOrNull() ?: 0
        if (max >= 2 && weights.count { it == max } == 1) return weights.indexOf(max)

        // 4. "Title (feat. X) - Artist": a bracketed feat. clause belongs to the title.
        if (parts.size == 2 &&
            BRACKETED_FEAT.containsMatchIn(parts[0]) &&
            !BRACKETED_FEAT.containsMatchIn(parts[1])
        ) {
            return 1
        }

        // 5. Convention: "Artist - Title".
        return 0
    }

    private fun strongScore(part: String): Int =
        variantsOf(part).maxOfOrNull { strongCounts[it] ?: 0 } ?: 0

    private fun isStrongArtist(part: String): Boolean = strongScore(part) > 0

    private fun weakWeight(part: String): Int =
        variantsOf(part).maxOfOrNull { fragmentCounts[it] ?: 0 } ?: 0

    private fun splitParts(normalized: String, hint: String?): List<String> {
        val parts = normalized.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 1) return parts
        return splitOnBareHyphen(parts[0], hint) ?: parts
    }

    /**
     * `Adele-Hello` has no spaced separator and could be a hyphenated word (`Jay-Z`), so it
     * is only split when exactly one side is a recognised artist.
     */
    private fun splitOnBareHyphen(text: String, hint: String?): List<String>? {
        for (index in 1 until text.length - 1) {
            if (text[index] != '-') continue
            val left = text.substring(0, index).trim()
            val right = text.substring(index + 1).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val leftIsArtist = (hint != null && matchesArtist(left, hint)) || isStrongArtist(left)
            val rightIsArtist = (hint != null && matchesArtist(right, hint)) || isStrongArtist(right)
            if (leftIsArtist != rightIsArtist) return listOf(left, right)
        }
        return null
    }

    companion object {
        private const val SEPARATOR = " - "
        private const val MAX_PARSE_LENGTH = 400

        private val AUDIO_EXTENSION = Regex(
            """\.(?:mp3|mp2|flac|m4a|m4b|aac|wav|wave|ogg|oga|opus|wma|aiff?|alac|ape|mka|amr|3gp|weba|webm|mp4)\s*$""",
            RegexOption.IGNORE_CASE
        )

        // 256k, 320kbps, 44.1kHz, 24bit, 1080p ... anywhere in the name.
        private val BITRATE_TAG = Regex(
            """(?<![\p{L}\p{N}])(?:\d{2,3}\s?(?:kbps|kbit/s|kbit|kb/s)|(?:64|96|112|128|160|192|224|256|320)\s?k|\d{2}(?:\.\d)?\s?khz|\d{2}\s?-?\s?bit|(?:2160|1440|1080|720|480|360)p(?:60)?)(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE
        )

        private val DOMAIN_TAG = Regex(
            """(?:https?://)?www\.[a-z0-9-]+(?:\.[a-z0-9-]+)+|(?<![\p{L}\p{N}-])[a-z0-9-]+\.(?:com|net|org|info|biz)(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE
        )

        // \u3010 \u3011 are the full-width brackets used in some Asian file names.
        private val EMPTY_BRACKETS = Regex("""[(\[{\u3010]\s*[)\]}\u3011]""")
        private val BRACKET_GROUP = Regex("""[(\[{\u3010]([^)\]}\u3011]*)[)\]}\u3011]""")

        // en dash, em dash, horizontal bar, figure dash, minus sign
        private val DASHES = Regex("""\s*[\u2013\u2014\u2015\u2012\u2212]\s*""")

        // "... | Official Video", "... - Official Audio"
        private val TRAILING_NOISE = Regex(
            """\s*(?:[|\uFF5C]|\s-\s)\s*(?:official\s+)?(?:(?:music|lyric|lyrics|audio|hd|hq)\s+)*(?:video|audio|lyrics?|visuali[sz]er|mv)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val PIPE = Regex("""\s*[|\uFF5C]\s*""")
        private val WHITESPACE = Regex("""\s+""")

        // "01 - ", "01. ", "01-", "1-05 ", "1. ", "01 " (only zero-padded). Leaves "21 Guns" and "7 Rings" alone.
        private val TRACK_NUMBER_PREFIX = Regex(
            """^\s*(?:\d{1,2}-\d{2}\s+|\d{2,3}\s*[-.)]\s*|\d\s*[.)]\s+|0\d\s+(?=\p{L}))"""
        )
        private val EDGE_JUNK_START = Regex("""^[\s\-.,:;|]+""")
        private val EDGE_JUNK_END = Regex("""[\s\-,:;|]+$""")

        private val NUMERIC_PART = Regex("""\d{1,3}""")
        private val YEAR = Regex("""(?:19|20)\d{2}""")
        private val RESOLUTION = Regex("""\d{3,4}p(?:60)?""")
        private val FEAT_CLAUSE = Regex(
            """\s*[(\[]?\s*\b(?:feat|ft|featuring)\b\.?.*$""",
            RegexOption.IGNORE_CASE
        )
        private val BRACKETED_FEAT = Regex(
            """[(\[]\s*(?:feat|ft|featuring)\b""",
            RegexOption.IGNORE_CASE
        )
        private val COLLAB_SPLIT = Regex(
            """\s*(?:,|&|\+|\u00D7|\band\b|\bx\b|\bvs\.?\b)\s*""",
            RegexOption.IGNORE_CASE
        )
        private val DIACRITICS = Regex("""\p{M}+""")
        private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

        // A bracket group is junk only if every word in it is one of these.
        private val NOISE_WORDS = setOf(
            "official", "video", "videoclip", "clip", "music", "audio", "lyric", "lyrics",
            "lyrical", "visualizer", "visualiser", "hd", "hq", "4k", "8k", "mv", "m/v",
            "explicit", "clean", "full", "song", "only", "with", "free", "download", "new",
            "topic", "vevo", "oficial", "officiel", "ufficiale", "mp3", "flac", "wav", "aac",
            "m4a", "ogg", "high", "quality"
        )

        // Words that describe a version of a song. A part made only of these is never an artist.
        private val DESCRIPTOR_WORDS = setOf(
            "remaster", "remastered", "remix", "mix", "edit", "version", "ver", "live",
            "acoustic", "instrumental", "karaoke", "unplugged", "reprise", "mono", "stereo",
            "demo", "deluxe", "bonus", "extended", "original", "radio", "single", "album",
            "club", "dub", "explicit", "clean", "uncut", "session", "track", "edition", "cut"
        )
        private val LIVE_PREPOSITIONS = setOf("at", "in", "from", "on")

        private val SMALL_WORDS = setOf(
            "a", "an", "and", "as", "at", "but", "by", "for", "in", "nor", "of", "on", "or",
            "the", "to", "up", "vs", "feat", "ft"
        )
        private val UNKNOWN_ARTISTS = setOf(
            "unknown", "unknown artist", "<unknown>", "unknown_artist", "n/a", "none"
        )

        /** True for blank values and the placeholders Android uses for a missing artist. */
        fun isUnknownArtist(name: String?): Boolean {
            val key = name?.trim()?.lowercase().orEmpty()
            return key.isEmpty() || key in UNKNOWN_ARTISTS
        }

        /** True for blank values and the placeholders Android uses for a missing album. */
        fun isUnknownAlbum(name: String?): Boolean {
            val key = name?.trim()?.lowercase().orEmpty()
            return key.isEmpty() || key == "unknown album" || key == "<unknown>"
        }

        private fun normalizeName(raw: String): String {
            var text = AUDIO_EXTENSION.replace(raw.trim(), "")
            text = text.replace('_', ' ')
            // "Adele.Turning.Tables" - dots used as word separators.
            if (text.none { it.isWhitespace() } && text.count { it == '.' } >= 2) {
                text = text.replace('.', ' ')
            }
            text = BITRATE_TAG.replace(text, " ")
            text = DOMAIN_TAG.replace(text, " ")
            text = EMPTY_BRACKETS.replace(text, " ")
            text = BRACKET_GROUP.replace(text) { match ->
                if (isNoiseGroup(match.groupValues[1])) " " else match.value
            }
            text = DASHES.replace(text, SEPARATOR)
            text = WHITESPACE.replace(text, " ").trim()
            text = TRAILING_NOISE.replace(text, "")
            text = PIPE.replace(text, SEPARATOR)
            text = WHITESPACE.replace(text, " ").trim()
            text = TRACK_NUMBER_PREFIX.replace(text, "")
            text = EDGE_JUNK_START.replace(text, "")
            text = EDGE_JUNK_END.replace(text, "")
            return text.trim()
        }

        private fun isNoiseGroup(content: String): Boolean {
            val tokens = content.lowercase()
                .split(WHITESPACE)
                .map { trimToAlphanumeric(it) }
                .filter { it.isNotEmpty() }
            return tokens.isNotEmpty() &&
                tokens.all { it in NOISE_WORDS || RESOLUTION.matches(it) }
        }

        private fun trimToAlphanumeric(value: String): String =
            value.trim { !it.isLetterOrDigit() }

        private fun isDescriptor(part: String): Boolean {
            val tokens = part.lowercase().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            if (tokens[0] == "live" && tokens.size >= 3 && tokens[1] in LIVE_PREPOSITIONS) {
                return true
            }
            return tokens.all { it in DESCRIPTOR_WORDS || YEAR.matches(it) }
        }

        private fun dropAlbum(rest: List<String>, albumKey: String?): List<String> {
            if (albumKey == null || rest.size < 2) return rest
            return rest.filter { keyOf(it) != albumKey }.ifEmpty { rest }
        }

        private fun matchesArtist(part: String, hint: String): Boolean {
            val hintKeys = variantsOf(hint)
            return variantsOf(part).any { it in hintKeys }
        }

        /** Lower-case, accent-free, punctuation-free key; "The Beatles" == "Beatles", "&" == "and". */
        private fun keyOf(value: String): String {
            val folded = DIACRITICS
                .replace(Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD), "")
                .replace("&", " and ")
            val collapsed = NON_ALNUM.replace(folded, " ").trim()
            return collapsed.removePrefix("the ").trim()
        }

        /**
         * All keys an artist string can be known by: the whole name, the name without a
         * "feat. X" clause, and each collaborator of "A & B", "A, B", "A x B".
         */
        private fun variantsOf(name: String): Set<String> {
            val keys = LinkedHashSet<String>()
            val base = keyOf(name)
            if (base.isNotEmpty()) keys.add(base)
            val withoutFeat = FEAT_CLAUSE.replace(name, "")
            if (withoutFeat != name) {
                val key = keyOf(withoutFeat)
                if (key.isNotEmpty()) keys.add(key)
            }
            for (piece in COLLAB_SPLIT.split(withoutFeat)) {
                val key = keyOf(piece)
                if (key.length >= 2) keys.add(key)
            }
            return keys
        }

        /**
         * Title-cases text that is entirely lower case or entirely upper case. Mixed-case
         * text is trusted as-is, and short all-caps names (ABBA) are left alone.
         */
        private fun fixCase(text: String): String {
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return text
            val allLower = letters.all { it.isLowerCase() }
            val allUpper = letters.all { it.isUpperCase() }
            return when {
                allLower -> toTitleCase(text)
                allUpper && (letters.length >= 6 || text.contains(' ')) -> toTitleCase(text)
                else -> text
            }
        }

        private fun toTitleCase(text: String): String {
            val words = text.split(' ').filter { it.isNotEmpty() }
            return words.mapIndexed { index, word ->
                val lower = word.lowercase()
                val isEdge = index == 0 || index == words.lastIndex
                if (!isEdge && trimToAlphanumeric(lower) in SMALL_WORDS) {
                    lower
                } else {
                    capitalizeWord(lower)
                }
            }.joinToString(" ")
        }

        private fun capitalizeWord(word: String): String {
            val builder = StringBuilder(word.length)
            var capitalizeNext = true
            for (ch in word) {
                if (capitalizeNext && ch.isLetter()) {
                    builder.append(ch.uppercaseChar())
                    capitalizeNext = false
                } else {
                    builder.append(ch)
                }
                if (ch.isDigit()) {
                    capitalizeNext = false
                } else if (ch == '-' || ch == '/' || ch == '(' || ch == '[' || ch == '"') {
                    capitalizeNext = true
                }
            }
            return builder.toString()
        }
    }
}
