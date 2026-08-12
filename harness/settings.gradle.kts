pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // Loom's version is a gradle.properties entry, and a Kotlin `plugins` block takes a constant.
    // Resolving it here is what lets the build script name the plugin without repeating the version:
    // the property stays the one place it is written.
    val loom_version: String by settings
    plugins {
        id("net.fabricmc.fabric-loom") version loom_version
    }
}

rootProject.name = "vanilla-reference-harness"
