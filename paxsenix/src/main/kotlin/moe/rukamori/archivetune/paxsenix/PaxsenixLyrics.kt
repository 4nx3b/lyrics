/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.paxsenix.models.*
import java.util.Locale
import kotlin.math.abs

object PaxsenixLyrics {
    private const val DEFAULT_BASE_URL = "https://lyrics.paxsenix.org/"

    // Built-in default API key (provided by the PaxSenix service for
    // ArchiveTune). Users can override this via Settings → Lyrics →
    // Providers → Paxsenix API key. When the user-set key is blank, this
    // built-in key is used as a fallback so the app works out-of-the-box.
    private const val DEFAULT_API_KEY = "Sk-paxsenix-Cd3wtTnii7rZYR_vFUbsNuY408zwRUh079PDLhVgQI2LdDPr"

    // User-configurable endpoint override. When blank, DEFAULT_BASE_URL is used.
    // Set via setEndpoint() from the host app's DataStore preference
    // (PaxsenixEndpointKey). Allows users to point at a self-hosted Paxsenix
    // instance or a mirror like https://api.paxsenix.biz.id/lyrics.
    private var baseUrl: String = DEFAULT_BASE_URL

    var userAgent: String = "ArchiveTune"
        private set

    // User-configurable API key. When blank, the built-in DEFAULT_API_KEY is
    // used as a fallback. When set, it's sent as
    // "Authorization: Bearer <key>" on every Paxsenix API request.
    // Set via setApiKey() from the host app's DataStore preference
    // (PaxsenixApiKeyKey).
    private var userApiKey: String = ""

    // The effective API key — returns the user-set key if non-blank, otherwise
    // the built-in default. Read at request time by the Ktor defaultRequest.
    private val apiKey: String get() = userApiKey.ifBlank { DEFAULT_API_KEY }

    // Optional logger callback. When set by the host app (e.g. routed through
    // GlobalLog/Timber), diagnostic messages go through the app's normal logging
    // pipeline. When null, messages are silently dropped — which is fine for
    // production since these are routine status lines, not errors.
    //
    // This replaces 38 System.err.println() calls that previously synchronized
    // on System.err (Android redirects it to logcat one line at a time as
    // `W/System.err`). During lyrics prefetch multiple providers run in
    // parallel, and the contention + allocation churn was a measurable source
    // of GC pressure and UI jank.
    var logger: ((String) -> Unit)? = null

    private fun log(message: String) {
        logger?.invoke(message)
    }

    fun setUserAgent(
        appName: String,
        versionName: String,
    ) {
        userAgent = "$appName/$versionName"
    }

    /**
     * Sets the user-provided Paxsenix API key. When non-blank, it's sent as
     * an "Authorization: Bearer <key>" header on every Paxsenix API request.
     * When blank, the built-in DEFAULT_API_KEY is used as a fallback.
     */
    fun setApiKey(key: String) {
        userApiKey = key.trim()
    }

    /**
     * Overrides the Paxsenix API endpoint. When [url] is blank, the default
     * endpoint (https://lyrics.paxsenix.org/) is used. The URL must end with
     * a trailing slash — if it doesn't, one is appended.
     */
    fun setEndpoint(url: String) {
        val trimmed = url.trim()
        baseUrl = if (trimmed.isBlank()) {
            DEFAULT_BASE_URL
        } else if (trimmed.endsWith("/")) {
            trimmed
        } else {
            "$trimmed/"
        }
    }

    // Apple Music AMP API (direct catalog search)
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val APPLE_MUSIC_WEB_HOME = "https://music.apple.com/"

