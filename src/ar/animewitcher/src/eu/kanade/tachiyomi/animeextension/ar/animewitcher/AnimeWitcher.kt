package eu.kanade.tachiyomi.animeextension.ar.animewitcher

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import android.app.Application
import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Locale

class AnimeWitcher : AnimeHttpSource(), ConfigurableAnimeSource {
    private val sourcePrefs by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    override fun createHttpServer(): HttpServer = HttpServer()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_SERVER
            title = "السيرفر المفضل"
            entries = arrayOf("تلقائي - الأعلى جودة", "MF", "ST", "MG", "BU", "QI", "AR", "WC", "PD", "KF", "VT")
            entryValues = arrayOf("auto", "MF", "ST", "MG", "BU", "QI", "AR", "WC", "PD", "KF", "VT")
            setDefaultValue("auto")
            summary = "السيرفر المفضل: %s"
        }.also(screen::addPreference)
    }

    private fun orderServers(servers: List<Server>): List<Server> {
        val preferred = sourcePrefs.getString(PREF_PREFERRED_SERVER, "auto") ?: "auto"
        return if (preferred == "auto") {
            servers.sortedWith(compareByDescending<Server> { it.quality.filter(Char::isDigit).toIntOrNull() ?: 0 }.thenBy { it.name })
        } else {
            servers.sortedWith(compareByDescending<Server> { it.name.equals(preferred, true) }
                .thenByDescending { it.quality.filter(Char::isDigit).toIntOrNull() ?: 0 })
        }
    }
    override val name = "AnimeWitcher"
    override val baseUrl = "https://animewitcher.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val supportsRelatedAnime = false

    private val firebaseProject = "animewitcher-1c66d"
    private val firebaseApiKey = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"
    private var algoliaAppId = "D8LH9I7ZL7"
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun requestHeaders(referer: String = "$baseUrl/"): Headers =
        Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", referer)
            .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .build()

    override fun headersBuilder(): Headers.Builder = requestHeaders().newBuilder()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun firestoreUrl(path: String): String =
        "https://firestore.googleapis.com/v1/projects/$firebaseProject/databases/(default)/documents/$path?key=$firebaseApiKey"

    private fun algoliaUrl(index: String): String =
        "https://$algoliaAppId-dsn.algolia.net/1/indexes/$index/query"

    private fun algoliaHeaders(): Headers = Headers.Builder()
        .add("X-Algolia-Application-Id", algoliaAppId)
        .add("X-Algolia-API-Key", algoliaApiKey)
        .add("User-Agent", "Algolia for Android (3.27.0); Android (16)")
        .add("Content-Type", "application/json; charset=UTF-8")
        .build()

    private fun execute(request: Request): String {
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun firestore(path: String): JSONObject {
        val req = Request.Builder().url(firestoreUrl(path))
            .headers(requestHeaders()).get().build()
        return JSONObject(execute(req))
    }

    private fun refreshAlgoliaKeys() {
        runCatching {
            val fields = firestore("Settings/constants").optJSONObject("fields") ?: return
            val settings = fields.optJSONObject("search_settings")
                ?.optJSONObject("mapValue")?.optJSONObject("fields")
            val newId = settings?.optJSONObject("app_id_v3")?.optString("stringValue")
                ?: settings?.optJSONObject("app_id")?.optString("stringValue")
                ?: settings?.optJSONObject("app_id_v2")?.optString("stringValue")
            val newKey = settings?.optJSONObject("api_key")?.optString("stringValue")
            if (!newId.isNullOrBlank() && !newKey.isNullOrBlank()) {
                algoliaAppId = newId
                algoliaApiKey = newKey
            }
        }
    }

    private fun algoliaQuery(index: String, params: String): JSONObject {
        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
        fun send(): JSONObject {
            val request = Request.Builder().url(algoliaUrl(index)).headers(algoliaHeaders())
                .post(body).build()
            return JSONObject(execute(request))
        }
        return try {
            send()
        } catch (e: Exception) {
            refreshAlgoliaKeys()
            send()
        }
    }

    private fun animeFromHit(hit: JSONObject): SAnime? {
        val id = hit.optString("objectID").ifBlank { hit.optString("anime_id") }
        val title = hit.optString("name")
        if (id.isBlank() || title.isBlank()) return null
        val data = encode(hit.toString())
        val url = "$baseUrl/watch/${encode(id)}?data=$data"
        return SAnime.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = hit.optString("poster_uri").ifBlank { null }
            fetch_type = FetchType.Episodes
            initialized = false
            update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
        }
    }

    private fun searchIndex(index: String, query: String, page: Int, attributes: String, count: Int): List<SAnime> {
        val params = "attributesToRetrieve=${encode(attributes)}&hitsPerPage=$count&page=${(page - 1).coerceAtLeast(0)}&query=${encode(query)}"
        val hits = algoliaQuery(index, params).optJSONArray("hits") ?: JSONArray()
        return (0 until hits.length()).mapNotNull { animeFromHit(hits.optJSONObject(it) ?: JSONObject()) }
            .distinctBy { it.url }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val list = searchIndex(
            "series", "", page,
            "objectID,name,poster_uri,type,details,tags,story,english_title,poster,rating,dubbed,path",
            30,
        )
        return AnimesPage(list, list.size >= 30)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val list = searchIndex(
            "recent", "", page,
            "name,date,doc_ref,episode_id,anime_id,episode_name,title,poster_uri,poster_url_aniList,type,tags,thumb_uri,comments_closed,filler,note",
            30,
        )
        return AnimesPage(list, list.size >= 30)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val list = searchIndex(
            "series", query, page,
            "objectID,name,poster_uri,type,details,tags,story,english_title,poster,rating,dubbed,path",
            50,
        )
        return AnimesPage(list, list.size >= 50)
    }

    private fun animeIdFromUrl(url: String): String =
        decode(url.substringAfterLast('/').substringBefore('?'))

    private fun animeDataFromUrl(url: String): JSONObject? = runCatching {
        val raw = url.substringAfter("?data=", "")
        if (raw.isBlank()) null else JSONObject(decode(raw))
    }.getOrNull()

    private fun getAnimeObject(id: String, url: String): JSONObject {
        val byId = runCatching {
            val hits = algoliaQuery("series", "filters=objectID:\"$id\"").optJSONArray("hits")
            hits?.optJSONObject(0)
        }.getOrNull()
        return byId ?: animeDataFromUrl(url) ?: JSONObject().put("objectID", id).put("name", id)
    }

    private data class EpisodeData(
        val id: String,
        val title: String,
        val number: Int,
        val image: String?,
    )

    private fun readString(fields: JSONObject, key: String): String? =
        fields.optJSONObject(key)?.optString("stringValue")?.takeIf { it.isNotBlank() && it != "null" }

    private fun fetchEpisodes(animePath: String): List<EpisodeData> {
        val encoded = encode(animePath).replace("+", "%20")
        val json = firestore("anime_list/$encoded/episodes_summery/summery")
        val values = json.optJSONObject("fields")?.optJSONObject("episodes")
            ?.optJSONObject("arrayValue")?.optJSONArray("values") ?: return emptyList()
        val result = mutableListOf<EpisodeData>()
        for (i in 0 until values.length()) {
            val fields = values.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val id = readString(fields, "doc_id") ?: continue
            val translated = fields.optJSONObject("title_translated")?.optJSONObject("mapValue")
                ?.optJSONObject("fields")
            val title = translated?.optJSONObject("ar")?.optString("stringValue")
                ?.takeIf { it.isNotBlank() } ?: readString(fields, "name") ?: "الحلقة $id"
            val number = fields.optJSONObject("number")?.let {
                (it.optString("integerValue").ifBlank { it.optString("stringValue") }).toIntOrNull()
            } ?: (i + 1)
            result += EpisodeData(id, title, number, readString(fields, "thumb_uri"))
        }
        return result.sortedBy { it.number }
    }

    private fun animePath(anime: JSONObject, id: String): String {
        val ref = anime.optString("doc_ref").ifBlank { anime.optString("path") }
            .ifBlank { "anime_list/$id" }
        return ref.substringAfter("anime_list/").removePrefix("/")
    }

    private fun details(anime: JSONObject, url: String): SAnime {
        val id = anime.optString("objectID").ifBlank { animeIdFromUrl(url) }
        val info = anime.optJSONObject("details") ?: JSONObject()
        val tags = anime.optJSONArray("tags")
        return SAnime.create().apply {
            this.url = url
            title = anime.optString("name").ifBlank { id }
            thumbnail_url = anime.optString("poster_uri")
                .ifBlank { anime.optJSONObject("poster")?.optString("medium").orEmpty() }
                .ifBlank { null }
            description = anime.optString("story").ifBlank {
                anime.optJSONObject("_highlightResult")?.optJSONObject("story")
                    ?.optString("value")?.replace(Regex("</?em>"), "").orEmpty()
            }.ifBlank { null }
            genre = if (tags == null) null else
                (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
            status = if (info.optString("state") == "مكتمل") SAnime.COMPLETED else SAnime.ONGOING
            fetch_type = FetchType.Episodes
            initialized = true
            update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
        }
    }

    private fun episodesFor(anime: JSONObject, id: String): List<SEpisode> {
        val path = animePath(anime, id)
        val poster = anime.optString("poster_uri").ifBlank {
            anime.optJSONObject("poster")?.optString("medium").orEmpty()
        }
        return fetchEpisodes(path).map { ep ->
            SEpisode.create().apply {
                url = "$path|${ep.id}"
                name = ep.title
                episode_number = ep.number.toFloat()
                preview_url = ep.image ?: poster.ifBlank { null }
            }
        }
    }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val id = animeIdFromUrl(anime.url)
        val data = getAnimeObject(id, anime.url)
        val updatedAnime = if (fetchDetails) details(data, anime.url) else anime
        val updatedEpisodes = if (fetchEpisodes) episodesFor(data, id) else episodes
        return SAnimeEpisodeUpdate(updatedAnime, updatedEpisodes)
    }

    private data class Server(
        val name: String,
        val link: String,
        val quality: String,
        val original: String,
        val openBrowser: Boolean,
    )

    private fun parseServerFields(fields: JSONObject): Server? {
        val name = readString(fields, "name") ?: return null
        val link = readString(fields, "link") ?: return null
        return Server(
            name,
            link,
            readString(fields, "quality").orEmpty(),
            readString(fields, "original_link").orEmpty(),
            fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false,
        )
    }

    private fun getServers(animePath: String, episodeId: String): List<Server> {
        val anime = encode(animePath).replace("+", "%20")
        val episode = encode(episodeId).replace("+", "%20")
        val path = "anime_list/$anime/episodes/$episode/servers2/all_servers"
        val primary = runCatching {
            val fields = firestore(path).optJSONObject("fields") ?: JSONObject()
            val values = fields.optJSONObject("servers")?.optJSONObject("arrayValue")?.optJSONArray("values")
                ?: JSONArray()
            (0 until values.length()).mapNotNull {
                val f = values.optJSONObject(it)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return@mapNotNull null
                parseServerFields(f)
            }
        }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return orderServers(primary)

        val fallbackPath = "anime_list/$anime/episodes/$episode/servers"
        return runCatching {
            val docs = firestore(fallbackPath).optJSONArray("documents") ?: JSONArray()
            (0 until docs.length()).mapNotNull {
                val fields = docs.optJSONObject(it)?.optJSONObject("fields") ?: return@mapNotNull null
                val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true
                if (!visible) null else parseServerFields(fields)
            }.sortedByDescending { it.quality.filter(Char::isDigit).toIntOrNull() ?: 0 }
        }.getOrDefault(emptyList()).let(::orderServers)
    }

    private fun getServerWords(serverName: String): List<String?> {
        val path = "Settings/servers/servers/${encode(serverName).replace("+", "%20")}"
        val fields = runCatching { firestore(path).optJSONObject("fields") }.getOrNull() ?: return emptyList()
        return (1..4).map { readString(fields, "word$it") }
    }

    private fun resolveServer(server: Server): String? {
        val link = server.link
        val words = getServerWords(server.name)
        fun page(url: String): String = runCatching {
            val origin = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE).find(url)?.value?.plus("/") ?: "$baseUrl/"
            val req = Request.Builder().url(url).headers(requestHeaders(origin)).get().build()
            execute(req)
        }.getOrDefault("")

        return runCatching {
            when (server.name.uppercase(Locale.ROOT)) {
                "MF", "ST", "MG", "BU" -> link
                "QI" -> server.original.ifBlank { link }
                "AR" -> {
                    val req = Request.Builder().url(link).headers(requestHeaders()).get().build()
                    client.newCall(req).execute().use { it.request.url.toString() }
                }
                "WC" -> {
                    val req = Request.Builder().url(link).headers(requestHeaders()).get().build()
                    client.newCall(req).execute().use { it.header("Location") }
                }
                "PD" -> {
                    val html = page(link)
                    Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                        ?: Regex("""https?://pixeldrain\.com/api/file/[A-Za-z0-9_-]+(?:\?[^"']*)?""")
                            .find(html)?.value ?: link
                }
                "KF" -> {
                    val a = words.getOrNull(0) ?: return null
                    val b = words.getOrNull(1) ?: return null
                    val html = page(link)
                    val token = html.substringAfter(a).substringBefore(b).trim()
                    token.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://$it" }
                }
                "VT" -> {
                    val a = words.getOrNull(0) ?: return null
                    val b = words.getOrNull(1) ?: return null
                    val c = words.getOrNull(2) ?: return null
                    val d = words.getOrNull(3) ?: return null
                    val first = page(link).substringAfter(a).substringBefore(b).replace("\">", "").trim()
                    val second = page("https://vidtube.one$first")
                    second.substringAfter(c).substringBefore(d).takeIf { it.isNotBlank() }?.let { "https://$it" }
                }
                else -> {
                    val a = words.getOrNull(0) ?: return null
                    val b = words.getOrNull(1) ?: return null
                    val html = page(link)
                    html.substringAfter(a).substringBefore(b).replace("amp;", "").trim()
                        .takeIf { it.startsWith("http") }
                }
            }
        }.getOrNull()
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split('|', limit = 2)
        if (parts.size != 2) return emptyList()
        val servers = getServers(parts[0], parts[1])
        return servers.map { server ->
            Hoster(
                hosterUrl = server.link,
                hosterName = "${server.name} ${server.quality}".trim(),
                memo = buildJsonObject {
                    put("animePath", parts[0])
                    put("episodeId", parts[1])
                    put("name", server.name)
                    put("link", server.link)
                    put("quality", server.quality)
                    put("original", server.original)
                    put("openBrowser", server.openBrowser)
                },
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.memo
        val server = Server(
            data["name"]?.jsonPrimitive?.content.orEmpty(),
            data["link"]?.jsonPrimitive?.content.orEmpty(),
            data["quality"]?.jsonPrimitive?.content.orEmpty(),
            data["original"]?.jsonPrimitive?.content.orEmpty(),
            data["openBrowser"]?.jsonPrimitive?.content?.toBoolean() ?: false,
        )
        val resolved = resolveServer(server) ?: return emptyList()
        if (resolved.startsWith("<") || resolved.isBlank()) return emptyList()

        val resolution = server.quality.filter(Char::isDigit).toIntOrNull()
        val headers = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", server.link)
            .build()

        val direct = resolved.substringBefore('?').lowercase(Locale.ROOT)
            .let { it.endsWith(".mp4") || it.endsWith(".m3u8") || it.endsWith(".mpd") }
        if (direct) {
            return listOf(Video(
                videoUrl = resolved,
                videoTitle = "${server.name} ${server.quality}".trim(),
                resolution = resolution,
                headers = headers,
                preferred = true,
            ))
        }

        val html = runCatching {
            val origin = Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE).find(resolved)?.value?.plus("/") ?: "$baseUrl/"
            val req = Request.Builder().url(resolved)
                .headers(headers.newBuilder().set("Referer", origin).build())
                .get().build()
            execute(req)
        }.getOrDefault("")

        val candidates = linkedSetOf<String>()
        fun addCandidate(raw: String?) {
            val value = raw.orEmpty().replace("\\\\/", "/").replace("\\\\u0026", "&")
                .replace("&amp;", "&").replace("\\\\", "").trim().trim('"', '\'', ' ')
            val normalized = when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> ""
            }
            if (normalized.isNotBlank() &&
                Regex("""(?i)\\.(m3u8|mp4|mpd|m4v|webm)(?:$|[?&])""").containsMatchIn(normalized)
            ) candidates += normalized
        }

        Regex("""(?is)<(?:source|video)[^>]+(?:src|data-src)=["']([^"']+)""")
            .findAll(html).forEach { addCandidate(it.groupValues[1]) }
        Regex("""(?is)(?:file|src|source|hls|url|video_url)\\s*[:=]\\s*["']([^"']+)""")
            .findAll(html).forEach { addCandidate(it.groupValues[1]) }
        Regex("""(?i)https?:\\?/\\?/[^"'\\s<>]+?\\.(?:m3u8|mp4|mpd|m4v|webm)(?:\\?[^"'\\s<>]*)?""")
            .findAll(html).forEach { addCandidate(it.value) }
        Regex("""(?i)//[^"'\\s<>]+?\\.(?:m3u8|mp4|mpd|m4v|webm)(?:\\?[^"'\\s<>]*)?""")
            .findAll(html).forEach { addCandidate(it.value) }

        val finalUrl = candidates.firstOrNull() ?: resolved.takeIf {
            Regex("""(?i)\\.(m3u8|mp4|mpd|m4v|webm)(?:$|[?&])""").containsMatchIn(it)
        } ?: return emptyList()

        return listOf(Video(
            videoUrl = finalUrl,
            videoTitle = "${server.name} ${server.quality}".trim(),
            resolution = resolution,
            headers = headers,
            preferred = true,
        ))
    }

    override fun List<Video>.sortVideos(): List<Video> =
        sortedWith(compareByDescending<Video> { it.preferred }.thenByDescending { it.resolution ?: 0 })

    companion object {
        private const val PREF_PREFERRED_SERVER = "animewitcher_preferred_server"
    }
}
