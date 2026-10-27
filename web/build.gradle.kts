plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("io.heapy.kinetica")
}
kotlin {
    js {
        browser { commonWebpackConfig { outputFileName = "client.js" } }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies { implementation(project(":shared")) }
    }
}
