plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    api(libs.ytmkt) { exclude(group = "org.jetbrains.kotlin"); exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core") }
    implementation(libs.newpipe) { exclude(group = "org.jetbrains.kotlin"); exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core") }
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}
