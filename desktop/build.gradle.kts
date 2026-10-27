plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("io.heapy.kinetica")
}
kotlin {
    jvm()
    macosArm64("native") { binaries.executable { entryPoint = "dotki.desktop.main" } }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation("io.heapy.kinetica:kinetica-render-core:0.4.0-dotki.1")
            implementation("io.ktor:ktor-client-core:3.5.1")
            implementation("io.ktor:ktor-client-websockets:3.5.1")
        }
        nativeMain.dependencies {
            implementation("io.heapy.kinetica:kinetica-appkit:0.4.0-dotki.1")
            implementation("io.ktor:ktor-client-darwin:3.5.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
