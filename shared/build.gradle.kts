plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
kotlin {
    jvm()
    js { browser() }
    val host = if (System.getProperty("os.name") == "Mac OS X") "macosArm64"
        else if (System.getProperty("os.arch") == "aarch64") "linuxArm64" else "linuxX64"
    when (val target = providers.gradleProperty("nativeTarget").getOrElse(host)) {
        "macosArm64" -> macosArm64("native")
        "linuxArm64" -> linuxArm64("native")
        "linuxX64" -> linuxX64("native")
        else -> error("Unsupported nativeTarget: $target")
    }
    sourceSets {
        commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
