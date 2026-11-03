package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

class Anime3rb : AnimeHttpSource() {

    override val name = "Anime3rb"
    override val baseUrl = "https://anime3rb.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val supportsRelatedAnime = false

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun headersBuilder(): Headers.Builder =
        Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "$baseUrl/")
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    // --------------------------------------------------
    // URL NORMALIZATION
    // --------------------------------------------------

    private fun absoluteUrl(value: String?): String {
        var raw = value.orEmpty().trim()

        if (raw.isBlank()) return ""

        raw = Parser.unescapeEntities(raw, false)
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\", "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")

        // إصلاح الروابط التي التصق بها اسم الموقع ببداية رابط آخر.
        raw = raw.replace(
            Regex("""(?i)^(?:https?://)?(?:www\.)?anime3rb\.com(?=https?:)"""),
            ""
        )

        // إصلاح بادئة البروتوكول غير المكتملة.
        raw = raw.replace(
            Regex("""(?i)^https:/([^/])"""),
            "https://$1"
        )

        raw = raw.replace(
            Regex("""(?i)^http:/([^/])"""),
            "http://$1"
        )

        // استخراج الرابط الفعلي إذا كان النص يحتوي على أكثر من رابط.
        val httpsIndex = raw.lastIndexOf("https://")
        val httpIndex = raw.lastIndexOf("http://")
        val lastUrlIndex = maxOf(httpsIndex, httpIndex)

        if (lastUrlIndex > 0) {
            raw = raw.substring(lastUrlIndex)
        }

        return when {
            raw.isBlank() -> ""

            raw.startsWith("https://", true) ||
                raw.startsWith("http://", true) -> raw

            raw.startsWith("//") -> "https:$raw"

            raw.startsWith("/") -> "$baseUrl$raw"

            else -> "$baseUrl/$raw"
        }
    }

    private fun cleanTitle(value: String): String =
        value
            .replace("\\n", " ")
            .replace('\n', ' ')
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun imageUrl(element: Element): String {
        val image = element.selectFirst("img") ?: return ""

        val value = sequenceOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "src"
        )
            .map { image.attr(it) }
            .firstOrNull { it.isNotBlank() }

        return absoluteUrl(value)
    }

    private fun animeFromCard(
        element: Element,
        titleSelector: String
    ): SAnime? {
        val title = cleanTitle(
            element.selectFirst(titleSelector)?.text()
                ?: element.attr("title")
        )

        val rawUrl = element.attr("href")
            .ifBlank {
                element.selectFirst("a[href]")?.attr("href").orEmpty()
            }

        val url = absoluteUrl(rawUrl)

        if (title.isBlank() || url.isBlank()) return null

        return SAnime.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = imageUrl(element).ifBlank { null }
            fetch_type = FetchType.Episodes
            initialized = false
        }
    }

    private fun document(response: Response): Document {
        return Jsoup.parse(
            response.body?.string().orEmpty(),
            response.request.url.toString()
        )
    }

    private fun hasNextPage(doc: Document): Boolean {
        return doc.selectFirst(
            "a[rel=next], " +
                ".pagination a[aria-label*=التالي], " +
                ".pagination a:contains(التالي)"
        ) != null
    }

    // --------------------------------------------------
    // POPULAR ANIME
    // --------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/titles/list?page=$page")
            .headers(headers)
            .get()
            .build()
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = document(response)

        val entries = doc.select("a.simple-title-card")
            .mapNotNull {
                animeFromCard(it, "h4")
            }
            .distinctBy { it.url }

        return AnimesPage(entries, hasNextPage(doc))
    }

    // --------------------------------------------------
    // LATEST UPDATES
    // --------------------------------------------------

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page > 1) {
            return AnimesPage(emptyList(), false)
        }

        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return Request.Builder()
            .url("$baseUrl/")
            .headers(headers)
            .get()
            .build()
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = document(response)

        val entries = doc.select("#videos a.video-card")
            .mapNotNull {
                animeFromCard(it, "h3.title-name")
            }
            .distinctBy { it.url }

        return AnimesPage(entries, false)
    }

    // --------------------------------------------------
    // SEARCH
    // --------------------------------------------------

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList
    ): AnimesPage {

        if (page > 1 || query.isBlank()) {
            return AnimesPage(emptyList(), false)
        }

        return try {
            val homeRequest = Request.Builder()
                .url("$baseUrl/")
                .headers(headers)
                .get()
                .build()

            val homeHtml = client.newCall(homeRequest)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        return AnimesPage(emptyList(), false)
                    }
                    response.body?.string().orEmpty()
                }

            val homeDoc = Jsoup.parse(homeHtml, baseUrl)

            val script = homeDoc.selectFirst("script[data-csrf]")
                ?: return AnimesPage(emptyList(), false)

            val token = script.attr("data-csrf")
            val updateUri = script.attr("data-update-uri")

            val updateUrl = absoluteUrl(
                updateUri.ifBlank { "/livewire/update" }
            )

            val snapshotRaw = homeDoc
                .selectFirst("form[wire:id]")
                ?.attr("wire:snapshot")
                ?: return AnimesPage(emptyList(), false)

            val snapshot = Parser.unescapeEntities(snapshotRaw, true)

            val component = JSONObject()
                .put("snapshot", snapshot)
                .put(
                    "updates",
                    JSONObject().put("query", query)
                )
                .put("calls", JSONArray())

            val payload = JSONObject()
                .put("_token", token)
                .put(
                    "components",
                    JSONArray().put(component)
                )

            val request = Request.Builder()
                .url(updateUrl)
                .headers(
                    headers.newBuilder()
                        .set("Accept", "*/*")
                        .set("X-Livewire", "1")
                        .set("Content-Type", "application/json")
                        .set("Origin", baseUrl)
                        .set("Referer", "$baseUrl/")
                        .build()
                )
                .post(
                    payload.toString().toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                )
                .build()

            val responseText = client.newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        return AnimesPage(emptyList(), false)
                    }
                    response.body?.string().orEmpty()
                }

            val html = JSONObject(responseText)
                .getJSONArray("components")
                .optJSONObject(0)
                ?.getJSONObject("effects")
                ?.optString("html")
                .orEmpty()

            val resultDoc = Jsoup.parse(html, baseUrl)

            val entries = resultDoc.select("a.simple-title-card")
                .mapNotNull {
                    animeFromCard(it, "h4")
                }
                .distinctBy { it.url }

            AnimesPage(entries, false)

        } catch (_: Exception) {
            AnimesPage(emptyList(), false)
        }
    }

    // --------------------------------------------------
    // SEARCH FALLBACK
    // --------------------------------------------------

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList
    ): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        return Request.Builder()
            .url("$baseUrl/titles/list?search=$encodedQuery&page=$page")
            .headers(headers)
            .get()
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = document(response)

        val entries = doc.select("a.simple-title-card")
            .mapNotNull {
                animeFromCard(it, "h4")
            }
            .distinctBy { it.url }

        return AnimesPage(entries, hasNextPage(doc))
    }

    // --------------------------------------------------
    // ANIME DETAILS
    // --------------------------------------------------

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = document(response)

        val rawTitle = doc.selectFirst("h1")
            ?.text()
            .orEmpty()

        val title = rawTitle
            .replace(Regex("الحلقة\\s*\\d+"), "")
            .trim()

        val poster = doc.selectFirst("img[alt*=بوستر]")
            ?.let {
                imageUrl(it.parent() ?: it)
            }
            .orEmpty()

        val description =
            doc.selectFirst("p.synopsis")?.text()
                ?: doc.selectFirst("meta[name=description]")
                    ?.attr("content")
                ?: ""

        val genres = doc.select(".genres a, a.genre, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return SAnime.create().apply {
            url = absoluteUrl(response.request.url.toString())

            this.title = cleanTitle(
                title.ifBlank { rawTitle }
            )

            thumbnail_url = poster.ifBlank { null }
            this.description = description.ifBlank { null }
            genre = genres.joinToString(", ").ifBlank { null }
            status = SAnime.UNKNOWN
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    // --------------------------------------------------
    // EPISODES
    // --------------------------------------------------

    override fun episodeListRequest(anime: SAnime): Request {
        val animeUrl = absoluteUrl(anime.url)

        return Request.Builder()
            .url(animeUrl)
            .headers(
                headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()
            )
            .get()
            .build()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = document(response)

        // الطريقة الأساسية.
        val primary = doc.select(
            ".video-list a[href], " +
                ".episodes-list a[href], " +
                ".episodes a[href], " +
                ".episode-list a[href]"
        )

        // بديل: استخراج أي رابط يشير إلى صفحة حلقة.
        val fallback = doc.select(
            "a[href*=/episode/]"
        )

        val elements = (primary + fallback)
            .distinctBy { absoluteUrl(it.attr("href")) }
            .filter {
                absoluteUrl(it.attr("href"))
                    .contains("/episode/")
            }

        return elements
            .mapNotNull { element ->
                parseEpisodeElement(element)
            }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .asReversed()
    }

    private fun parseEpisodeElement(element: Element): SEpisode {
        val url = absoluteUrl(element.attr("href"))

        val data = element.selectFirst(
            ".video-data, .episode-data, .episode-info"
        )

        val episodeText = data
            ?.children()
            ?.getOrNull(0)
            ?.text()
            .orEmpty()

        val episodeName = data
            ?.children()
            ?.getOrNull(1)
            ?.text()
            .orEmpty()

        val completeText = cleanTitle(
            element.text()
                .ifBlank { element.attr("title") }
        )

        val numberText = Regex(
            """(?:الحلقة|episode|ep)\s*([0-9٠-٩۰-۹]+)""",
            RegexOption.IGNORE_CASE
        )
            .find("$episodeText $completeText")
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        val normalizedNumber = numberText
            .replace('٠', '0')
            .replace('١', '1')
            .replace('٢', '2')
            .replace('٣', '3')
            .replace('٤', '4')
            .replace('٥', '5')
            .replace('٦', '6')
            .replace('٧', '7')
            .replace('٨', '8')
            .replace('٩', '9')
            .replace('۰', '0')
            .replace('۱', '1')
            .replace('۲', '2')
            .replace('۳', '3')
            .replace('۴', '4')
            .replace('۵', '5')
            .replace('۶', '6')
            .replace('۷', '7')
            .replace('۸', '8')
            .replace('۹', '9')

        val number = normalizedNumber
            .toFloatOrNull()

        val finalName = cleanTitle(
            episodeName
                .ifBlank { completeText }
                .ifBlank { episodeText }
                .ifBlank { "حلقة ${numberText.ifBlank { "" }}" }
        )

        return SEpisode.create().apply {
            this.url = url
            name = finalName
            episode_number = number ?: -1f
            preview_url = imageUrl(element).ifBlank { null }
        }
    }

    // --------------------------------------------------
    // HOSTER EXTRACTION
    // --------------------------------------------------

    private fun decodeUrl(value: String): String {
        return Parser.unescapeEntities(value, false)
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\", "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    private fun appendParameter(
        url: String,
        name: String,
        value: String
    ): String {
        if (url.contains("$name=")) return url

        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$name=$value"
    }

    override fun hosterListRequest(episode: SEpisode): Request {
        val episodeUrl = absoluteUrl(episode.url)

        return Request.Builder()
            .url(episodeUrl)
            .headers(
                headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()
            )
            .get()
            .build()
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val html = response.body?.string().orEmpty()

        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parse(
            html,
            response.request.url.toString()
        )

        // المصدر الأساسي الذي يستخدمه الموقع.
        var playerUrl = doc
            .selectFirst("[data-video-source]")
            ?.attr("data-video-source")
            .orEmpty()

        // بدائل لاستخراج رابط المشغل.
        if (playerUrl.isBlank()) {
            playerUrl = doc
                .selectFirst("iframe[src]")
                ?.attr("src")
                .orEmpty()
        }

        if (playerUrl.isBlank()) {
            playerUrl = doc
                .selectFirst("[data-src]")
                ?.attr("data-src")
                .orEmpty()
        }

        if (playerUrl.isBlank()) {
            val patterns = listOf(
                Regex("""data-video-source\s*=\s*["']([^"']+)["']"""),
                Regex("""videoSource\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""playerUrl\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""iframe\s*[:=]\s*["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                val found = pattern.find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()

                if (found.isNotBlank()) {
                    playerUrl = found
                    break
                }
            }
        }

        playerUrl = absoluteUrl(decodeUrl(playerUrl))

        if (
            playerUrl.isBlank() ||
            (!playerUrl.startsWith("https://") &&
                !playerUrl.startsWith("http://"))
        ) {
            return emptyList()
        }

        val cinema = Regex(
            """searchParams\.append\(\s*['"]cinema['"]\s*,\s*(\d+)\s*\)"""
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

        val last = Regex(
            """searchParams\.append\(\s*['"]last['"]\s*,\s*(\d+)\s*\)"""
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

        if (cinema != null) {
            playerUrl = appendParameter(
                playerUrl,
                "cinema",
                cinema
            )
        }

        if (last != null) {
            playerUrl = appendParameter(
                playerUrl,
                "last",
                last
            )
        }

        return listOf(
            Hoster(
                playerUrl,
                "Anime3rb Video"
            )
        )
    }

    // --------------------------------------------------
    // VIDEO EXTRACTION HELPERS
    // --------------------------------------------------

    private fun makeVideo(
        source: String,
        label: String
    ): Video? {
        val sourceUrl = absoluteUrl(decodeUrl(source))

        if (
            sourceUrl.isBlank() ||
            (!sourceUrl.startsWith("https://") &&
                !sourceUrl.startsWith("http://"))
        ) {
            return null
        }

        if (
            sourceUrl.endsWith(".m3u8", true) ||
            sourceUrl.endsWith(".mp4", true) ||
            sourceUrl.contains(".m3u8?") ||
            sourceUrl.contains(".mp4?")
        ) {
            val resolution = Regex("""\d+""")
                .find(label)
                ?.value
                ?.toIntOrNull()

            val videoHeaders = Headers.Builder()
                .add("User-Agent", userAgent)
                .add("Referer", "$baseUrl/")
                .build()

            return Video(
                videoUrl = sourceUrl,
                videoTitle = "Anime3rb $label",
                resolution = resolution,
                headers = videoHeaders,
                preferred = resolution != null && resolution >= 720
            )
        }

        return null
    }

    private fun extractJsonVideoSources(html: String): List<Video> {
        val patterns = listOf(
            Regex("""var\s+video_sources\s*=\s*(\[[\s\S]*?]);"""),
            Regex("""video_sources\s*=\s*(\[[\s\S]*?]);"""),
            Regex(""""video_sources"\s*:\s*(\[[\s\S]*?])""")
        )

        var arrayText = ""

        for (pattern in patterns) {
            arrayText = pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            if (arrayText.isNotBlank()) break
        }

        if (arrayText.isBlank()) return emptyList()

        return try {
            val sources = JSONArray(arrayText)

            buildList {
                for (index in 0 until sources.length()) {
                    val item = sources.optJSONObject(index)
                        ?: continue

                    if (item.optBoolean("premium", false)) {
                        continue
                    }

                    val source = item.optString("src")
                        .ifBlank {
                            item.optString("file")
                        }
                        .ifBlank {
                            item.optString("url")
                        }

                    val label = item.optString("label")
                        .ifBlank {
                            item.optString("quality", "Auto")
                        }

                    val video = makeVideo(source, label)

                    if (video != null) {
                        add(video)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractJavaScriptVideoSources(html: String): List<Video> {
        val patterns = listOf(
            Regex("""var\s+videos\s*=\s*(\[[\s\S]*?]);"""),
            Regex("""videos\s*=\s*(\[[\s\S]*?]);""")
        )

        var arrayContent = ""

        for (pattern in patterns) {
            arrayContent = pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            if (arrayContent.isNotBlank()) break
        }

        if (arrayContent.isBlank()) return emptyList()

        val objects = Regex("""\{[^{}]*}""")
            .findAll(arrayContent)

        return buildList {
            for (objectMatch in objects) {
                val item = objectMatch.value

                val source = Regex(
                    """\b(?:src|file|url)\s*:\s*(['"])(.*?)\1"""
                )
                    .find(item)
                    ?.groupValues
                    ?.getOrNull(2)
                    .orEmpty()

                val label = Regex(
                    """\b(?:label|quality|title)\s*:\s*(['"])(.*?)\1"""
                )
                    .find(item)
                    ?.groupValues
                    ?.getOrNull(2)
                    .orEmpty()
                    .ifBlank { "Auto" }

                val video = makeVideo(source, label)

                if (video != null) {
                    add(video)
                }
            }
        }
    }

    private fun extractHtmlVideoSources(html: String): List<Video> {
        val doc = Jsoup.parse(html)

        val elements = doc.select(
            "video[src], video source[src], source[src]"
        )

        return elements.mapNotNull { element ->
            val source = element.attr("src")
            val label = element.attr("label")
                .ifBlank {
                    element.attr("size")
                }
                .ifBlank {
                    element.attr("res")
                }
                .ifBlank {
                    "Auto"
                }

            makeVideo(source, label)
        }
    }

    private fun extractRegexVideoSources(html: String): List<Video> {
        val patterns = listOf(
            Regex("""(?:file|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8)(?:\?[^"']*)?)["']"""),
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)(?:\?[^"']*)?)["']""")
        )

        return buildList {
            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    val source = match.groupValues
                        .lastOrNull()
                        .orEmpty()

                    val video = makeVideo(source, "Auto")

                    if (video != null) {
                        add(video)
                    }
                }
            }
        }
    }

    // --------------------------------------------------
    // VIDEO EXTRACTION
    // --------------------------------------------------

    override fun videoListRequest(hoster: Hoster): Request {
        val playerUrl = absoluteUrl(hoster.hosterUrl)

        return Request.Builder()
            .url(playerUrl)
            .headers(
                headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .set("Accept", "*/*")
                    .build()
            )
            .get()
            .build()
    }

    override fun videoListParse(
        response: Response,
        hoster: Hoster
    ): List<Video> {
        val html = response.body?.string().orEmpty()

        if (html.isBlank()) return emptyList()

        val results = mutableListOf<Video>()

        results.addAll(
            extractJavaScriptVideoSources(html)
        )

        results.addAll(
            extractJsonVideoSources(html)
        )

        results.addAll(
            extractHtmlVideoSources(html)
        )

        results.addAll(
            extractRegexVideoSources(html)
        )

        return results.distinctBy { it.videoUrl }
    }
}
