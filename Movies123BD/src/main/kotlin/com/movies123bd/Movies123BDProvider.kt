package com.movies123bd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Movies123BDProvider : MainAPI() {
    override var mainUrl = "https://123moviesbd.one"
    override var name = "123MoviesBD"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies-hd/?page=" to "Popular Movies",
        "$mainUrl/tv-series/?page=" to "TV Series",
        "$mainUrl/top-imdb/?page=" to "Top IMDb"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val homeItems = document.select("a.card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl).document
        return document.select("a.card").mapNotNull { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val poster = fixUrlNull(this.selectFirst(".poster img")?.attr("src"))
        val isMovie = href.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val isMovie = url.contains("/movie/")

        val tmdbId = Regex(""".*-(\d+)""").find(url.trimEnd('/'))?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Failed to locate TMDB identifier")

        val jsonLdScript = document.selectFirst("script[type='application/ld+json']")?.data()
        val ldData = jsonLdScript?.let { tryParseJson<JsonLdMovie>(it) }

        val title = ldData?.name ?: document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = ldData?.image ?: fixUrlNull(document.selectFirst(".poster img")?.attr("src"))
        val plot = ldData?.description ?: document.selectFirst(".overview")?.text()
        val year = ldData?.datePublished?.take(4)?.toIntOrNull()
        val rating = ldData?.aggregateRating?.ratingValue?.toDoubleOrNull()
        val cast = ldData?.actor?.mapNotNull { it.name } ?: emptyList()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, tmdbId) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating)
                addActors(cast)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonCards = document.select(".grid .card")
            if (seasonCards.isNotEmpty()) {
                seasonCards.forEach { card ->
                    val seasonText = card.selectFirst("h3")?.text()?.trim() ?: ""
                    val seasonNum = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(seasonText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epText = card.selectFirst(".rating")?.text()?.trim() ?: ""
                    val epCount = Regex("""(\d+)\s*ep""", RegexOption.IGNORE_CASE)
                        .find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val seasonPoster = fixUrlNull(card.selectFirst(".poster img")?.attr("src"))

                    for (ep in 1..epCount) {
                        episodes.add(
                            newEpisode("tv,$tmdbId,$seasonNum,$ep") {
                                this.name = "Season $seasonNum Episode $ep"
                                this.season = seasonNum
                                this.episode = ep
                                this.posterUrl = seasonPoster
                            }
                        )
                    }
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode("tv,$tmdbId,1,1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(rating)
                addActors(cast)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = !data.startsWith("tv,")
        val tmdbId: String
        val season: Int?
        val episode: Int?

        if (isMovie) {
            tmdbId = data
            season = null
            episode = null
        } else {
            val parts = data.split(",")
            tmdbId = parts[1]
            season = parts[2].toIntOrNull()
            episode = parts[3].toIntOrNull()
        }

        val tasks = listOf<suspend () -> Unit>(
            // 1. Direct VidEm API resolution (1080p, 720p, 360p HLS)
            { invokeVidEm(tmdbId, season, episode, callback) },

            // 2. Direct 2Embed / Uqloads resolution
            { invoke2embed(tmdbId, season, episode, callback) },

            // 3. Direct VidSrc API
            {
                try {
                    val vsUrl = if (isMovie) {
                        "https://vidsrcme.ru/vs_src.php?type=movie&id=$tmdbId"
                    } else {
                        "https://vidsrcme.ru/vs_src.php?type=tv&id=$tmdbId&season=$season&episode=$episode"
                    }
                    val vsSrc = app.get(vsUrl).text
                    tryParseJson<VidSrcResponse>(vsSrc)?.src?.let { srcUrl ->
                        resolveStreamFromEmbed("VidSrc Direct", srcUrl, "https://vidsrcme.ru/", subtitleCallback, callback)
                    }
                } catch (e: Throwable) {
                    logError(e)
                }
            },

            // 4. CineSrc embed fallback
            {
                val cinesrcUrl = if (isMovie) {
                    "https://cinesrc.st/embed/movie/$tmdbId"
                } else {
                    "https://cinesrc.st/embed/tv/$tmdbId?s=$season&e=$episode"
                }
                resolveStreamFromEmbed("CineSrc", cinesrcUrl, "$mainUrl/", subtitleCallback, callback)
            },

            // 5. VidSrc embed fallback
            {
                val vidsrcUrl = if (isMovie) {
                    "https://vidsrc.me/embed/movie?tmdb=$tmdbId"
                } else {
                    "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
                }
                resolveStreamFromEmbed("VidSrc", vidsrcUrl, "$mainUrl/", subtitleCallback, callback)
            }
        )

        tasks.amap { task ->
            try {
                task.invoke()
            } catch (e: Throwable) {
                logError(e)
            }
        }

        return true
    }

    private suspend fun invokeVidEm(
        tmdbId: String,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val embedUrl = if (season != null && episode != null) {
                "https://videm.xyz/embed/tv/$tmdbId/$season/$episode"
            } else {
                "https://videm.xyz/embed/movie/$tmdbId"
            }

            val text = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.2embed.cc/"
                )
            ).text

            val qMatch = Regex("""var\s+Q\s*=\s*(\{.+?\});""", RegexOption.DOT_MATCHES_ALL).find(text)
            val qJson = qMatch?.groupValues?.get(1) ?: return
            val vidEmData = tryParseJson<VidEmData>(qJson) ?: return
            val tToken = vidEmData.t ?: return
            val servers = vidEmData.ssr?.servers ?: return

            servers.amap { server ->
                try {
                    val ref = server.ref ?: return@amap
                    val playUrl = "https://videm.xyz/api.php?a=play&ref=${URLEncoder.encode(ref, "UTF-8")}&t=${URLEncoder.encode(tToken, "UTF-8")}&fresh=1"
                    val playRespText = app.get(
                        playUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl
                        )
                    ).text
                    val playResp = tryParseJson<VidEmPlayResponse>(playRespText) ?: return@amap
                    val relUrl = playResp.url ?: return@amap
                    val streamUrl = if (relUrl.startsWith("http")) relUrl else "https://videm.xyz$relUrl"

                    M3u8Helper.generateM3u8(
                        source = "123Movies (VidEm - ${server.name ?: "HD"})",
                        streamUrl = streamUrl,
                        referer = "https://videm.xyz/",
                        headers = mapOf(
                            "Referer" to "https://videm.xyz/",
                            "User-Agent" to USER_AGENT
                        )
                    ).forEach(callback)
                } catch (e: Throwable) {
                    logError(e)
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private suspend fun invoke2embed(
        tmdbId: String,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "Referer" to "https://www.2embed.cc",
                "User-Agent" to USER_AGENT,
                "sec-fetch-dest" to "iframe"
            )
            val slug = if (season != null && episode != null) {
                "embedtv/$tmdbId&s=$season&e=$episode"
            } else {
                "embed/$tmdbId"
            }
            val api = "https://www.2embed.cc/$slug"
            val text = app.get(api, headers = headers).text
            val sKey = "swish?id="
            val start = text.indexOf(sKey)
            if (start < 0) return
            val end = text.indexOf("'", start + sKey.length)
            if (end < 0) return
            val strmId = text.substring(start + sKey.length, end)

            val uplUrl = "https://uqloads.xyz/e/$strmId"
            val res = app.get(uplUrl, headers = headers).text
            val sKey2 = "eval(function"
            val start2 = res.indexOf(sKey2)
            if (start2 < 0) return
            val eKey2 = "split('|')))"
            val end2 = res.indexOf(eKey2, start2)
            if (end2 < 0) return
            val packed = res.substring(start2, end2 + eKey2.length)
            val strmData = JsUnpacker(packed).unpack() ?: return
            val sKey3 = "\"hls2\":\""
            val start3 = strmData.indexOf(sKey3)
            if (start3 >= 0) {
                val sStart = start3 + sKey3.length
                val sEnd = strmData.indexOf("\"", sStart)
                if (sEnd > sStart) {
                    val streamUrl = strmData.substring(sStart, sEnd)
                    M3u8Helper.generateM3u8(
                        source = "123Movies (2Embed)",
                        streamUrl = streamUrl,
                        referer = "https://uqloads.xyz/",
                        headers = mapOf(
                            "Referer" to "https://uqloads.xyz/",
                            "User-Agent" to USER_AGENT
                        )
                    ).forEach(callback)
                }
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private suspend fun resolveStreamFromEmbed(
        serverName: String,
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extracted = loadExtractor(url, referer, subtitleCallback, callback)
        if (extracted) return

        try {
            val autoClickScript = """
                (function() {
                    function clickPlay() {
                        var v = document.querySelector('video');
                        if (v) { try { v.muted = true; v.play(); } catch(e){} }
                        var btns = document.querySelectorAll('button, .jw-bigplay, #bigPlay, .play-button, [aria-label="Play"]');
                        for (var i = 0; i < btns.length; i++) {
                            try { btns[i].click(); } catch(e){}
                        }
                    }
                    clickPlay();
                    setInterval(clickPlay, 1000);
                })();
            """.trimIndent()

            val resolver = WebViewResolver(
                interceptUrl = Regex("""(m3u8|\.mp4|master\.txt)"""),
                additionalUrls = listOf(Regex("""(m3u8|\.mp4|master\.txt)""")),
                useOkhttp = false,
                script = autoClickScript,
                timeout = 20_000L
            )
            val response = app.get(
                url,
                referer = referer,
                interceptor = resolver
            )
            val streamUrl = response.url
            if (streamUrl != url && (streamUrl.contains("m3u8") || streamUrl.contains("master.txt"))) {
                M3u8Helper.generateM3u8(
                    source = "123Movies ($serverName)",
                    streamUrl = streamUrl,
                    referer = url,
                    headers = response.headers.toMap()
                ).forEach(callback)
            } else if (streamUrl != url && streamUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = "123Movies ($serverName)",
                        name = "123Movies ($serverName)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = response.headers.toMap()
                    }
                )
            }
        } catch (e: Throwable) {
            logError(e)
        }
    }

    data class VidEmData(
        @JsonProperty("t") val t: String?,
        @JsonProperty("ssr") val ssr: VidEmSsr?
    )

    data class VidEmSsr(
        @JsonProperty("servers") val servers: List<VidEmServer>?
    )

    data class VidEmServer(
        @JsonProperty("ref") val ref: String?,
        @JsonProperty("name") val name: String?
    )

    data class VidEmPlayResponse(
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?
    )

    data class VidSrcResponse(
        @JsonProperty("src") val src: String?
    )

    data class JsonLdMovie(
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("datePublished") val datePublished: String?,
        @JsonProperty("aggregateRating") val aggregateRating: LdRating?,
        @JsonProperty("actor") val actor: List<LdPerson>?
    )

    data class LdRating(@JsonProperty("ratingValue") val ratingValue: String?)
    data class LdPerson(@JsonProperty("name") val name: String?)
}
