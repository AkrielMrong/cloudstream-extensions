package com.movies123bd

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Movies123BDPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Movies123BDProvider())
    }
}
