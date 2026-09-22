/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.betterlyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word-boundary whitespace regression tests.
 *
 * TTML sources carry the separator space between words in different places;
 * the parser must preserve it in the word text so verbatim word-level
 * renderers keep their gaps.
 */
class TTMLParserWordSpacingTest {
    // NOTE: built by concatenation, not a trimIndent raw string — the
    // interpolated lineBody spans multiple lines, which would drag the
    // minimal indent to zero and leave the XML prolog indented (a fatal XML
    // error that silently fails the parse).
    private fun ttml(lineBody: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<tt xmlns=\"http://www.w3.org/ns/ttml\">\n" +
            "  <body>\n" +
            "    <div>\n" +
            "      <p begin=\"00:00:01.000\" end=\"00:00:05.000\">$lineBody</p>\n" +
            "    </div>\n" +
            "  </body>\n" +
            "</tt>"

    private fun joinedWordText(ttml: String): String {
        val lines = TTMLParser.parseTTML(ttml)
        assertTrue("parser returned no lines", lines.isNotEmpty())
        return lines.first().words.joinToString("") { it.text }
    }

    private fun wordTexts(ttml: String): List<String> {
        val lines = TTMLParser.parseTTML(ttml)
        assertTrue("parser returned no lines", lines.isNotEmpty())
        return lines.first().words.map { it.text }
    }

    @Test
    fun `space inside span text is preserved`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello </span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `space as text node between spans is preserved`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello</span> " +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `leading space folds onto previous word`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello</span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\"> world</span>",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `whitespace-only timed span acts as separator`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello</span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:02.100\"> </span>" +
                    "<span begin=\"00:00:02.100\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `pretty printed latin spans recover separator spaces`() {
        val ttml =
            ttml(
                "\n  <span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello</span>\n" +
                    "  <span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>\n",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `pretty printed cjk spans stay spaceless`() {
        val ttml =
            ttml(
                "\n  <span begin=\"00:00:01.000\" end=\"00:00:02.000\">こんにちは</span>\n" +
                    "  <span begin=\"00:00:02.000\" end=\"00:00:03.000\">世界</span>\n",
            )
        assertEquals("こんにちは世界", joinedWordText(ttml))
    }

    @Test
    fun `compact cjk spans stay spaceless`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">你好</span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">世界</span>",
            )
        assertEquals("你好世界", joinedWordText(ttml))
    }

    @Test
    fun `double space collapses to single separator`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello  </span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Hello world", joinedWordText(ttml))
    }

    @Test
    fun `no separator produces no invented space`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello</span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Helloworld", joinedWordText(ttml))
    }

    @Test
    fun `trailing space on final word is dropped`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello </span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world </span>",
            )
        val words = wordTexts(ttml)
        assertEquals(listOf("Hello ", "world"), words)
    }

    @Test
    fun `line text still carries original spacing`() {
        val ttml =
            ttml(
                "<span begin=\"00:00:01.000\" end=\"00:00:02.000\">Hello </span>" +
                    "<span begin=\"00:00:02.000\" end=\"00:00:03.000\">world</span>",
            )
        assertEquals("Hello world", TTMLParser.parseTTML(ttml).first().text)
    }

    @Test
    fun `korean gets separator from pretty printed layout`() {
        val ttml =
            ttml(
                "\n  <span begin=\"00:00:01.000\" end=\"00:00:02.000\">안녕</span>\n" +
                    "  <span begin=\"00:00:02.000\" end=\"00:00:03.000\">세계</span>\n",
            )
        assertEquals("안녕 세계", joinedWordText(ttml))
    }
}
