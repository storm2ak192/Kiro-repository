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

    private fun absoluteUrl(value: String?): String {
        val raw = value.orEmpty().trim()

        if (raw.isBlank()) return ""

        return when {
            raw.startsWith("https://") ||
                raw.startsWith("http://") -> raw

            raw.startsWith("//") -> "https:$raw"

            raw.startsWith("/") -> baseUrl + raw

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
            element.selectFirst(titleSelector)?.text().orEmpty()
        )

        val url = absoluteUrl(element.attr("href"))

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

        return AnimesPage(
            entries,
            hasNextPage(doc)
        )
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

            val snapshot = Parser.unescapeEntities(
                snapshotRaw,
                true
            )

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

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

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

        return AnimesPage(
            entries,
            hasNextPage(doc)
        )
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

        val genres = doc.select(
            ".genres a, a.genre, .genre a"
        )
            .map {
                it.text().trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()

        return SAnime.create().apply {
            url = response.request.url.toString()

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
        return Request.Builder()
            .url(anime.url)
            .headers(headers)
            .get()
            .build()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {

        val doc = document(response)

        return doc.select(".video-list a")
            .asReversed()
            .mapNotNull { element ->

                val episode = parseEpisodeElement(element)

                episode.url.takeIf {
                    it.isNotBlank()
                }?.let {
                    episode
                }
            }
    }

    private fun parseEpisodeElement(
        element: Element
    ): SEpisode {

        val url = absoluteUrl(
            element.attr("href")
        )

        val data = element.selectFirst(".video-data")

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

        val number = episodeText
            .filter { it.isDigit() }
            .toFloatOrNull()

        return SEpisode.create().apply {
            this.url = url

            name = cleanTitle(
                episodeName
                    .ifBlank { episodeText }
                    .ifBlank { "حلقة" }
            )

            episode_number = number ?: -1f

            preview_url = imageUrl(element).ifBlank { null }
        }
    }

    // --------------------------------------------------
    // HOSTER EXTRACTION
    // --------------------------------------------------

    private fun decodeUrl(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\", "")
            .trim()
    }

    private fun appendParameter(
        url: String,
        name: String,
        value: String
    ): String {

        val separator = if (url.contains("?")) "&" else "?"

        return "$url$separator$name=$value"
    }

    override fun hosterListRequest(episode: SEpisode): Request {
        return Request.Builder()
            .url(episode.url)
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

        // الطريقة الأساسية:
        // استخراج رابط المشغل من data-video-source.

        val dataSource = doc
            .selectFirst("[data-video-source]")
            ?.attr("data-video-source")
            .orEmpty()

        // طريقة بديلة في حالة وجود الرابط داخل HTML مباشرة.

        val fallbackSource = Regex(
            """data-video-source\s*=\s*["']([^"']+)["']"""
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        var playerUrl = decodeUrl(
            dataSource.ifBlank { fallbackSource }
        )

        if (playerUrl.isBlank()) {
            return emptyList()
        }

        playerUrl = absoluteUrl(playerUrl)

        // استخراج معاملات المشغل من JavaScript.

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

        // إضافة المعاملات عند وجودها فقط.

        if (
            cinema != null &&
            !playerUrl.contains("cinema=")
        ) {
            playerUrl = appendParameter(
                playerUrl,
                "cinema",
                cinema
            )
        }

        if (
            last != null &&
            !playerUrl.contains("last=")
        ) {
            playerUrl = appendParameter(
                playerUrl,
                "last",
                last
            )
        }

        if (!playerUrl.contains("next-image=")) {
            playerUrl = appendParameter(
                playerUrl,
                "next-image",
                "undefined"
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
    // VIDEO EXTRACTION
    // --------------------------------------------------

    override fun videoListRequest(hoster: Hoster): Request {

        return Request.Builder()
            .url(hoster.hosterUrl)
            .headers(
                headers.newBuilder()
                    .set("Referer", hoster.hosterUrl)
                    .build()
            )
            .get()
            .build()
    }

    private fun extractJsonVideoSources(
        html: String
    ): List<Video> {

        val match = Regex(
            """var\s+video_sources\s*=\s*(\[[\s\S]*?]);"""
        ).find(html) ?: return emptyList()

        return try {

            val sources = JSONArray(
                match.groupValues[1]
            )

            buildList {

                for (index in 0 until sources.length()) {

                    val item = sources.optJSONObject(index)
                        ?: continue

                    val sourceUrl = decodeUrl(
                        item.optString("src")
                    )

                    if (
                        sourceUrl.isBlank() ||
                        item.optBoolean("premium", false)
                    ) {
                        continue
                    }

                    val label = item.optString(
                        "label",
                        "Auto"
                    )

                    val resolution = Regex("""\d+""")
                        .find(label)
                        ?.value
                        ?.toIntOrNull()

                    val videoHeaders = Headers.Builder()
                        .add("User-Agent", userAgent)
                        .add("Referer", "$baseUrl/")
                        .build()

                    add(
                        Video(
                            videoUrl = sourceUrl,
                            videoTitle = "Anime3rb $label",
                            resolution = resolution,
                            headers = videoHeaders,
                            preferred = resolution != null &&
                                resolution >= 720
                        )
                    )
                }
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractJavaScriptVideoSources(
        html: String
    ): List<Video> {

        val match = Regex(
            """var\s+videos\s*=\s*(\[[\s\S]*?]);"""
        ).find(html) ?: return emptyList()

        val arrayContent = match.groupValues[1]

        // استخراج كل عنصر من عناصر المصفوفة.

        val objects = Regex(
            """\{[^{}]*}"""
        ).findAll(arrayContent)

        return buildList {

            for (objectMatch in objects) {

                val item = objectMatch.value

                // استخراج رابط الفيديو.

                val source = Regex(
                    """\bsrc\s*:\s*(['"])(.*?)\1"""
                )
                    .find(item)
                    ?.groupValues
                    ?.getOrNull(2)
                    .orEmpty()

                // استخراج الجودة.

                val label = Regex(
                    """\blabel\s*:\s*(['"])(.*?)\1"""
                )
                    .find(item)
                    ?.groupValues
                    ?.getOrNull(2)
                    .orEmpty()

                val sourceUrl = decodeUrl(source)

                if (sourceUrl.isBlank()) {
                    continue
                }

                val quality = label.ifBlank { "Auto" }

                val resolution = Regex("""\d+""")
                    .find(quality)
                    ?.value
                    ?.toIntOrNull()

                val videoHeaders = Headers.Builder()
                    .add("User-Agent", userAgent)
                    .add("Referer", "$baseUrl/")
                    .build()

                add(
                    Video(
                        videoUrl = sourceUrl,
                        videoTitle = "Anime3rb $quality",
                        resolution = resolution,
                        headers = videoHeaders,
                        preferred = resolution != null &&
                            resolution >= 720
                    )
                )
            }
        }
    }

    override fun videoListParse(
        response: Response,
        hoster: Hoster
    ): List<Video> {

        val html = response.body?.string().orEmpty()

        if (html.isBlank()) {
            return emptyList()
        }

        // تجربة صيغة videos أولاً.

        val javascriptVideos = extractJavaScriptVideoSources(html)

        if (javascriptVideos.isNotEmpty()) {
            return javascriptVideos.distinctBy {
                it.videoUrl
            }
        }

        // تجربة صيغة video_sources كبديل.

        val jsonVideos = extractJsonVideoSources(html)

        if (jsonVideos.isNotEmpty()) {
            return jsonVideos.distinctBy {
                it.videoUrl
            }
        }

        return emptyList()
    }
}