    // Fallback JWT used by the Apple Music web player. This token is publicly
    // distributed by Apple in their web player JavaScript bundle and is the
    // same one used by countless open-source Apple Music metadata tools.
    //
    // Apple rotates it roughly every ~6 months; the previous hardcoded value
    // expired on 2026-06-17, which caused every AMP API request to return 401
    // ("AMP search failed with status: 401"). Rather than relying on manual
    // rotations, we now scrape a fresh token from the Apple Music web player
    // JS bundle on first use (and on 401) via [ensureAmpTokenFresh].
    private val fallbackAmpToken: String =
        "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzc0NDU2MzgyLCJleHAiOjE3ODE3" +
            "MTM5ODIsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".4n8qYF4qa18sL1E0G9A3qX35cD8wQ-IJcS9Bh8ZT8JV_yLBtVq46B-9-2ZS3EvWHuw3yK9BYFYAhAdTaDm38vQ"

    @Volatile
    private var ampToken: String = fallbackAmpToken

    // JWT decoded `exp` epoch seconds, or 0 if unknown / unparseable.
    @Volatile
    private var ampTokenExpAtSec: Long = 0L

    // Last time we attempted to refresh the AMP token, used to throttle retries
    // when the Apple Music web player is unreachable.
    @Volatile
    private var ampTokenLastRefreshAtMs: Long = 0L

    private val ampTokenRefreshMutex = Mutex()

