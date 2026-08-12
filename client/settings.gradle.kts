rootProject.name = "asset-renderer-client"

// A leaf. Both the renderer and the generators include this build; it includes nothing, which is
// what lets the renderer include the generators in turn without a cycle.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
