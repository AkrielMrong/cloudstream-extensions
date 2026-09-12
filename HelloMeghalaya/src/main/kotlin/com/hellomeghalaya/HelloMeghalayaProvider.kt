package com.hellomeghalaya

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

class HelloMeghalayaProvider : MainAPI() {
    override var mainUrl = "https://www.hellomeghalaya.in"
    override var name = "Hello Meghalaya"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    override val mainPage = mainPageOf(
        "catalog:65d4a8652d6455ca1cb980ae" to "Movies",
        "catalog:6a66f72eeb17e8fab339b3f4" to "Web Series",
        "catalog:65f156a22d64556b4368f89f" to "Short Films",
        "catalog:67f52b93c8e10da45b9cc9e3" to "Classic Movies",
        "catalog:65d4a92f2d6455ca1cb980b2" to "Music Videos",
        "catalog:67f52bbbc8e10da45b9cc9e4" to "Classic Songs",
        "catalog:65f15fd62d64556b4368f914" to "Lifestyle & Infotainment",
        "catalog:65e6d4312d64556176743c30" to "Shorts",
        "coming-soon" to "Coming Soon"
    )

    companion object {
        private const val API_URL = "https://ottapi.hellomeghalaya.in"
        private const val AUTH_TOKEN = "xFGLeq8pLfWPizFxmJXy"
        private const val SECRET_KEY = "5c9239f7f3648a093ce4"
        private var cachedSession: String? = null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (request.data == "coming-soon") {
            val url = "$API_URL/catalog_lists/coming-soon?auth_token=$AUTH_TOKEN"
            val text = app.get(url).text
            val res = tryParseJson<ComingSoonResponse>(text)
            res?.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } else if (request.data.startsWith("catalog:")) {
            val catalogId = request.data.removePrefix("catalog:")
            val pageIdx = (page - 1).coerceAtLeast(0)
            val url = "$API_URL/catalogs/$catalogId/items.gzip?page=$pageIdx&page_size=20&region=IN&auth_token=$AUTH_TOKEN"
            val text = app.get(url).text
            val res = tryParseJson<CatalogItemsResponse>(text)
            res?.data?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } else {
            emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$API_URL/search3?q=$encodedQuery&region=IN&auth_token=$AUTH_TOKEN"
        val text = app.get(url).text
        val res = tryParseJson<Search3Response>(text)
        val resultList = mutableListOf<SearchResponse>()
        res?.data?.items?.values?.forEach { category ->
            category.items?.mapNotNull { it.toSearchResponse() }?.let {
                resultList.addAll(it)
            }
        }
        return resultList.distinctBy { it.url }
    }

    private fun ItemDto.toSearchResponse(): SearchResponse? {
        val itemTitle = this.title ?: return null
        val cid = this.contentId ?: this.id ?: return null
        val catId = this.catalogId ?: this.catalogObject?.id ?: ""
        val itemTheme = this.theme ?: "movie"
        val fid = this.friendlyId ?: ""

        val detailUrl = "$mainUrl/detail?cat=$catId&id=$cid&theme=$itemTheme&friendly_id=$fid"
        val poster = extractPoster(this.thumbnails)

        val isSeries = itemTheme == "show" || itemTheme == "web-series" || itemTheme == "show_episode"

        return if (isSeries) {
            newTvSeriesSearchResponse(itemTitle, detailUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(itemTitle, detailUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = java.net.URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else "")
        } ?: emptyMap()

        val catId = queryParams["cat"].orEmpty()
        val contentId = queryParams["id"].orEmpty()
        val theme = queryParams["theme"].orEmpty()
        val friendlyId = queryParams["friendly_id"].orEmpty()

        if (catId.isBlank() || contentId.isBlank()) {
            throw ErrorLoadingException("Invalid media link parameters")
        }

        val detailUrl = "$API_URL/catalogs/$catId/items/$contentId.gzip?region=IN&auth_token=$AUTH_TOKEN"
        val detailText = app.get(detailUrl).text
        val item = tryParseJson<SingleItemResponse>(detailText)?.data
            ?: throw ErrorLoadingException("Failed to fetch media details")

        val title = item.title ?: "Unknown"
        val plot = item.description ?: item.shortDescription
        val poster = extractPoster(item.thumbnails)
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(item.releaseDateString ?: item.publishedDate ?: "")?.value?.toIntOrNull()
        val rating = item.averageUserRating?.toDoubleOrNull()
        val tags = item.displayGenres ?: item.genres ?: emptyList()
        val actors = item.starCast?.mapNotNull { it.name } ?: emptyList()

        val isShow = theme == "show" || theme == "web-series" || !item.subcategories.isNullOrEmpty()

        if (isShow) {
            val episodes = mutableListOf<Episode>()
            val subcategories = item.subcategories
            if (!subcategories.isNullOrEmpty()) {
                val catalogFriendlyId = item.catalogObject?.friendlyId ?: "web-series"
                val showFriendlyId = item.friendlyId ?: friendlyId.ifBlank { "web-series" }

                subcategories.forEachIndexed { subIndex, sub ->
                    val seasonNum = Regex("""(?:Season|Part)\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(sub.title ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: (subIndex + 1)
                    val seasonFriendly = sub.friendlyId ?: ""
                    val epUrl = "$API_URL/catalogs/$catalogFriendlyId/items/$showFriendlyId/subcategories/$seasonFriendly/episodes.gzip?order_by=asc&auth_token=$AUTH_TOKEN&region=IN&status=published"
                    try {
                        val epText = app.get(epUrl).text
                        val epRes = tryParseJson<CatalogItemsResponse>(epText)
                        epRes?.data?.items?.forEachIndexed { epIndex, ep ->
                            val epTitle = ep.title ?: "Episode ${epIndex + 1}"
                            val epPart = ep.part ?: (epIndex + 1)
                            val epContentId = ep.contentId ?: ep.id ?: ""
                            val epCatId = ep.catalogId ?: catId
                            val epPoster = extractPoster(ep.thumbnails) ?: poster
                            episodes.add(
                                newEpisode("$epCatId,$epContentId") {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epPart
                                    this.posterUrl = epPoster
                                    this.description = ep.description ?: ep.shortDescription
                                }
                            )
                        }
                    } catch (e: Throwable) {
                        logError(e)
                    }
                }
            }

            if (episodes.isEmpty()) {
                try {
                    val epListUrl = "$API_URL/catalogs/$catId/items/$contentId/episode_list?page=0&page_size=500&order_by=asc&auth_token=$AUTH_TOKEN&region=IN&item_language=en&status=published"
                    val epListText = app.get(epListUrl).text
                    val epRes = tryParseJson<CatalogItemsResponse>(epListText)
                    epRes?.data?.items?.forEachIndexed { epIndex, ep ->
                        val epTitle = ep.title ?: "Episode ${epIndex + 1}"
                        val epPart = ep.part ?: (epIndex + 1)
                        val epContentId = ep.contentId ?: ep.id ?: ""
                        val epCatId = ep.catalogId ?: catId
                        val epPoster = extractPoster(ep.thumbnails) ?: poster
                        episodes.add(
                            newEpisode("$epCatId,$epContentId") {
                                this.name = epTitle
                                this.season = 1
                                this.episode = epPart
                                this.posterUrl = epPoster
                                this.description = ep.description ?: ep.shortDescription
                            }
                        )
                    }
                } catch (e: Throwable) {
                    logError(e)
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        } else {
            val loadData = "$catId,$contentId"
            return newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        if (parts.size < 2) return false
        val catalogId = parts[0].trim()
        val contentId = parts[1].trim()

        var streamFound = false

        // 1. Direct stream retrieval with session
        val session = getSession()
        if (session.isNotBlank()) {
            streamFound = extractStreamWithSession(catalogId, contentId, session, callback)
            if (!streamFound) {
                val freshSession = refreshSession()
                if (freshSession.isNotBlank()) {
                    streamFound = extractStreamWithSession(catalogId, contentId, freshSession, callback)
                }
            }
        }

        // 2. Direct preview / trailer fallback
        if (!streamFound) {
            try {
                val detailUrl = "$API_URL/catalogs/$catalogId/items/$contentId.gzip?region=IN&auth_token=$AUTH_TOKEN"
                val detailText = app.get(detailUrl).text
                val item = tryParseJson<SingleItemResponse>(detailText)?.data
                val previewUrl = item?.preview?.previewUrl ?: item?.previewUrl
                if (!previewUrl.isNullOrBlank() && previewUrl.contains("m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = "$name (Stream)",
                        streamUrl = previewUrl,
                        referer = "$mainUrl/",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)",
                            "Referer" to "$mainUrl/"
                        )
                    ).forEach { link ->
                        callback.invoke(link)
                        streamFound = true
                    }
                }
            } catch (e: Throwable) {
                logError(e)
            }
        }

        return streamFound
    }

    private suspend fun extractStreamWithSession(
        catalogId: String,
        contentId: String,
        session: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val raw = "$catalogId$contentId$session$ts$SECRET_KEY"
            val md5 = md5(raw)

            val payload = """
                {"catalog_id":"$catalogId","content_id":"$contentId","category":"","region":"IN","auth_token":"$AUTH_TOKEN","id":"$session","md5":"$md5","ts":"$ts","platform":"ios"}
            """.trimIndent()

            val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

            val resText = app.post(
                "$API_URL/v2/users/get_all_details.gzip",
                requestBody = requestBody,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)"
                )
            ).text

            val res = tryParseJson<AllDetailsResponse>(resText)
            val hlsList = res?.data?.adaptiveUrls?.hd?.hls
            val playbackUrl = hlsList?.firstOrNull()?.playbackUrl
                ?: res?.data?.adaptiveUrl
                ?: res?.data?.playUrl?.saranyu?.url

            if (!playbackUrl.isNullOrBlank() && playbackUrl.contains("m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = playbackUrl,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X)",
                        "Referer" to "$mainUrl/"
                    )
                ).forEach(callback)
                return true
            }
        } catch (e: Throwable) {
            logError(e)
        }
        return false
    }

    private suspend fun getSession(): String {
        if (!cachedSession.isNullOrBlank()) return cachedSession!!
        return refreshSession()
    }

    private suspend fun refreshSession(): String {
        return try {
            val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
            val guestUid = "guest_${UUID.randomUUID().toString().replace("-", "")}"
            val payload = """
                {
                    "auth_token": "$AUTH_TOKEN",
                    "device_name": "Cloudstream",
                    "device_type": "android",
                    "device_id": "$deviceId",
                    "user": {
                        "ext_account_email_id": "cloudstream_guest@hellomeghalaya.in",
                        "firstname": "Cloudstream",
                        "provider": "Google",
                        "uid": "$guestUid",
                        "region": "IN",
                        "latitude": 25.5788,
                        "longitude": 91.8933,
                        "city": "Shillong",
                        "state": "Meghalaya",
                        "district": "East Khasi Hills"
                    },
                    "mode": "web"
                }
            """.trimIndent()

            val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

            val resText = app.post(
                "$API_URL/users/external_auth/sign_in",
                requestBody = requestBody,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
            ).text

            val res = tryParseJson<AuthResponse>(resText)
            val session = res?.data?.session.orEmpty()
            if (session.isNotBlank()) {
                cachedSession = session
            }
            session
        } catch (e: Throwable) {
            logError(e)
            ""
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractPoster(thumbnails: ThumbnailsDto?): String? {
        if (thumbnails == null) return null
        val raw = thumbnails.xlImage23?.url?.ifBlank { null }
            ?: thumbnails.large23?.url?.ifBlank { null }
            ?: thumbnails.medium23?.url?.ifBlank { null }
            ?: thumbnails.small23?.url?.ifBlank { null }
            ?: thumbnails.xlImage169?.url?.ifBlank { null }
            ?: thumbnails.large169?.url?.ifBlank { null }
            ?: thumbnails.medium169?.url?.ifBlank { null }
            ?: thumbnails.small169?.url?.ifBlank { null }
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("http")) raw else "https://vod.hellomeghalaya.in$raw"
    }

    // JSON DTO Models
    data class CatalogItemsResponse(
        @JsonProperty("data") val data: CatalogItemsData? = null
    )

    data class CatalogItemsData(
        @JsonProperty("items") val items: List<ItemDto>? = null,
        @JsonProperty("total_items_count") val totalItemsCount: Int? = null
    )

    data class ComingSoonResponse(
        @JsonProperty("data") val data: List<ItemDto>? = null
    )

    data class ItemDto(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("content_id") val contentId: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("catalog_id") val catalogId: String? = null,
        @JsonProperty("catalog_object") val catalogObject: CatalogObject? = null,
        @JsonProperty("theme") val theme: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("short_description") val shortDescription: String? = null,
        @JsonProperty("duration_string") val durationString: String? = null,
        @JsonProperty("release_date_string") val releaseDateString: String? = null,
        @JsonProperty("published_date") val publishedDate: String? = null,
        @JsonProperty("average_user_rating") val averageUserRating: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("display_genres") val displayGenres: List<String>? = null,
        @JsonProperty("thumbnails") val thumbnails: ThumbnailsDto? = null,
        @JsonProperty("friendly_id") val friendlyId: String? = null,
        @JsonProperty("preview") val preview: PreviewDto? = null,
        @JsonProperty("preview_url") val previewUrl: String? = null,
        @JsonProperty("subcategories") val subcategories: List<SubcategoryDto>? = null,
        @JsonProperty("part") val part: Int? = null,
        @JsonProperty("star_cast") val starCast: List<PersonDto>? = null,
        @JsonProperty("director") val director: List<PersonDto>? = null
    )

    data class CatalogObject(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("friendly_id") val friendlyId: String? = null
    )

    data class ThumbnailsDto(
        @JsonProperty("xl_image_2_3") val xlImage23: ImageDto? = null,
        @JsonProperty("large_2_3") val large23: ImageDto? = null,
        @JsonProperty("medium_2_3") val medium23: ImageDto? = null,
        @JsonProperty("small_2_3") val small23: ImageDto? = null,
        @JsonProperty("xl_image_16_9") val xlImage169: ImageDto? = null,
        @JsonProperty("large_16_9") val large169: ImageDto? = null,
        @JsonProperty("medium_16_9") val medium169: ImageDto? = null,
        @JsonProperty("small_16_9") val small169: ImageDto? = null
    )

    data class ImageDto(
        @JsonProperty("url") val url: String? = null
    )

    data class PreviewDto(
        @JsonProperty("preview_available") val previewAvailable: Boolean? = null,
        @JsonProperty("preview_url") val previewUrl: String? = null
    )

    data class SubcategoryDto(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("content_id") val contentId: String? = null,
        @JsonProperty("friendly_id") val friendlyId: String? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )

    data class PersonDto(
        @JsonProperty("name") val name: String? = null
    )

    data class Search3Response(
        @JsonProperty("data") val data: Search3Data? = null
    )

    data class Search3Data(
        @JsonProperty("items") val items: Map<String, Search3Category>? = null
    )

    data class Search3Category(
        @JsonProperty("display_title") val displayTitle: String? = null,
        @JsonProperty("Items") val items: List<ItemDto>? = null
    )

    data class SingleItemResponse(
        @JsonProperty("data") val data: ItemDto? = null
    )

    data class AuthResponse(
        @JsonProperty("data") val data: AuthData? = null
    )

    data class AuthData(
        @JsonProperty("session") val session: String? = null,
        @JsonProperty("user_id") val userId: String? = null
    )

    data class AllDetailsResponse(
        @JsonProperty("data") val data: AllDetailsData? = null
    )

    data class AllDetailsData(
        @JsonProperty("adaptive_urls") val adaptiveUrls: AdaptiveUrlsDto? = null,
        @JsonProperty("adaptive_url") val adaptiveUrl: String? = null,
        @JsonProperty("play_url") val playUrl: PlayUrlDto? = null
    )

    data class AdaptiveUrlsDto(
        @JsonProperty("hd") val hd: QualityDto? = null,
        @JsonProperty("sd") val sd: QualityDto? = null
    )

    data class QualityDto(
        @JsonProperty("hls") val hls: List<StreamUrlDto>? = null,
        @JsonProperty("dash") val dash: List<StreamUrlDto>? = null
    )

    data class StreamUrlDto(
        @JsonProperty("playback_url") val playbackUrl: String? = null,
        @JsonProperty("protocol") val protocol: String? = null
    )

    data class PlayUrlDto(
        @JsonProperty("saranyu") val saranyu: SaranyuUrlDto? = null
    )

    data class SaranyuUrlDto(
        @JsonProperty("url") val url: String? = null
    )
}
