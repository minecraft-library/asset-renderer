rootProject.name = "asset-renderer-tooling"

// The renderer, as a build rather than a source set. Tooling reads its client-jar acquisition and a
// handful of vocabulary types; nothing in the renderer reads tooling, and Gradle forbids the cycle
// that would let it - which is the property the split exists to hold.
//
// The renderer drives the flows the other way through `Exec` shells into this build's wrapper, the
// same shape `harnessClasses` uses for the harness, so the edge stays one-directional in both the
// dependency graph and the task graph.
includeBuild("..")

// The renderer's catalogue, read rather than copied: a version pinned in one build and re-typed in
// the other is the drift this shares a file to avoid.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
