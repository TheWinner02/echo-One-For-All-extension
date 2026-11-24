plugins { `java-library`; alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { compileOnly(libs.echo.common); compileOnly(libs.kotlin.stdlib) }
