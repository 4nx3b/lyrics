rootProject.name = "lyrics"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":kugou")
include(":lrclib")
// :simpmusic and :paxsenix modules removed per user request (2026-08-30).
include(":betterlyrics")
include(":unison")
include(":youlyplus")
