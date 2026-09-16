rootProject.name = "asset-renderer"

// The one build that sits beside this one: `parity`, the annotation vocabulary every build that
// writes a declaration compiles against, which reads nothing at all. It stays a build of its own
// because the harness includes it and an included build has to be a standalone one.
//
// Client-jar acquisition is no longer among them. It was a build of its own to be read by two - this
// one and the generators - and the generators now read it through this one, so the second consumer it
// was extracted for does not exist. It lives at `lib.minecraft.renderer.client` in this source tree.
//
// The harness is not here: it has its own toolchain and Loom, and this build reaches it by shelling
// into its wrapper rather than by resolving anything from it.
includeBuild("parity")

// The generators are a SUBPROJECT of this build rather than a build beside it, which is what lets
// them resolve this build's production types instead of re-declaring them. It has to be one build:
// a composite substitutes an INCLUDED build into its root and never the reverse, so a generator
// depending on the renderer through `includeBuild("..")` resolves only when the generators are the
// build being invoked - measured, and it falls through to a repository lookup otherwise.
//
// The direction stays one-way. `:tooling` takes `project(":")` on `implementation`, so nothing it
// declares - ASM included - reaches this project's classpath or the published JAR.
include("tooling")

// A capture continues past a failed producer, and that is how it is DRIVEN rather than a flag anyone
// has to remember. A capture's job is to produce a comparable set; a producer that failed is a result
// to record, not a reason to discard the rows that succeeded. Forgetting `--continue` was its own
// recurring cost - a capture would run its full six minutes, lose the index to one red self-captured
// row, and be started again from nothing - and the case it struck most is the one a capture exists
// for: re-baselining a pin whose own test asserts on the value being re-based, so the suite is red
// BECAUSE of the change under measurement.
//
// It lives HERE because nowhere later works. The execution plan is built once configuration ends, so
// a project script setting this has already missed it - measured, not assumed: set from
// `gradle/parity.gradle.kts` the flag reads back as set and the build still halts on the first failed
// producer. Settings is evaluated before any of that.
//
// Read off the typed tokens, where every parity refusal is deliberately read off the resolved graph
// instead. There is no graph yet, so a token match is the only question available; the substring is
// wider than Gradle's own abbreviation matching in one direction only. Over-answering costs a
// `--continue` on an invocation that names a capture and never reaches one, and under-answering is
// the loop above.
if (gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("parityCapture") })
    gradle.startParameter.isContinueOnFailure = true
