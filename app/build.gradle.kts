plugins {
    alias(libs.plugins.android.application)
}

dependencies {
    implementation(project(":ext"))
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.protobuf.java)

    // AGP resolves the compile classpath consistently with the runtime classpath.
    // Align the stdlib brought by embedded providers with our Kotlin compiler.
    constraints {
        implementation(libs.kotlin.stdlib) {
            because("Keep compile and runtime Kotlin standard libraries aligned")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

val extType = project.findProperty("extType")?.toString() ?: ""
val extId = project.findProperty("extId")?.toString() ?: ""
val extClass = project.findProperty("extClass")?.toString() ?: ""

val extIconUrl = project.findProperty("extIconUrl")?.toString()
val extName = project.findProperty("extName")?.toString() ?: ""
val extDescription = project.findProperty("extDescription")?.toString()

val extAuthor = project.findProperty("extAuthor")?.toString() ?: ""
val extAuthorUrl = project.findProperty("extAuthorUrl")?.toString()

val extRepoUrl = project.findProperty("extRepoUrl")?.toString()
val extUpdateUrl = project.findProperty("extUpdateUrl")?.toString()

val verCode = (project.findProperty("extVersionCode") as? String)?.toIntOrNull()
    ?: runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val verName = (project.findProperty("extVersionName") as? String)
    ?: runCatching { "v" + execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("v1.0.0")


val outputDir = file("${layout.buildDirectory.asFile.get()}/generated/proguard")
val generatedProguard = file("${outputDir}/generated-rules.pro")

tasks.register("generateProguardRules") {
    doLast {
        outputDir.mkdirs()
        generatedProguard.writeText(
            """
                -dontobfuscate
                -dontoptimize
                -dontwarn java.lang.management.**
                -dontwarn io.ktor.**
                -dontwarn org.slf4j.**
                -keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
                -keep,allowoptimization class dev.brahmkshatriya.echo.extension.$extClass { public <init>(); public *; }
                -keep class dev.brahmkshatriya.echo.extension.** { *; }
                -keepclassmembers class dev.brahmkshatriya.echo.extension.** { *; }
                -keep class dev.brahmkshatriya.echo.oneforall.** { *; }
                -keepclassmembers class dev.brahmkshatriya.echo.oneforall.** { *; }
                -keep class dev.toastbits.ytmkt.** { *; }
                -keepclassmembers class dev.toastbits.ytmkt.** { *; }
                -keep class io.ktor.** { *; }
                -keepclassmembers class io.ktor.** { *; }
                -keep class kotlinx.serialization.** { *; }
                -keepclassmembers class kotlinx.serialization.** { *; }
                -keep class org.schabi.newpipe.** { *; }
                -keepclassmembers class org.schabi.newpipe.** { *; }
                -keep class org.mozilla.** { *; }
                -keepclassmembers class org.mozilla.** { *; }
                """.trimIndent()
        )
    }
}

tasks.named("preBuild") {
    dependsOn("generateProguardRules")
}

android {
    namespace = "dev.brahmkshatriya.echo.extension"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.brahmkshatriya.echo.extension.$extId"
        minSdk = 24
        targetSdk = 36
        versionCode = verCode
        versionName = verName

        manifestPlaceholders.apply {
            put("type", "dev.brahmkshatriya.echo.${extType}")
            put("id", extId)
            put("class_path", "dev.brahmkshatriya.echo.extension.${extClass}")
            put("version", verName)
            put("version_code", verCode.toString())
            put("icon_url", extIconUrl ?: "")
            put("app_name", "Echo : $extName Extension")
            put("name", extName)
            put("description", extDescription ?: "")
            put("author", extAuthor)
            put("author_url", extAuthorUrl ?: "")
            put("repo_url", extRepoUrl ?: "")
            put("update_url", extUpdateUrl ?: "")
        }
    }

    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/module-info.class") }

    buildTypes {
        all {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                generatedProguard.absolutePath
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
tasks.register<Copy>("exportExtension") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk"); rename { "one_for_all-v0.1.0.eapk" } }
    into(rootProject.layout.projectDirectory.dir("release"))
}
