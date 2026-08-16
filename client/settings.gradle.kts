rootProject.name = "asset-renderer-client"

// Both the renderer and the generators include this build. The one it includes itself is the
// annotation vocabulary it declares its own parity reach in, which resolves nothing and ships
// nothing.
includeBuild("../parity")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
