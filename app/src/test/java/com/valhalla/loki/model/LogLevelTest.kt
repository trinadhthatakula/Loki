package com.valhalla.loki.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LogLevel.of].
 *
 * Every "threadtime" line below is in the format plain `logcat` with no `-v` actually emits, which is
 * what [LogcatCapture] runs — `MM-DD HH:MM:SS.mmm  PID  TID L Tag: message`. Cases marked *regression*
 * were observed on a device: the viewer's header read "46 of 50 lines" under the **Debug+** filter,
 * with `D VRI[FirstRunActivity]:` lines among the four it hid — a DEBUG line invisible under a
 * DEBUG-inclusive filter, because the level came back [LogLevel.UNKNOWN].
 */
class LogLevelTest {

    private fun threadtime(level: Char, tagAndMessage: String) =
        "08-22 04:44:01.123  1234  1234 $level $tagAndMessage"

    // --- the format Loki actually captures ---------------------------------------------------

    @Test
    fun `parses every level from a threadtime line`() {
        val expected = mapOf(
            'V' to LogLevel.VERBOSE,
            'D' to LogLevel.DEBUG,
            'I' to LogLevel.INFO,
            'W' to LogLevel.WARN,
            'E' to LogLevel.ERROR,
            'F' to LogLevel.FATAL,
            // logcat prints F; the formats that spell android.util.Log.ASSERT out print A.
            'A' to LogLevel.FATAL,
        )
        expected.forEach { (letter, level) ->
            assertEquals(
                "letter $letter",
                level,
                LogLevel.of(threadtime(letter, "SomeTag: a message"))
            )
        }
    }

    @Test
    fun `tolerates the extra uid column that -v uid adds`() {
        assertEquals(
            LogLevel.INFO,
            LogLevel.of("08-22 04:44:01.123  1000  1234  1234 I SomeTag: a message")
        )
    }

    @Test
    fun `tolerates the year prefix that -v year adds`() {
        assertEquals(
            LogLevel.WARN,
            LogLevel.of("2026-08-22 04:44:01.123  1234  1234 W SomeTag: a message")
        )
    }

    // --- regressions ------------------------------------------------------------------------

    @Test
    fun `regression - reads a tag containing brackets`() {
        // Read positionally. The tag-matching fallback rejects `[`, so this used to fail to match
        // and then keep scanning, finding nothing, and report UNKNOWN — which put a DEBUG line
        // outside the Debug+ filter.
        assertEquals(
            LogLevel.DEBUG,
            LogLevel.of(threadtime('D', "VRI[FirstRunActivity]: Handling a message"))
        )
    }

    @Test
    fun `regression - reads a tag containing plus signs`() {
        assertEquals(
            LogLevel.FATAL,
            LogLevel.of(threadtime('F', "libc++abi: terminating"))
        )
    }

    @Test
    fun `regression - does not read a level out of the message`() {
        // The single most damaging form of the old bug: when the prefix did not match, scanning
        // continued into the message, so a WARN line mentioning another level was filed under it.
        // A line here reported as ERROR would show up under an Error+ filter that should hide it.
        assertEquals(
            LogLevel.WARN,
            LogLevel.of(threadtime('W', "!@Boot: retry E/Something: nope"))
        )
        assertEquals(
            LogLevel.INFO,
            LogLevel.of(threadtime('I', "ActivityManager: killed F/native: abort"))
        )
    }

    // --- other formats ----------------------------------------------------------------------

    @Test
    fun `parses the brief format`() {
        assertEquals(LogLevel.DEBUG, LogLevel.of("D/SomeTag( 1234): a message"))
        assertEquals(LogLevel.ERROR, LogLevel.of("E/SomeTag: a message"))
    }

    @Test
    fun `parses a brief tag the tag pattern rejects`() {
        // Falls through to the anchored `X/` check. Safe only because it is anchored at index 0.
        assertEquals(LogLevel.DEBUG, LogLevel.of("D/my tag: a message"))
    }

    // --- lines with no level at all ---------------------------------------------------------

    @Test
    fun `has no level for logcat's buffer markers`() {
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("--------- beginning of main"))
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("--------- beginning of crash"))
    }

    @Test
    fun `has no level for blank lines`() {
        assertEquals(LogLevel.UNKNOWN, LogLevel.of(""))
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("   "))
    }

    @Test
    fun `does not invent a level for ordinary prose`() {
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("Exception in thread main"))
        // A bare capital mid-sentence is not a priority, which is what the tag requirement buys.
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("value of E was 3"))
        // Anchoring the `X/` fallback at index 0 is what keeps a path from reading as one.
        assertEquals(LogLevel.UNKNOWN, LogLevel.of("wrote /data/misc/loki: ok"))
    }

    // --- the ordering the level filter depends on --------------------------------------------

    @Test
    fun `declaration order is severity order`() {
        assertEquals(
            listOf(
                LogLevel.UNKNOWN,
                LogLevel.VERBOSE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.FATAL,
            ),
            LogLevel.entries.toList()
        )
    }

    @Test
    fun `a warn-and-above filter admits exactly warn and above`() {
        // This is the whole implementation of the viewer's level filter, so pin it here rather than
        // let a reordered enum quietly change what every filter means.
        val admitted = LogLevel.entries.filter { it >= LogLevel.WARN }
        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL), admitted)
        // UNKNOWN below VERBOSE is deliberate: a line with no priority falls outside every filter
        // except All, which is what "errors and above" means to a reader.
        assertTrue(LogLevel.UNKNOWN < LogLevel.VERBOSE)
    }
}
