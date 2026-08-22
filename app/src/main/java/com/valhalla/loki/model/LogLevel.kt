package com.valhalla.loki.model

/**
 * A logcat priority, parsed back out of a captured line.
 *
 * **Declaration order is severity order**, lowest first, and the viewer's level filter relies on it:
 * an enum is [Comparable] by ordinal, so `line.level >= LogLevel.WARN` is the whole "warnings and
 * above" test. Adding a level in the wrong position silently reorders every filter, so keep the list
 * in the order logcat itself documents.
 *
 * [UNKNOWN] sits below [VERBOSE] deliberately. Lines with no priority at all — blank lines, the
 * `--------- beginning of crash` separators logcat injects — then fall outside every filter except
 * "All", which is what a user asking for "errors and above" means.
 */
enum class LogLevel(val letter: Char) {
    UNKNOWN('?'),
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),

    /** `F` in logcat's output, `A` in the formats that spell [android.util.Log.ASSERT] out. */
    FATAL('F'),
    ;

    companion object {

        /**
         * The *threadtime* prefix, which is what `logcat` with no `-v` produces and therefore what
         * every capture Loki makes actually looks like:
         * `08-22 04:44:01.123  1234  1234 D SomeTag: message`.
         *
         * This is anchored on the **column shape** — date, time, then numeric columns — and reads
         * the letter positionally, without looking at the tag at all. That is the whole point.
         * [TAGGED] below has to recognise the tag to know it has found a real prefix, which makes
         * any tag it cannot spell invisible; a positional match has no opinion about tags, so
         * `D VRI[FirstRunActivity]:` and `F libc++abi:` parse correctly.
         *
         * Two or three numeric columns: two is plain threadtime (pid, tid), three tolerates the
         * extra uid column `-v uid` adds. The letter cannot be confused with a column because it is
         * not a digit.
         *
         * Optional four-digit year prefix for `-v year`. Loki does not pass it, but a log read back
         * from an older capture or a file the user placed there costs nothing to accept.
         */
        private val THREADTIME =
            Regex("""^(?:\d{4}-)?\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}(?:\s+\d+){2,3}\s+([VDIWEAF])\s""")

        /**
         * Fallback for the *brief* format — `D/SomeTag( 1234): message` — and for anything else
         * carrying a recognisable `letter tag:` pair.
         *
         * Requiring a tag and a `:` or `(` after it is what keeps a bare capital letter mid-sentence
         * from reading as a priority. The cost is that a tag containing a character outside the
         * class does not match, which is why [THREADTIME] is consulted first rather than this: for a
         * threadtime line, failing here does not merely lose the level, it lets the regex keep
         * scanning into the *message*, where `retry E/Something: nope` would report a `W` line as
         * ERROR. Anchoring the common case removes that whole class of mis-read.
         */
        private val TAGGED = Regex("""(?:^|\s)([VDIWEAF])[/\s][A-Za-z0-9_.\-$]+\s*[:(]""")

        /** The [LogLevel] a line carries, or [UNKNOWN] if it carries none. */
        fun of(line: String): LogLevel {
            if (line.isBlank()) return UNKNOWN
            THREADTIME.find(line)?.let { return ofLetter(it.groupValues[1][0]) }
            TAGGED.find(line)?.groupValues?.get(1)?.firstOrNull()?.let { return ofLetter(it) }
            // Brief format with a tag containing characters the class above rejects — `D/my tag:`.
            // Only safe because it is anchored at index 0.
            if (line.length >= 2 && line[1] == '/') return ofLetter(line[0])
            return UNKNOWN
        }

        private fun ofLetter(letter: Char): LogLevel = when (letter.uppercaseChar()) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E' -> ERROR
            'F', 'A' -> FATAL
            else -> UNKNOWN
        }
    }
}
