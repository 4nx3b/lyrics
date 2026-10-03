/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.youlyplus

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.youlyplus.models.YouLyPlusLine
import moe.rukamori.archivetune.youlyplus.models.YouLyPlusLyricsResponse
import moe.rukamori.archivetune.youlyplus.models.YouLyPlusSyllable
import java.lang.Character.UnicodeScript
import java.util.Locale

object YouLyPlus {
    private const val LYRICS_PATH = "v2/lyrics/get"

    /** Floor for a merged word token's duration — a zero/negative span would
     *  render as a non-animating word. */
    private const val MIN_WORD_DURATION_MS = 40L

    /** Last-resort duration for a word fragment with neither its own duration
     *  nor a following fragment to measure against. */
    private const val DEFAULT_WORD_DURATION_MS = 600L

    // YouLyPlus mirror list, ordered by observed reliability (probed live
    // 2026-09): `lyricsplus.binimum.org` serves the current KPoE API
    // (v2/lyrics/get, `type:"Word"` with syllabus word timing) and is the
    // only mirror returning 200 today, so it leads. The old comment about
    // it 301-ing to a dead host is obsolete.
    //
    // `lyricsplus.prjktla.my.id` (the project's own domain) currently sits
    // behind a broken Cloudflare origin (HTTP 530 / error 1033) — kept second
    // in case it comes back, matching upstream's KPOE_SERVERS order.
    //
    // `lyricsplus.prjktla.workers.dev` is last: free-tier Cloudflare Worker
    // that usually answers 429, occasionally recovers.
    //
    // `lyricsplus-seven.vercel.app` was REMOVED — Vercel answers 402
    // (deployment disabled / billing), which is permanent, not transient.
    //
    // The v1/ttml/get endpoint was dropped entirely: upstream
    // (ibratabian17/YouLyPlus) removed it and the only working mirror
    // answers 404 there — probing it first only added latency to every
    // lookup. Word-level timing now comes from v2's `syllabus` data via
    // toLyricsText().
    private val baseUrls =
        listOf(
            "https://lyricsplus.binimum.org/",
            "https://lyricsplus.prjktla.my.id/",
            "https://lyricsplus.prjktla.workers.dev/",
        )

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }

            defaultRequest {
                headers.append("Accept", "application/json")
                headers.append("User-Agent", "ArchiveTune")
            }

            expectSuccess = false
        }
    }

    var logger: ((String) -> Unit)? = null

    // Rate-limit backoff: the upstream answers 429 under sustained load and
    // the app fires one lookup per track change - without a cooldown every
    // subsequent track burned a full request round (and the mirrors' shared
    // quota) on guaranteed-429 calls. One 429 parks the provider for a
    // minute; any mirror can trip it since they share the upstream API.
    @Volatile private var rateLimitedUntilMs: Long = 0L

    private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }

        return try {
            val lyrics =
                fetchLyricsAsLrc(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
                    ?: throw IllegalStateException("Lyrics unavailable")
            Result.success(lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) {
        getLyrics(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSeconds,
        ).onSuccess(callback)
    }

    private suspend fun fetchLyricsAsLrc(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
    ): String? =
        fetchFromMirrors(LYRICS_PATH, title, artist, album, durationSeconds) { body ->
            val response = jsonFormat.decodeFromString<YouLyPlusLyricsResponse>(body)
            response.toLyricsText()
        }

    private suspend fun fetchFromMirrors(
        path: String,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        decode: (String) -> String?,
    ): String? {
        val now = System.currentTimeMillis()
        if (now < rateLimitedUntilMs) {
            logger?.invoke(
                "YouLyPlus $path skipped: rate-limited for another " +
                    "${(rateLimitedUntilMs - now) / 1000}s",
            )
            return null
        }
        for (baseUrl in baseUrls) {
            currentCoroutineContext().ensureActive()
            val endpoint = baseUrl + path
            logger?.invoke("Fetching YouLyPlus lyrics from $endpoint")

            try {
                val response =
                    client.get(endpoint) {
                        parameter("title", title)
                        parameter("artist", artist)
                        if (album.isNotBlank()) parameter("album", album)
                        if (durationSeconds > 0) parameter("duration", durationSeconds)
                    }
                val body = response.bodyAsText()
                logger?.invoke("YouLyPlus $path response status: ${response.status}")

                if (response.status.value == 429) {
                    rateLimitedUntilMs =
                        System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    logger?.invoke(
                        "YouLyPlus $path rate-limited - cooling down for " +
                            "${RATE_LIMIT_COOLDOWN_MS / 1000}s",
                    )
                    return null
                }

                if (!response.status.isSuccess()) {
                    continue
                }

                val lyrics = decode(body)
                if (!lyrics.isNullOrBlank()) {
                    logger?.invoke("YouLyPlus $path lyrics length: ${lyrics.length}")
                    return lyrics
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.invoke("YouLyPlus $path fetch error from $baseUrl: ${e.message}")
            }
        }

        return null
    }

    private fun YouLyPlusLyricsResponse.toLyricsText(): String? {
        if (lyrics.isEmpty()) return null

        val timedLines = lyrics.filter { it.time != null }
        if (timedLines.isNotEmpty()) {
            return timedLines
                .joinToString("\n") { line ->
                    buildString {
                        append(formatLrcTimestamp(line.time ?: 0L, bracketed = true))
                        val syllables =
                            line.syllabus.orEmpty()
                                .filter { !it.text.isNullOrBlank() && it.time != null }
                        if (type.equals("Word", ignoreCase = true) && syllables.isNotEmpty()) {
                            // YRC-style word tokens with TRUE durations: the v2
                            // API gives every syllable both a start AND a
                            // duration, but the old enhanced-LRC emission kept
                            // only the starts — so a word's animation always
                            // ended when the NEXT token began (the +600ms
                            // default for line-final words) instead of when the
                            // word is actually sung. `(startMs,durationMs)`
                            // markers carry the real length through to the
                            // app-side parser.
                            //
                            // Fragments of the SAME word are MERGED into one
                            // token ("e"+"nough" -> "enough"): the API routinely
                            // splits one word across several syllabus entries,
                            // and emitting them as separate karaoke words made
                            // each fragment animate for a sliver and read as
                            // two words. A word boundary is where the LINE'S
                            // OWN TEXT has whitespace (authoritative, when the
                            // alignment works) or where the fragment itself
                            // carries a trailing space.
                            val lineEndMs =
                                (line.time ?: 0L) + (line.duration ?: 0L).coerceAtLeast(0L)
                            append(buildYrcWordTokens(
                                syllables,
                                alignedSyllableGaps(line.text.orEmpty(), syllables),
                                lineEndMs,
                            ))
                        } else {
                            append(line.text.orEmpty())
                        }
                    }
                }.takeIf { it.isNotBlank() }
        }

        return lyrics
            .mapNotNull(YouLyPlusLine::text)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }

    /** Builds `word(startMs,durationMs)` tokens for a line, merging
     * same-word syllable fragments. Gaps between tokens come from the
     * authoritative line text when possible, otherwise from the fragments'
     * own trailing whitespace. */
    private fun buildYrcWordTokens(
        syllables: List<YouLyPlusSyllable>,
        alignedGaps: List<String>?,
        lineEndMs: Long,
    ): String = buildString {
        val pieces =
            syllables.mapIndexedNotNull { index, syllable ->
                val time = syllable.time ?: return@mapIndexedNotNull null
                val text = syllable.text.orEmpty()
                YrcPiece(
                    trimmed = text.trim(),
                    hasTrailingSpace = text.lastOrNull()?.isWhitespace() == true,
                    startMs = time,
                    durationMs = syllable.duration ?: 0L,
                    index = index,
                )
            }
        if (pieces.isEmpty()) return@buildString

        val groups = mutableListOf<MutableList<YrcPiece>>()
        pieces.forEach { piece ->
            val lastGroup = groups.lastOrNull()
            val boundaryBefore =
                if (lastGroup?.lastOrNull() == null) {
                    true
                } else {
                    wordBoundaryAfter(lastGroup.last(), alignedGaps)
                }
            if (boundaryBefore || groups.isEmpty()) {
                groups.add(mutableListOf(piece))
            } else {
                lastGroup!!.add(piece)
            }
        }

        groups.forEachIndexed { groupIndex, group ->
            val first = group.first()
            val last = group.last()
            // Effective end of the last fragment: its own duration when the
            // API carries one, else the next fragment's start, else the line
            // end.
            val lastEndMs =
                when {
                    last.durationMs > 0L -> last.startMs + last.durationMs
                    else -> {
                        val nextPieceStart =
                            pieces.firstOrNull { it.index > last.index }?.startMs
                        nextPieceStart?.takeIf { it > last.startMs } ?: lineEndMs.takeIf { it > last.startMs }
                            ?: (last.startMs + DEFAULT_WORD_DURATION_MS)
                    }
                }
            val startMs = first.startMs
            val durationMs = (lastEndMs - startMs).coerceAtLeast(MIN_WORD_DURATION_MS)
            // Trailing space so verbatim word renderers keep the inter-word
            // gap — same contract as the enhanced-LRC path.
            val boundaryAfter =
                if (groupIndex == groups.size - 1) {
                    false
                } else {
                    wordBoundaryAfter(last, alignedGaps)
                }
            append(group.groupJoinText(boundaryAfter))
            append('(')
            append(startMs)
            append(',')
            append(durationMs)
            append(')')
        }
    }

    private fun List<YrcPiece>.groupJoinText(boundaryAfter: Boolean): String =
        joinToString("") { it.trimmed } + if (boundaryAfter) " " else ""

    /** Word boundary after [piece]: when the line-text alignment worked it
     *  is AUTHORITATIVE (the API's own fragment text can disagree — "love "
     *  followed by "," is one word in "love, tonight"); without alignment
     *  the fragment's own trailing space is the best available signal. */
    private fun wordBoundaryAfter(
        piece: YrcPiece,
        alignedGaps: List<String>?,
    ): Boolean {
        val alignedGap = alignedGaps?.getOrNull(piece.index)
        return if (alignedGap != null) alignedGap.isNotEmpty() else piece.hasTrailingSpace
    }

    private data class YrcPiece(
        val trimmed: String,
        val hasTrailingSpace: Boolean,
        val startMs: Long,
        val durationMs: Long,
        val index: Int,
    )

    /**
     * Separator to append after each syllable (before the next one), derived
     * by aligning the concatenated syllable text against the line's own text:
     * a boundary that falls where the line text has whitespace gets a space,
     * one that falls inside a word gets nothing. Returns null when the
     * syllables do not reconstruct the line text (punctuation drift, missing
     * entries) — the caller then falls back to the pair-wise heuristic.
     */
    private fun alignedSyllableGaps(
        lineText: String,
        syllables: List<YouLyPlusSyllable>,
    ): List<String>? {
        if (lineText.isBlank()) return null
        // Squash the authoritative line text, recording the whitespace run
        // that preceded each non-whitespace character.
        val gapsBefore = ArrayList<String>(lineText.length)
        val squashed = StringBuilder(lineText.length)
        var pendingGap = ""
        for (ch in lineText) {
            if (ch.isWhitespace()) {
                pendingGap += ch
            } else {
                gapsBefore.add(pendingGap)
                squashed.append(ch)
                pendingGap = ""
            }
        }
        val sq = squashed.toString()
        val tokens = syllables.map { it.text.orEmpty().filterNot(Char::isWhitespace) }
        val joined = tokens.joinToString("")
        if (joined.isEmpty() || joined.length != sq.length || !sq.equals(joined, ignoreCase = true)) {
            return null
        }
        val separators = ArrayList<String>(syllables.size)
        var consumed = 0
        for (index in tokens.indices) {
            consumed += tokens[index].length
            if (index == tokens.size - 1) {
                separators.add("")
                continue
            }
            val gap = if (consumed < gapsBefore.size) gapsBefore[consumed] else ""
            separators.add(if (gap.isEmpty()) "" else " ")
        }
        return separators
    }

    // The v2 API's syllable tokens carry bare word text with no separator
    // whitespace, so naively concatenating them produced lines like
    // "Helloworldonfire". Rebuild the gaps: one space between two words,
    // nothing around punctuation, and nothing inside scripts that are
    // written without inter-word spaces (Han, kana, Thai, ...).
    internal fun syllableSeparator(
        current: String,
        next: String,
    ): String {
        if (current.isEmpty() || next.isEmpty()) return ""
        val last = current.last()
        val first = next.first()
        if (last.isWhitespace() || first.isWhitespace()) return ""
        if (!first.isLetterOrDigit()) return ""
        if (last in NO_SPACE_AFTER_CHARS) return ""
        if (isSpacelessScript(last) && isSpacelessScript(first)) return ""
        return " "
    }

    private val NO_SPACE_AFTER_CHARS = setOf('(', '[', '{', '«', '‹', '“', '‘')

    private fun isSpacelessScript(ch: Char): Boolean =
        ch.isLetter() &&
            when (Character.UnicodeScript.of(ch.code)) {
                UnicodeScript.HAN,
                UnicodeScript.HIRAGANA,
                UnicodeScript.KATAKANA,
                UnicodeScript.THAI,
                UnicodeScript.LAO,
                UnicodeScript.KHMER,
                UnicodeScript.MYANMAR,
                -> true

                else -> false
            }

    private fun formatLrcTimestamp(
        timeMs: Long,
        bracketed: Boolean,
    ): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        val millis = safeTime % 1000L
        val timestamp = String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
        return if (bracketed) "[$timestamp]" else "<$timestamp>"
    }
}
