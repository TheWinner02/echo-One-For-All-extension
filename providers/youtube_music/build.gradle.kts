plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    api(files("libs/ytm-kt-0.4.3.jar"))
    api(libs.newpipe) { exclude(group = "org.jetbrains.kotlin"); exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core") }
    api(libs.ktor.client.core)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)
}
