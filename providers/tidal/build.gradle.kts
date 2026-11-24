plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
}
