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
