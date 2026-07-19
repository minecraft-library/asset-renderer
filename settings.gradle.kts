rootProject.name = "asset-renderer"

// TEMPORARY local composite build - remove before the real gson-extras publish + pin bump.
// Builds asset against live gson-extras source instead of the JitPack pin in build.gradle.kts.
// The explicit dependencySubstitution bridges the coordinate mismatch: asset depends on
// `com.github.simplified-dev:gson-extras` (JitPack) while the local project publishes as
// `dev.simplified:gson-extras`. Revert = delete this block.
includeBuild("../../Simplified-Dev/gson-extras") {
    dependencySubstitution {
        substitute(module("com.github.simplified-dev:gson-extras")).using(project(":"))
    }
}
