plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.gradle.shadow)
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    api(project(":engine"))
    implementation(project(":provider-spotify"))
    implementation(project(":provider-deezer"))
    implementation(project(":provider-youtube_music"))
    implementation(project(":provider-soundcloud"))
    implementation(project(":provider-qobuz"))
    implementation(project(":provider-tidal"))
    testImplementation(libs.echo.common)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
tasks.shadowJar {
    archiveBaseName.set("one_for_all")
    archiveVersion.set("v0.1.0")
    mergeServiceFiles()
    val repoUrl = providers.gradleProperty("extRepoUrl").orNull ?: ""
    val updateUrl = providers.gradleProperty("extUpdateUrl").orNull ?: ""
    manifest.attributes(mapOf(
        "Extension-Id" to "one_for_all", "Extension-Type" to "music",
        "Extension-Class" to "OneForAllExtension", "Extension-Name" to "One For All",
        "Extension-Version-Code" to "1", "Extension-Version-Name" to "v0.1.0",
        "Extension-Description" to providers.gradleProperty("extDescription").get(),
        "Extension-Icon-Url" to providers.gradleProperty("extIconUrl").get(),
        "Extension-Author" to providers.gradleProperty("extAuthor").get(),
        "Extension-Author-Url" to providers.gradleProperty("extAuthorUrl").get(),
        "Extension-Repo-Url" to repoUrl, "Extension-Update-Url" to updateUrl
    ))
}
