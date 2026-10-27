pluginManagement {
    repositories {
        mavenLocal { content { includeGroupByRegex("io\\.heapy\\.kinetica.*") } }
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "io.heapy.kinetica") {
            useModule("io.heapy.kinetica:kinetica-gradle-plugin:${requested.version}")
        }
    }
}
dependencyResolutionManagement {
    repositories {
        // The locked Kinetica snapshot is installed by scripts/build-kinetica.sh.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("io.heapy.kinetica") }
        }
        mavenCentral()
    }
}
rootProject.name = "dotki"
include(":shared", ":server", ":web", ":desktop")
