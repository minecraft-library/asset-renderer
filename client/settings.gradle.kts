rootProject.name = "asset-renderer-client"

// A leaf. Both the renderer and the generators include this build; it includes nothing.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
