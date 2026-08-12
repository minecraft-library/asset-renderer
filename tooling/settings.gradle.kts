rootProject.name = "asset-renderer-tooling"

// The client-jar acquisition leaf, and the only build this one reads. The renderer is deliberately
// absent: the generators emit the JSON it loads and share its vocabulary by value rather than by
// type, so nothing here resolves against it and the renderer is free to include this build.
includeBuild("../client")

// The renderer's catalogue, read rather than copied: a version pinned in one build and re-typed in
// the other is the drift this shares a file to avoid.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
