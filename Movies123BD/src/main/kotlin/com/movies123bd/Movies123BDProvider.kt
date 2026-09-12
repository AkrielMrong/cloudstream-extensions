package com.movies123bd

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Movies123BDProvider : MainAPI() {
    override var mainUrl = "https://123moviesbd.one"
    override var name = "123MoviesBD"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
        val ldData = jsonLdScript?.let { parseJson<JsonLdMovie>(it) }

        val title = ldData?.name ?: document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = ldData?.image ?: fixUrlNull(document.selectFirst(".poster img")?.attr("src"))
        val plot = ldData?.description ?: document.selectFirst(".overview")?.text()
        val year = ldData?.datePublished?.take(4)?.toIntOrNull()
        val rating = ldData?.aggregateRating?.ratingValue?.toRatingInt()
        val cast = ldData?.actor?.mapNotNull { it.name } ?: emptyList()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, tmdbId) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating
                addActors(cast)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            episodes.add(
                newEpisode("tv,$tmdbId,1,1") {
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                }
            )

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating
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

        val hosts = if (isMovie) {
            val tmdbId = data
            listOf(
                "https://cinesrc.st/embed/movie/$tmdbId",
                "https://vidsrc.me/embed/movie?tmdb=$tmdbId"
            )
        } else {
            val parts = data.split(",")
            val tmdbId = parts[1]
            val s = parts[2]
            val e = parts[3]
            listOf(
                "https://cinesrc.st/embed/tv/$tmdbId?s=$s&e=$e",
                "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$s&episode=$e"
            )
        }

        hosts.forEach { embedUrl ->
            loadExtractor(embedUrl, subtitleCallback, callback)
        }

        return true
    }

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
