package com.movies123bd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

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

        val hosts = mutableListOf<Pair<String, String>>()

        if (isMovie) {
            val tmdbId = data
            try {
                val vsSrc = app.get("https://vidsrcme.ru/vs_src.php?type=movie&id=$tmdbId").text
                tryParseJson<VidSrcResponse>(vsSrc)?.src?.let { srcUrl ->
                    hosts.add("VidSrc Direct" to srcUrl)
                }
            } catch (e: Throwable) {
                logError(e)
            }

            hosts.add("CineSrc" to "https://cinesrc.st/embed/movie/$tmdbId")
            hosts.add("VidSrc" to "https://vidsrc.me/embed/movie?tmdb=$tmdbId")
            hosts.add("VidSrcPM" to "https://vidsrc.pm/embed/movie?tmdb=$tmdbId")
            hosts.add("Smashy" to "https://player.smashy.stream/movie/$tmdbId")
        } else {
            val parts = data.split(",")
            val tmdbId = parts[1]
            val s = parts[2]
            val e = parts[3]

            try {
                val vsSrc = app.get("https://vidsrcme.ru/vs_src.php?type=tv&id=$tmdbId&season=$s&episode=$e").text
                tryParseJson<VidSrcResponse>(vsSrc)?.src?.let { srcUrl ->
                    hosts.add("VidSrc Direct" to srcUrl)
                }
            } catch (err: Throwable) {
                logError(err)
            }

            hosts.add("CineSrc" to "https://cinesrc.st/embed/tv/$tmdbId?s=$s&e=$e")
            hosts.add("VidSrc" to "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$s&episode=$e")
            hosts.add("VidSrcPM" to "https://vidsrc.pm/embed/tv?tmdb=$tmdbId&season=$s&episode=$e")
            hosts.add("Smashy" to "https://player.smashy.stream/tv/$tmdbId/$s/$e")
        }

        hosts.amap { (serverName, embedUrl) ->
            resolveStreamFromEmbed(serverName, embedUrl, "$mainUrl/", subtitleCallback, callback)
        }

        return true
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
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(m3u8|\.mp4|master\.txt)"""),
                additionalUrls = listOf(Regex("""(m3u8|\.mp4|master\.txt)""")),
                useOkhttp = false,
                timeout = 25_000L
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
