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
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")

        // إصلاح التصاق اسم الموقع ببداية رابط آخر.
        val gluedUrl = Regex(
            """(?i)^(?:https?://)?(?:www\.)?anime3rb\.com(https?://.*)$"""
        ).matchEntire(raw)

        if (gluedUrl != null) {
            raw = gluedUrl.groupValues[1]
        }

        return when {
            raw.isBlank() -> ""

            raw.startsWith("http://", true) ||
                raw.startsWith("https://", true) -> raw

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
    // ANIME DETAILS REQUEST
    // --------------------------------------------------
    // Aniyomi's default request builder may concatenate baseUrl with anime.url.
    // The cards currently store absolute URLs, so build the request explicitly.
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = absoluteUrl(anime.url)
        return Request.Builder()
            .url(url)
            .headers(
                headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .build()
            )
            .get()
            .build()
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

        // The current site uses /episode/<anime>/<number> links.
        // Keep the older selector as a fallback for alternate page layouts.
        val episodeLinks = (
            doc.select("a[href*=/episode/], .video-list a")
            ).distinctBy { it.attr("href") }

        return episodeLinks
            .mapNotNull { element ->
                val href = absoluteUrl(element.attr("href"))
                if (href.isBlank()) return@mapNotNull null

                val videoData = element.selectFirst(".video-data")
                val children = videoData?.children()
                val linkText = element.text().replace(Regex("\\s+"), " ").trim()

                val episodeText = children?.getOrNull(0)?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: Regex("(?i)الحلقة\\s*([0-9٠-٩]+)").find(linkText)?.value.orEmpty()

                val episodeName = children?.getOrNull(1)?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?: linkText
                        .replace(Regex("(?i)الحلقة\\s*[0-9٠-٩]+"), "")
                        .replace(Regex("^\\d{1,2}:\\d{2}\\s*"), "")
                        .trim()

                val numberText = Regex("[0-9٠-٩]+").find(episodeText)?.value
                    ?: Regex("[0-9٠-٩]+").find(linkText)?.value
                val episodeNumber = numberText
                    ?.map { char ->
                        when (char) {
                            in '٠'..'٩' -> char.code - '٠'.code
                            else -> char.digitToIntOrNull() ?: -1
                        }
                    }
                    ?.joinToString("")
                    ?.toFloatOrNull()

                val episodePoster = element.selectFirst("img")
                    ?.let { imageUrl(element) }
                    .orEmpty()

                SEpisode.create().apply {
                    url = href
                    name = episodeName.ifBlank { episodeText.ifBlank { linkText } }
                    episode_number = episodeNumber ?: -1f
                    preview_url = episodePoster.ifBlank { null }
                }
            }
            .distinctBy { it.url }
            .sortedBy { it.episode_number.takeIf { number -> number >= 0f } ?: Float.MAX_VALUE }
    }

    // --------------------------------------------------
    // HOSTER EXTRACTION
    // --------------------------------------------------

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

        // النمط الأصلي المستعمل في CloudStream لاستخراج رابط المشغل.
        val playerPattern = Regex(
            """https:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)player(?:\\/|/)[^"']+"""
        )

        val match = playerPattern.find(html)
            ?: return emptyList()

        val playerUrl = match.value
            .replace("\\", "")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        if (
            !playerUrl.startsWith("https://") &&
            !playerUrl.startsWith("http://")
        ) {
            return emptyList()
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
        // طلب صفحة المشغل بنفس الرؤوس المستخدمة في CloudStream.
        return Request.Builder()
            .url(hoster.hosterUrl)
            .headers(
                headers.newBuilder()
                    .set("Host", "video.vid3rb.com")
                    .set("User-Agent", userAgent)
                    .set("Referer", "$baseUrl/")
                    .set("Sec-Fetch-Dest", "iframe")
                    .set("Sec-Fetch-Site", "cross-site")
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

        // نفس نمط استخراج video_sources من المصدر الأصلي.
        val jsonPattern = Regex(
            """var\s+video_sources\s*=\s*(\[.*?]);""",
            RegexOption.DOT_MATCHES_ALL
        )

        val results = mutableListOf<Video>()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "https://video.vid3rb.com/")
            .build()

        for (match in jsonPattern.findAll(html)) {

            val jsonString = match.groupValues[1]

            val sources = try {
                JSONArray(jsonString)
            } catch (_: Exception) {
                continue
            }

            for (index in 0 until sources.length()) {

                val item = sources.optJSONObject(index)
                    ?: continue

                // تجاهل السيرفرات المدفوعة.
                val premium = item.optBoolean(
                    "premium",
                    item.optString("premium") == "true"
                )

                if (premium) continue

                val source = item.optString("src")
                    .replace("\\", "")
                    .replace("&amp;", "&")
                    .replace("\\u0026", "&")
                    .trim()

                if (
                    source.isBlank() ||
                    (!source.startsWith("https://") &&
                        !source.startsWith("http://"))
                ) {
                    continue
                }

                val label = item.optString("label")
                    .ifBlank { "Unknown" }

                val quality = label
                    .replace(Regex("[^0-9]"), "")
                    .toIntOrNull()

                results.add(
                    Video(
                        videoUrl = source,
                        videoTitle = "Anime3rb $label",
                        resolution = quality,
                        headers = videoHeaders,
                        preferred = quality != null && quality >= 720
                    )
                )
            }
        }

        return results.distinctBy { it.videoUrl }
    }
}
