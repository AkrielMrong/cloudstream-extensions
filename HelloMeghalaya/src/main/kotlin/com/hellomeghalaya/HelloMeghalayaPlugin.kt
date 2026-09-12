package com.hellomeghalaya

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HelloMeghalayaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HelloMeghalayaProvider())
    }
}