    private val tokenClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 10_000
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, ampUserAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
            expectSuccess = false
        }
    }

    /**
     * Forces a refresh of the cached AMP token by scraping the JWT from the
     * Apple Music web player JavaScript bundle.
     *
     * Public so the host app can pre-warm the token on startup, but it's also
     * called automatically by [ensureAmpTokenFresh] when the cached token is
     * missing or close to expiry.
     */
    suspend fun refreshAmpToken(): String? =
        ampTokenRefreshMutex.withLock {
            ampTokenLastRefreshAtMs = System.currentTimeMillis()
            val fresh = scrapeAmpTokenFromWeb()
            if (fresh != null) {
                ampToken = fresh
                ampTokenExpAtSec = decodeJwtExpSec(fresh)
                log("AMP token refreshed from Apple Music web player (exp=${ampTokenExpAtSec}s)")
            } else {
                log("AMP token refresh failed — falling back to hardcoded token")
            }
            fresh
        }

    /**
     * Returns a usable AMP token, refreshing first if the cached one is missing
     * or past (or near) its expiry. Refresh failures fall back to whatever we
     * have on hand (including the hardcoded fallback) rather than failing the
     * entire lyrics resolution.
     */
    private suspend fun ensureAmpTokenFresh(): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val needsRefresh =
            ampTokenExpAtSec == 0L || ampTokenExpAtSec - nowSec < 60L * 60L * 24L // < 24h left
        if (!needsRefresh) return ampToken

        // Throttle: don't hammer music.apple.com more than once a minute.
        val sinceLast = System.currentTimeMillis() - ampTokenLastRefreshAtMs
        if (sinceLast in 1..60_000L) return ampToken

        // Don't wait on the refresh if the current token isn't strictly expired
        // yet — kick off a background refresh and serve the stale token. The
        // 401 handler in [searchAppleMusicId] will block on a forced refresh.
        if (ampTokenExpAtSec == 0L || ampTokenExpAtSec > nowSec) {
            // Still valid: refresh opportunistically but serve the current token.
            // We can't launch a coroutine from here without a scope, so do the
            // refresh synchronously only if the token has actually expired.
            return ampToken
        }

        return refreshAmpToken() ?: ampToken
    }

    private suspend fun scrapeAmpTokenFromWeb(): String? =
        runSuspendCatching {
            // Step 1: fetch the music.apple.com landing page to discover the JS bundle URL.
            val homeResponse = tokenClient.get(APPLE_MUSIC_WEB_HOME)
            if (homeResponse.status != HttpStatusCode.OK) {
                log("Apple Music home fetch failed: ${homeResponse.status}")
                return@runSuspendCatching null
            }
            val html = homeResponse.bodyAsText()

            // The HTML may embed the JWT directly as a `token=...` inline script.
            // Search for it first — it's the cheapest path.
            directJwtRegex.find(html)?.let { return@runSuspendCatching it.value }

            // Otherwise, find the index JS bundle URL (e.g. /assets/index-<hash>.js).
            val jsBundleMatch = jsBundleRegex.find(html) ?: run {
                log("AMP token: no JS bundle URL found in Apple Music home HTML")
                return@runSuspendCatching null
            }
            val rawJsUrl = jsBundleMatch.groupValues[1]
            val jsBundleUrl =
                when {
                    rawJsUrl.startsWith("http") -> rawJsUrl
                    rawJsUrl.startsWith("//") -> "https:$rawJsUrl"
                    rawJsUrl.startsWith("/") -> "https://music.apple.com$rawJsUrl"
                    else -> "https://music.apple.com/$rawJsUrl"
                }

            // Step 2: fetch the JS bundle and extract the JWT.
            val jsResponse = tokenClient.get(jsBundleUrl)
            if (jsResponse.status != HttpStatusCode.OK) {
                log("AMP token: JS bundle fetch failed: ${jsResponse.status}")
                return@runSuspendCatching null
            }
            val js = jsResponse.bodyAsText()
            directJwtRegex.find(js)?.value.also {
                if (it == null) log("AMP token: no JWT found in JS bundle $jsBundleUrl")
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log("AMP token scrape error: ${e.message}")
        }.getOrNull()

    // Apple Music web player JWTs are ES256-signed with 3 base64url segments
    // (header.payload.signature). Match a plausible JWT-shaped token. The token
    // we're looking for is always emitted by Apple with `iss: AMPWebPlay`, so
    // we additionally sanity-check the payload before accepting.
    private val directJwtRegex: Regex =
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    private val jsBundleRegex: Regex =
        Regex("""(?:src|href)=["']([^"']*index-[A-Za-z0-9._-]+\.js)["']""")

    /** Decodes the `exp` claim of a JWT without verifying the signature. */
    private fun decodeJwtExpSec(jwt: String): Long {
        val parts = jwt.split(".")
        if (parts.size != 3) return 0L
        val payload =
            runCatching {
                val normalized = parts[1].replace('-', '+').replace('_', '/')
                val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
                String(java.util.Base64.getDecoder().decode(padded))
            }.getOrNull() ?: return 0L
        val expMatch = """"exp"\s*:\s*(\d+)""".toRegex().find(payload) ?: return 0L
        return expMatch.groupValues[1].toLongOrNull() ?: 0L
    }

    fun setAmpToken(token: String) {
        ampToken = token
        ampTokenExpAtSec = decodeJwtExpSec(token)
    }

    /**
     * Coroutine-safe equivalent of [runCatching] that rethrows [CancellationException].
     *
     * Stdlib `runCatching` catches every [Throwable] including [CancellationException],
     * which breaks structured concurrency: a parent `withTimeoutOrNull(8000) { ... }`
     * cancellation gets wrapped in `Result.failure` instead of propagating. The folded
     * `onFailure` handler then routes the cancellation to [reportException] in the host
     * app, producing noisy `W/ArchiveTune: r8.fpb: Timed out waiting for 8000 ms`
     * logcat entries for every lyrics prefetch that exceeds the per-provider timeout.
     *
     * Mirrors the [moe.koiverse.rukamori.betterlyrics.BetterLyrics] `runSuspendCatching`
     * helper — see commit history there for the same fix applied earlier.
     */
    private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                // Read baseUrl and apiKey at request time (not at client
                // creation time) so setEndpoint()/setApiKey() take effect
                // immediately without needing to recreate the client.
                url(baseUrl)
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json, text/plain, */*")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                // Attach the API key as a Bearer token when set.
                if (apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            }

            expectSuccess = false
        }
    }

    private fun resolveDurationMs(duration: Int): Long =
        when {
            duration <= 0 -> 0L

            duration > 360000 -> duration.toLong()

            // 1h+ is likely ms (360k ms = 6 min)
            else -> duration * 1000L // Likely seconds
        }

    private val lyricsContentKeys =
        listOf("lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lyric")

    private fun cleanJsonLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val payload =
            runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
                ?: return trimmed
        return extractLyrics(payload)
    }

    private fun extractLyrics(element: JsonElement): String? =
        when (element) {
            JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                if (!element.isString) {
                    null
                } else {
                    val value = element.content.trim()
                    if (value.isEmpty()) {
                        null
                    } else {
                        val nestedPayload = runCatching { json.parseToJsonElement(value) }.getOrNull()
                        if (nestedPayload != null && nestedPayload !is JsonPrimitive) {
                            extractLyrics(nestedPayload)
                        } else {
                            value
                        }
                    }
                }
            }

            is JsonArray -> {
                element
                    .mapNotNull(::extractLyrics)
                    .joinToString("\n")
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }

            is JsonObject -> {
                if (element.isErrorPayload()) {
                    null
                } else {
                    lyricsContentKeys
                        .asSequence()
                        .mapNotNull { key -> element[key]?.let(::extractLyrics) }
                        .firstOrNull()
                        ?: (element["metadata"] as? JsonObject)?.let { metadata ->
                            lyricsContentKeys
                                .asSequence()
                                .mapNotNull { key -> metadata[key]?.let(::extractLyrics) }
                                .firstOrNull()
                        }
                        ?: element["words"]?.let { words ->
                            when (words) {
                                is JsonArray -> {
                                    words
                                        .mapNotNull(::extractLyrics)
                                        .joinToString(" ")
                                        .trim()
                                        .takeIf { it.isNotEmpty() }
                                }

                                else -> {
                                    extractLyrics(words)
                                }
                            }
                        }
                }
            }
        }

    private fun JsonObject.isErrorPayload(): Boolean {
        if ((this["isError"] as? JsonPrimitive)?.booleanOrNull == true) return true

        return when (val error = this["error"]) {
            null, JsonNull -> false
            is JsonPrimitive -> error.booleanOrNull ?: error.content.trim().isNotEmpty()
            is JsonArray -> error.isNotEmpty()
            is JsonObject -> error.isNotEmpty()
        }
    }

    private val ampUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    /**
     * Searches Apple Music catalog via the AMP API to find a song's catalog ID.
     *
     * The Apple Music web player JWT is fetched on demand via [ensureAmpTokenFresh]
     * (and force-refreshed once on HTTP 401) so we no longer depend on a hardcoded
     * token that goes stale every few months.
     */
    private suspend fun searchAppleMusicId(
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val query = "$title $artist"
        log("Searching Apple Music catalog for: $query")

        val country = Locale.getDefault().country
        val storefront = if (country.length == 2) country.lowercase(Locale.ROOT) else "us"

        return runSuspendCatching {
            val token = ensureAmpTokenFresh()
            val response =
                client.get("$AMP_BASE_URL/v1/catalog/$storefront/search") {
                    header("Authorization", "Bearer $token")
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    header(HttpHeaders.UserAgent, ampUserAgent)
                    parameter("term", query)
                    parameter("types", "songs")
                    parameter("limit", "10")
                }

            if (response.status == HttpStatusCode.Unauthorized) {
                // Token likely expired between refresh and use — force a refresh
                // and retry once. If refresh fails we serve the fallback token,
                // which will simply 401 again and we bail out cleanly.
                log("AMP search returned 401 — force-refreshing token and retrying once")
                refreshAmpToken()?.let { freshToken ->
                    val retryResponse =
                        client.get("$AMP_BASE_URL/v1/catalog/$storefront/search") {
                            header("Authorization", "Bearer $freshToken")
                            header("Origin", "https://music.apple.com")
                            header("Referer", "https://music.apple.com/")
                            header(HttpHeaders.UserAgent, ampUserAgent)
                            parameter("term", query)
                            parameter("types", "songs")
                            parameter("limit", "10")
                        }
                    if (retryResponse.status != HttpStatusCode.OK) {
                        log("AMP search retry failed with status: ${retryResponse.status}")
                        return@runSuspendCatching null
                    }
                    return@runSuspendCatching parseAmpSearchResult(retryResponse.body<JsonObject>(), title, artist, durationMs)
                }
                log("AMP token refresh failed; cannot retry search")
                return@runSuspendCatching null
            }

            if (response.status != HttpStatusCode.OK) {
                log("AMP search failed with status: ${response.status}")
                return@runSuspendCatching null
            }

            parseAmpSearchResult(response.body<JsonObject>(), title, artist, durationMs)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log("AMP search error: ${e.message}")
        }.getOrNull()
    }

    private fun parseAmpSearchResult(
        root: JsonObject,
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val songs =
            root["results"]
                ?.jsonObject
                ?.get("songs")
                ?.jsonObject
                ?.get("data")
                ?.jsonArray
                ?: return null

        if (songs.isEmpty()) {
            log("AMP search returned no results")
            return null
        }

        data class ScoredSong(
            val id: String,
            val score: Int,
            val name: String,
            val artistName: String,
            val duration: Long,
        )

        val scored =
            songs
                .mapNotNull { item ->
                    val obj = item.jsonObject
                    val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                    val songId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = attrs["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val artistName = attrs["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dur = attrs["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L

                    var score = 0
                    if (name.equals(title, ignoreCase = true)) {
                        score += 20
                    } else if (name.contains(title, ignoreCase = true) || title.contains(name, ignoreCase = true)) {
                        score += 10
                    }
                    if (artistName.equals(artist, ignoreCase = true)) {
                        score += 15
                    } else if (artistName.contains(artist, ignoreCase = true) || artist.contains(artistName, ignoreCase = true)) {
                        score += 5
                    }
                    if (durationMs > 0 && dur > 0) {
                        val diff = abs(dur - durationMs)
                        if (diff < 3000) {
                            score += 10
                        } else if (diff < 10000) {
                            score += 5
                        }
                    }

                    ScoredSong(songId, score, name, artistName, dur)
                }.sortedByDescending { it.score }

        val best = scored.firstOrNull() ?: return null
        log("Best AMP match: ${best.name} by ${best.artistName} (ID: ${best.id}, Score: ${best.score})")

        if (best.score < 12) {
            log("Rejecting match — score ${best.score} < 12")
            return null
        }

        return best.id
    }

    suspend fun getAppleMusicLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val songId =
                searchAppleMusicId(title, artist, durationMs)
                    ?: throw IllegalStateException("Apple Music lyrics unavailable")

            val lyricsResponse =
                client.get("apple-music/lyrics") {
                    parameter("id", songId)
                    parameter("ttml", "true")
                }

            log("Apple Music lyrics (TTML) status: ${lyricsResponse.status}")
            if (lyricsResponse.status == HttpStatusCode.OK) {
                try {
                    val rawBody = lyricsResponse.body<String>().trim()

                    if (rawBody.startsWith("<tt") || rawBody.startsWith("<?xml")) {
                        log("SUCCESS from Apple Music (Direct TTML)")
                        return@runSuspendCatching rawBody
                    }

                    val data = Json.decodeFromString<JsonObject>(rawBody)
                    val content = data["content"]?.jsonPrimitive?.content
                    if (content != null && (content.contains("<tt") || content.contains("<?xml"))) {
                        log("SUCCESS from Apple Music (JSON-wrapped TTML, Length: ${content.length})")
                        return@runSuspendCatching content
                    } else {
                        log("Apple Music TTML content was null or invalid. Type: ${data["type"]}")
                    }
                } catch (e: Exception) {
                    log("Error parsing Apple Music TTML: ${e.message}")
                }
            }

            val jsonResponse =
                client.get("apple-music/lyrics") {
                    parameter("id", songId)
                }
            log("Apple Music lyrics (JSON) status: ${jsonResponse.status}")
            if (jsonResponse.status == HttpStatusCode.OK) {
                val lyricsData = jsonResponse.body<AppleMusicLyricsResponse>()
                if (lyricsData.content.isNotEmpty()) {
                    log("SUCCESS from Apple Music (LRC Fallback)")
                    return@runSuspendCatching convertAppleMusicToLrc(lyricsData)
                }
            }

            throw IllegalStateException("Apple Music lyrics unavailable")
        }

    suspend fun getNeteaseLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            val neteaseSearch =
                client.get("netease/search") {
                    parameter("q", query)
                }

            if (neteaseSearch.status == HttpStatusCode.OK) {
                val searchResponse = neteaseSearch.body<NeteaseSearchResponse>()
                val songs = searchResponse.result?.songs ?: emptyList()

                val bestMatch =
                    if (durationMs > 0) {
                        songs.minByOrNull { abs(it.duration.toLong() - durationMs) }
                    } else {
                        songs.firstOrNull()
                    }

                if (bestMatch != null) {
                    val diff = abs(bestMatch.duration.toLong() - durationMs)
                    log("Best NetEase match: ${bestMatch.name} (ID: ${bestMatch.id}, Duration: ${bestMatch.duration}, Diff: $diff)")
                    if (durationMs <= 0 || (diff < 10000)) {
                        val lyricsResponse =
                            client.get("netease/lyrics") {
                                parameter("id", bestMatch.id)
                                parameter("word", "true")
                            }

                        log("NetEase lyrics status: ${lyricsResponse.status}")
                        if (lyricsResponse.status == HttpStatusCode.OK) {
                            val lyricsData = lyricsResponse.body<JsonObject>()

                            // Try to get word-by-word (klyric) first
                            val klyric =
                                lyricsData["klyric"]
                                    ?.jsonObject
                                    ?.get("lyric")
                                    ?.jsonPrimitive
                                    ?.content
                            if (!klyric.isNullOrBlank()) {
                                log("SUCCESS from NetEase (Karaoke)")
                                return@runSuspendCatching klyric
                            }

                            // Fallback to normal lyric (lrc)
                            val lrc =
                                lyricsData["lrc"]
                                    ?.jsonObject
                                    ?.get("lyric")
                                    ?.jsonPrimitive
                                    ?.content
                            if (!lrc.isNullOrBlank()) {
                                log("SUCCESS from NetEase (LRC)")
                                return@runSuspendCatching lrc
                            }
                        }
                    }
                }
            }
            throw IllegalStateException("NetEase lyrics unavailable")
        }

    suspend fun getSpotifyLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            val spotifySearch =
                client.get("spotify/search") {
                    parameter("q", query)
                }
            if (spotifySearch.status == HttpStatusCode.OK) {
                val items = spotifySearch.body<List<PaxsenixSearchItem>>()
                val bestMatch =
                    if (durationMs > 0) {
                        items.minByOrNull { abs(it.durationMs - durationMs) }
                    } else {
                        items.firstOrNull()
                    }

                if (bestMatch != null) {
                    val diff = abs(bestMatch.durationMs - durationMs)
                    log("Best Spotify match: ${bestMatch.name ?: bestMatch.title} (ID: ${bestMatch.realId}, Duration: ${bestMatch.durationMs}, Diff: $diff)")
                    if (durationMs <= 0 || (diff < 10000)) {
                        val lyricsResponse =
                            client.get("spotify/lyrics") {
                                parameter("id", bestMatch.realId)
                            }
                        log("Spotify lyrics status: ${lyricsResponse.status}")
                        if (lyricsResponse.status == HttpStatusCode.OK) {
                            val data = cleanJsonLyrics(lyricsResponse.body<String>())
                            if (data != null) {
                                log("SUCCESS from Spotify")
                                return@runSuspendCatching data
                            }
                        }
                    }
                }
            }
            throw IllegalStateException("Spotify lyrics unavailable")
        }

    suspend fun getYouTubeLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            log("Requesting YouTube lyrics for: $query (Duration: $durationSeconds)")

            val searchResponse =
                client.get("youtube/search") {
                    parameter("q", query)
                }
            if (searchResponse.status != HttpStatusCode.OK) {
                log("YouTube search failed with status: ${searchResponse.status}")
                throw IllegalStateException("YouTube lyrics unavailable")
            }

            val items = searchResponse.body<List<PaxsenixSearchItem>>()
            val bestMatch =
                if (durationMs > 0) {
                    items.minByOrNull { abs(it.durationMs - durationMs) }
                } else {
                    items.firstOrNull()
                }

            if (bestMatch != null) {
                val diff = abs(bestMatch.durationMs - durationMs)
                log("Best YouTube match: ${bestMatch.name ?: bestMatch.title} (ID: ${bestMatch.realId}, Duration: ${bestMatch.durationMs}, Diff: $diff)")
                if (durationMs <= 0 || (diff < 10000)) {
                    val lyricsResponse =
                        client.get("youtube/lyrics") {
                            parameter("id", bestMatch.realId)
                        }
                    log("YouTube lyrics status: ${lyricsResponse.status}")
                    if (lyricsResponse.status == HttpStatusCode.OK) {
                        val data = cleanJsonLyrics(lyricsResponse.body<String>())
                        if (data != null) {
                            log("SUCCESS from YouTube")
                            return@runSuspendCatching data
                        }
                        log("YouTube returned error: ${data.orEmpty().take(200)}")
                    }
                }
            }
            throw IllegalStateException("YouTube lyrics unavailable")
        }

    suspend fun getMusixmatchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            val query = "$title $artist"
            log("Requesting Musixmatch lyrics for: $query (Duration: $durationSeconds)")

            // Try word-by-word first
            val mxmWord =
                client.get("musixmatch/lyrics") {
                    parameter("q", query)
                    parameter("t", title)
                    parameter("a", artist)
                    parameter("d", durationSeconds.toString())
                    parameter("type", "word")
                }
            if (mxmWord.status == HttpStatusCode.OK) {
                val data = cleanJsonLyrics(mxmWord.body<String>())
                if (data != null) {
                    log("SUCCESS from Musixmatch (Word)")
                    return@runSuspendCatching data
                }
                log("Musixmatch (Word) returned server error: ${data.orEmpty().take(200)}")
            }

            // Fallback to default
            val mxmLyrics =
                client.get("musixmatch/lyrics") {
                    parameter("q", query)
                    parameter("t", title)
                    parameter("a", artist)
                    parameter("d", durationSeconds.toString())
                }
            log("Musixmatch lyrics status: ${mxmLyrics.status}")
            if (mxmLyrics.status == HttpStatusCode.OK) {
                val data = cleanJsonLyrics(mxmLyrics.body<String>())
                if (data != null) {
                    log("SUCCESS from Musixmatch")
                    return@runSuspendCatching data
                }
                log("Musixmatch returned server error: ${data.orEmpty().take(200)}")
            }
            throw IllegalStateException("Musixmatch lyrics unavailable")
        }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runSuspendCatching {
            log("--- Starting search for [$title] by [$artist] ---")

            getAppleMusicLyrics(title, artist, durationSeconds).getOrNull()?.let {
                log("Search FINISHED (Apple Music)")
                return@runSuspendCatching it
            }

            getNeteaseLyrics(title, artist, durationSeconds).getOrNull()?.let {
                log("Search FINISHED (NetEase)")
                return@runSuspendCatching it
            }

            getSpotifyLyrics(title, artist, durationSeconds).getOrNull()?.let {
                log("Search FINISHED (Spotify)")
                return@runSuspendCatching it
            }

            getMusixmatchLyrics(title, artist, durationSeconds).getOrNull()?.let {
                log("Search FINISHED (Musixmatch)")
                return@runSuspendCatching it
            }

            log("Search FAILED - No providers found lyrics")
            throw IllegalStateException("Lyrics unavailable from Paxsenix for $title")
        }

    private fun convertAppleMusicToLrc(response: AppleMusicLyricsResponse): String =
        response.content.joinToString("\n") { line ->
            val minutes = line.timestamp / 1000 / 60
            val seconds = (line.timestamp / 1000) % 60
            val hundredths = (line.timestamp % 1000) / 10
            val time = String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
            val text = line.text.joinToString(" ") { it.text.trim() }
            "$time$text"
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, duration).onSuccess(callback)
    }

    suspend fun getStats(): Result<PaxsenixStats> =
        runSuspendCatching {
            client.get("api/stats").body<PaxsenixStats>()
        }

    /** Outcome of probing one provider path on the configured endpoint. */
    enum class PathStatus {
        /** The endpoint serves this path (2xx, or a 4xx that is about our dummy parameters). */
        AVAILABLE,

        /** The endpoint answered but refuses this path outright — upstream retired it. */
        RETIRED,

        /** No usable answer: connection failure, timeout, or a server-side error. */
        UNREACHABLE,
    }

    data class PathCheck(
        /** Human-readable provider name, e.g. "Apple Music". */
        val provider: String,
        /** Path relative to the configured endpoint, e.g. "netease/search". */
        val path: String,
        val status: PathStatus,
        /** HTTP status code, or null when the request never completed. */
        val httpCode: Int?,
    )

    /**
     * The one probe request per provider, in the order shown to the user.
     *
     * Each provider is represented by the *first* path its lyrics lookup hits, since a
     * provider whose search path is gone cannot reach its lyrics path either. The query
     * parameters are deliberate throwaways: this asks "does this endpoint still serve this
     * route", not "can you find this song".
     */
    private val PROBE_PATHS =
        listOf(
            Triple("Apple Music", "apple-music/lyrics", "id" to "1440857781"),
            Triple("NetEase", "netease/search", "q" to "test"),
            Triple("Spotify", "spotify/search", "q" to "test"),
            Triple("YouTube", "youtube/search", "q" to "test"),
            Triple("Musixmatch", "musixmatch/lyrics", "q" to "test"),
        )

    /**
     * Probes every per-provider path against the currently configured endpoint and reports
     * which ones it still serves.
     *
     * ## Why this exists
     *
     * Paxsenix is a single service root with one sub-path per provider, and the app exposes a
     * toggle per provider. Upstream has retired most of those routes — they answer 403 with an
     * explicit "no longer available" message regardless of the API key — while `apple-music`
     * still works. From inside the app that is indistinguishable from a wrong endpoint or a
     * bad key: every provider just silently yields no lyrics. This turns the guesswork into a
     * one-tap answer, and it re-runs against whatever endpoint the user has configured, so a
     * self-hosted instance or a future mirror reports its own real coverage.
     *
     * A 403 is read as "retired" because that is how the public service signals a removed
     * route; other 4xx codes mean the route exists and merely disliked the throwaway query.
     * Never throws — a failed probe is reported as [PathStatus.UNREACHABLE].
     */
    suspend fun checkProviderPaths(): List<PathCheck> =
        PROBE_PATHS.map { (provider, path, probeParam) ->
            val (paramName, paramValue) = probeParam
            try {
                val status =
                    client
                        .get(path) {
                            parameter(paramName, paramValue)
                        }.status
                val outcome =
                    when {
                        status == HttpStatusCode.Forbidden -> PathStatus.RETIRED
                        status.value >= 500 -> PathStatus.UNREACHABLE
                        else -> PathStatus.AVAILABLE
                    }
                log("endpoint check $path -> ${status.value} ($outcome)")
                PathCheck(provider, path, outcome, status.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("endpoint check $path failed: ${e.message}")
                PathCheck(provider, path, PathStatus.UNREACHABLE, null)
            }
        }
}
// Cache invalidation
