dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "Watch Movies and TV Series from 123moviesbd.one"
    authors = listOf("Antigravity")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.movies123bd"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
