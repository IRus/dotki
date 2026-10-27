plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

tasks.register<JavaExec>("compareBots") {
    dependsOn("jvmTestClasses")
    val test = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass.set("dotki.server.BotComparisonKt")
    args(providers.gradleProperty("comparisonGame").orElse("ALL").get())
}
kotlin {
    jvm()
    val host = if (System.getProperty("os.name") == "Mac OS X") "macosArm64"
        else if (System.getProperty("os.arch") == "aarch64") "linuxArm64" else "linuxX64"
    val native = when (val target = providers.gradleProperty("nativeTarget").getOrElse(host)) {
        "macosArm64" -> macosArm64("native")
        "linuxArm64" -> linuxArm64("native")
        "linuxX64" -> linuxX64("native")
        else -> error("Unsupported nativeTarget: $target")
    }
    native.binaries.executable { entryPoint = "dotki.server.main" }
    native.binaries.all { linkerOpts("-lsqlite3") }
    providers.gradleProperty("sqliteLibraryDir").orNull?.let { directory ->
        native.binaries.all { linkerOpts("-L$directory", "--allow-shlib-undefined") }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation("io.ktor:ktor-server-core:3.5.1")
            implementation("io.ktor:ktor-server-cio:3.5.1")
            implementation("io.ktor:ktor-server-websockets:3.5.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
        }
        nativeMain.dependencies { implementation("co.touchlab:sqliter-driver:1.4.0") }
    }
}
