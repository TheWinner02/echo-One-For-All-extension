plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.google.protobuf)
}
kotlin { jvmToolchain(21) }
dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.protobuf.java)
}
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated/sources/proto/main/java")) }
protobuf { protoc { artifact = "com.google.protobuf:protoc:4.32.0" } }
dependencies {
}
