/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.youlyplus

import moe.rukamori.archivetune.youlyplus.models.YouLyPlusLine
import moe.rukamori.archivetune.youlyplus.models.YouLyPlusLyricsResponse
import moe.rukamori.archivetune.youlyplus.models.YouLyPlusSyllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 Word-type lyrics must carry BOTH fixes for the user-reported karaoke
 * bugs through the emitted LRC:
 *
 *  1. "words that should be one are still separate" — the API splits one
 *     sung word across several syllabus entries ("e" + "nough"); those
 *     fragments must be MERGED into a single word token.
 *  2. "words that should be animated longer end soon" — the API carries a
 *     real per-fragment duration; the emitted token's duration must be the
 *     word's TRUE length (last fragment start + duration − word start), not
 *     whatever "next token's start" happens to be.
 *
 * Emission is YRC-style `[mm:ss.mmm]word(startMs,durationMs)…` inside a
 * standard LRC line, which is why the expectations below read that form.
 */
class YouLyPlusWordMergeEmissionTest {
    private fun responseOf(lines: List<YouLyPlusLine>) =
        YouLyPlusLyricsResponse(type = "Word", lyrics = lines)

    private fun lyricsText(lines: List<YouLyPlusLine>): String? {
        val method =
            YouLyPlus::class.java.getDeclaredMethod(
                "toLyricsText",
                YouLyPlusLyricsResponse::class.java,
            )
        method.isAccessible = true
        return method.invoke(YouLyPlus, responseOf(lines)) as String?
    }

    @Test
    fun `split word fragments merge into one token`() {
        // "for long enough": "e" + "nough" are one word (no trailing space on
        // "e"); "enough" must appear as ONE token with the full span.
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(
                        time = 31245,
                        duration = 1284,
                        text = "for long enough",
                        syllabus =
                            listOf(
                                YouLyPlusSyllable(time = 31245, duration = 253, text = "for "),
                                YouLyPlusSyllable(time = 31498, duration = 341, text = "long "),
                                YouLyPlusSyllable(time = 31839, duration = 157, text = "e"),
                                YouLyPlusSyllable(time = 31996, duration = 533, text = "nough"),
                            ),
                    ),
                ),
            )!!
        assertTrue(text.startsWith("[00:31.245]"))
        assertEquals(
            "for (31245,253)long (31498,341)enough(31839,690)",
            text.removePrefix("[00:31.245]"),
        )
    }

    @Test
    fun `word duration is the true sung length not the next token start`() {
        // "call" starts at 28077 with duration 883ms; the next line begins at
        // 30189. The old enhanced-LRC emission animated "call" for the 600ms
        // default; the token must now carry the real 883ms.
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(
                        time = 27395,
                        duration = 1565,
                        text = "I been tryna call",
                        syllabus =
                            listOf(
                                YouLyPlusSyllable(time = 27395, duration = 154, text = "I "),
                                YouLyPlusSyllable(time = 27549, duration = 191, text = "been "),
                                YouLyPlusSyllable(time = 27740, duration = 337, text = "tryna "),
                                YouLyPlusSyllable(time = 28077, duration = 883, text = "call"),
                            ),
                    ),
                ),
            )!!
        assertTrue(text.endsWith("call(28077,883)"))
    }

    @Test
    fun `trailing spaces separate words and are preserved on the token`() {
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(
                        time = 32873,
                        duration = 4521,
                        text = "Maybe you can show me how to love, maybe",
                        syllabus =
                            listOf(
                                YouLyPlusSyllable(time = 32873, duration = 473, text = "Maybe "),
                                YouLyPlusSyllable(time = 35729, duration = 760, text = "may"),
                                YouLyPlusSyllable(time = 36489, duration = 905, text = "be"),
                            ),
                ),
                ),
            )!!
        // "may"+"be" merge (no boundary between them); the merged token carries
        // the real end 36489+905=37394 → duration 1665 from 35729.
        assertEquals(
            "[00:32.873]Maybe (32873,473)maybe(35729,1665)",
            text,
        )
    }

    @Test
    fun `line text alignment decides boundaries over fragment spaces`() {
        // Fragment "love " carries a trailing space, but the line text
        // "love, tonight" has no space before the comma — the alignment
        // (authoritative) glues "love" and "," into ONE token that ends
        // 34800..35482 ("," lasts 100ms).
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(
                        time = 34057,
                        duration = 4000,
                        text = "show me love, tonight",
                        syllabus =
                            listOf(
                                YouLyPlusSyllable(time = 34057, duration = 366, text = "show "),
                                YouLyPlusSyllable(time = 34260, duration = 540, text = "me "),
                                YouLyPlusSyllable(time = 34800, duration = 582, text = "love "),
                                YouLyPlusSyllable(time = 35382, duration = 100, text = ", "),
                                YouLyPlusSyllable(time = 35482, duration = 900, text = "tonight"),
                            ),
                    ),
                ),
            )!!
        assertEquals(
            "show (34057,366)me (34260,540)love, (34800,682)tonight(35482,900)",
            text.removePrefix("[00:34.057]"),
        )
    }

    @Test
    fun `missing duration falls back to next fragment start`() {
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(
                        time = 1000,
                        duration = 0,
                        text = "one two",
                        syllabus =
                            listOf(
                                YouLyPlusSyllable(time = 1000, duration = null, text = "one "),
                                YouLyPlusSyllable(time = 1500, duration = 400, text = "two"),
                            ),
                    ),
                ),
            )!!
        // "one" has no duration → ends at the next fragment start (1500).
        assertTrue(text.contains("one (1000,500)"))
    }

    @Test
    fun `type Line keeps plain text emission`() {
        val response =
            YouLyPlusLyricsResponse(
                type = "Line",
                lyrics =
                    listOf(
                        YouLyPlusLine(time = 6506, duration = 2183, text = "Il m'a dit : coucou"),
                    ),
            )
        val method =
            YouLyPlus::class.java.getDeclaredMethod(
                "toLyricsText",
                YouLyPlusLyricsResponse::class.java,
            )
        method.isAccessible = true
        val text = method.invoke(YouLyPlus, response) as String?
        assertEquals("[00:06.506]Il m'a dit : coucou", text)
    }

    @Test
    fun `empty syllabus with word type keeps line text`() {
        val text =
            lyricsText(
                listOf(
                    YouLyPlusLine(time = 1000, duration = 2000, text = "plain line"),
                ),
            )!!
        assertEquals("[00:01.000]plain line", text)
    }

    @Test
    fun `untimed lines fall back to plain text`() {
        val response =
            YouLyPlusLyricsResponse(
                type = "Word",
                lyrics = listOf(YouLyPlusLine(time = null, text = "no timing")),
            )
        val method =
            YouLyPlus::class.java.getDeclaredMethod(
                "toLyricsText",
                YouLyPlusLyricsResponse::class.java,
            )
        method.isAccessible = true
        assertEquals("no timing", method.invoke(YouLyPlus, response) as String?)
    }

    @Test
    fun `no lyrics returns null`() {
        val method =
            YouLyPlus::class.java.getDeclaredMethod(
                "toLyricsText",
                YouLyPlusLyricsResponse::class.java,
            )
        method.isAccessible = true
        assertNull(method.invoke(YouLyPlus, YouLyPlusLyricsResponse(type = "Word")) as String?)
    }
}
