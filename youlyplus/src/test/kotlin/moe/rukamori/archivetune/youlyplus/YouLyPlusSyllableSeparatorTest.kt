/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.youlyplus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the enhanced-LRC syllable gap reconstruction: the v2
 * API returns bare syllable tokens, and the generated LRC must re-insert the
 * inter-word spaces that word-level renderers display.
 */
class YouLyPlusSyllableSeparatorTest {
    @Test
    fun `two latin words get one space`() {
        assertEquals(" ", YouLyPlus.syllableSeparator("Hello", "world"))
    }

    @Test
    fun `existing whitespace is not duplicated`() {
        assertEquals("", YouLyPlus.syllableSeparator("Hello ", "world"))
        assertEquals("", YouLyPlus.syllableSeparator("Hello", " world"))
    }

    @Test
    fun `no space before punctuation`() {
        assertEquals("", YouLyPlus.syllableSeparator("Hello", ","))
        assertEquals("", YouLyPlus.syllableSeparator("Hello", "!!"))
        assertEquals("", YouLyPlus.syllableSeparator("hello", "?"))
    }

    @Test
    fun `no space after opening punctuation`() {
        assertEquals("", YouLyPlus.syllableSeparator("(", "hello"))
        assertEquals("", YouLyPlus.syllableSeparator("\u201C", "hello"))
    }

    @Test
    fun `cjk to cjk gets no space`() {
        assertEquals("", YouLyPlus.syllableSeparator("你好", "世界"))
        assertEquals("", YouLyPlus.syllableSeparator("こん", "にちは"))
    }

    @Test
    fun `korean gets spaces`() {
        assertEquals(" ", YouLyPlus.syllableSeparator("안녕", "세계"))
    }

    @Test
    fun `cjk to latin boundary gets a space`() {
        assertEquals(" ", YouLyPlus.syllableSeparator("君と", "dance"))
        assertEquals(" ", YouLyPlus.syllableSeparator("dance", "する"))
    }

    @Test
    fun `digits behave like letters`() {
        assertEquals(" ", YouLyPlus.syllableSeparator("Chapter", "1"))
        assertEquals(" ", YouLyPlus.syllableSeparator("1", "more"))
    }

    @Test
    fun `empty input gets no separator`() {
        assertEquals("", YouLyPlus.syllableSeparator("", "world"))
        assertEquals("", YouLyPlus.syllableSeparator("Hello", ""))
    }

    @Test
    fun `apostrophe words keep natural spacing`() {
        // "I'm" arrives as "I" + "'m" — the apostrophe must not gain a space.
        assertEquals("", YouLyPlus.syllableSeparator("I", "'m"))
        // Contractions split the other way also stay glued.
        assertEquals("", YouLyPlus.syllableSeparator("don", "'t"))
    }

    @Test
    fun `thai gets no inter-word space`() {
        assertEquals("", YouLyPlus.syllableSeparator("สวัส", "ดี"))
    }
}
