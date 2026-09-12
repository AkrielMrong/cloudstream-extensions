dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "Watch Movies, Web Series, Music Videos, and Podcasts from Hello Meghalaya (hellomeghalaya.in)"
    authors = listOf("Antigravity")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.hellomeghalaya"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
