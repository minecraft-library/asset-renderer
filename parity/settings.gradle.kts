rootProject.name = "asset-renderer-parity"

// A leaf, and the smallest one in the repo: five annotation types importing nothing but
// java.lang.annotation. It includes no build and reads no catalogue, because it declares no
// dependency to pin - which is also what lets every build that writes a declaration take it on
// without inheriting anything else.
