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
                        val syllables = line.syllabus.orEmpty().filter { !it.text.isNullOrBlank() && it.time != null }
                        if (type.equals("Word", ignoreCase = true) && syllables.isNotEmpty()) {
                            // Word boundaries come from the LINE'S OWN TEXT,
                            // not from a heuristic over the syllable tokens:
                            // the API routinely splits one word across several
                            // syllabus entries ("to" + "night"), and the old
                            // pair-wise heuristic inserted a space between the
                            // pieces — printing one word as two. Aligning the
                            // concatenated syllable text against the line text
                            // means a separator appears exactly where the line
                            // itself has whitespace, and syllables of the same
                            // word are glued.
                            val separators = alignedSyllableGaps(line.text.orEmpty(), syllables)
                            syllables.forEachIndexed { index, syllable ->
                                append(formatLrcTimestamp(syllable.time ?: 0L, bracketed = false))
                                append(syllable.text.orEmpty())
                                if (index < syllables.size - 1) {
                                    val nextText = syllables.getOrNull(index + 1)?.text.orEmpty()
                                    append(
                                        separators?.getOrNull(index)?.takeIf { it.isNotEmpty() }
                                            ?: syllableSeparator(syllable.text.orEmpty(), nextText),
                                    )
                                }
                            }
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
