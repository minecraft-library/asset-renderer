rootProject.name = "asset-renderer"

// The two builds that sit beside this one. `client` is the leaf both this and the generators read
// for client-jar acquisition; `tooling` is the generators, which read `client` and nothing here.
//
// The harness is not here: it has its own toolchain and Loom, and this build reaches it by shelling
// into its wrapper rather than by resolving anything from it.
includeBuild("client")
includeBuild("tooling")
