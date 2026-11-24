pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.toastbits.dev")
    }
}
rootProject.name = "One For All"
include(":app", ":ext", ":engine")
include(":provider-spotify")
project(":provider-spotify").projectDir = file("providers/spotify")
include(":provider-deezer")
project(":provider-deezer").projectDir = file("providers/deezer")
include(":provider-youtube_music")
project(":provider-youtube_music").projectDir = file("providers/youtube_music")
include(":provider-soundcloud")
project(":provider-soundcloud").projectDir = file("providers/soundcloud")
include(":provider-qobuz")
project(":provider-qobuz").projectDir = file("providers/qobuz")
include(":provider-tidal")
project(":provider-tidal").projectDir = file("providers/tidal")
