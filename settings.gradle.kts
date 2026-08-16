rootProject.name = "asset-renderer"

// The three builds that sit beside this one. `client` is the leaf both this and the generators read
// for client-jar acquisition; `tooling` is the generators, which read `client` and nothing here;
// `parity` is the annotation vocabulary every build that writes a declaration compiles against, and
// reads nothing at all.
//
// The harness is not here: it has its own toolchain and Loom, and this build reaches it by shelling
// into its wrapper rather than by resolving anything from it.
includeBuild("client")
includeBuild("tooling")
includeBuild("parity")
