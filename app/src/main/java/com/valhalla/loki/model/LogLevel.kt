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
         * Matches the priority letter as its own token, followed by a tag.
         *
         * Covers both formats Loki can end up with. `logcat` with no `-v` gives *threadtime* —
         * `08-22 04:44:01.123  1234  1234 D SomeTag: message` — where the letter is space-delimited;
         * *brief* gives `D/SomeTag( 1234): message`, where it is slash-delimited. Requiring a tag
         * and a `:` or `(` after it is what keeps a bare capital letter mid-sentence from reading as
         * a priority.
         *
         * Leftmost match wins, and in every logcat format the real prefix precedes the message, so a
         * message that itself contains `E/Something:` cannot outvote the line's actual level.
         */
        private val TAGGED = Regex("""(?:^|\s)([VDIWEAF])[/\s][A-Za-z0-9_.\-$]+\s*[:(]""")

        /** The [LogLevel] a line carries, or [UNKNOWN] if it carries none. */
        fun of(line: String): LogLevel {
            if (line.isBlank()) return UNKNOWN
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
