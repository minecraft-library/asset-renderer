# Build scripts

Load when touching `build.gradle.kts`, a script under `gradle/`, a source set, the Vector module
flag, or the dependency between this build and `:tooling`. `CLAUDE.md`'s *Build* says what the root
script keeps and the order it applies the other three in. Indexed from
[`CLAUDE.md`](../../CLAUDE.md).

- An applied script sees no declaration of the root's and gets no type-safe accessors. Both are why
  `val sourceSets = the<SourceSetContainer>()` opens two of them, and why the one value they need
  from the root - the resolved `-Dasset.*` set - crosses as `extra["assetFlagsInForce"]` rather
  than as the function that computes it.
- The guards read all four as one text through `BuildScripts.all()`, so what they pin is what the
  build declares rather than which file declares it.
- JDK 21 with the **Vector API incubator** (`--add-modules=jdk.incubator.vector`), wired into
  JavaCompile, Test, JavaExec, JMH and Javadoc in `build.gradle.kts`. It is a COMPILE requirement:
  `SimdOps` is the one source that imports `jdk.incubator.vector.*`, and every other task carries the
  flag so this repo's own launches stay on the SIMD path rather than because a launch without it
  breaks. Missing it on a JVM launch is a **silent scalar fallback**, not a class-not-found:
  `SimdSupport` probes once with a non-initialising `Class.forName` inside `catch (Throwable)`, and
  the two-class split is what keeps the JVM from ever resolving the incubator imports when the answer
  is no. That is also why a downstream consumer of the published JAR owes the flag nothing. Missing
  it on `javadoc` IS a hard failure - `SimdOps` reports the package as not visible. `javadoc` stays
  red either way - every error it has left is a builder an annotation processor produces and the
  doclet cannot see - so it is wired because the flag belongs everywhere it is read, not because the
  task becomes usable.
- ASM 9.8 reads Java 25 class files; the tooling flows walk client-jar bytecode with it. It is
  declared in `tooling/build.gradle.kts` alone and `:tooling` is taken by nobody, so it is on this
  project's classpath nowhere and in no published JAR.
- A composite substitutes an INCLUDED build into its root and never the reverse, which is why one
  build rather than two is the only shape that works; see [tooling/CLAUDE.md](../../tooling/CLAUDE.md).
- `src/visual/java` is a source set of its own: the render drivers, the parity sweeps, the parity
  store and the pipeline dump - programs with a `main`, not tests. The test set reads the store, so
  it takes visual's output, one way and never the reverse, and visual inherits the test
  configurations rather than re-declaring every pin.
- The dependency runs one way and only one. `:tooling` takes `project(":")`; nothing here names a
  tooling type, and `implementation` is what stops ASM and the walkers coming back - they are on
  this project's classpath nowhere and in no published JAR.
- Both wrappers - this build's and the harness's - name their Gradle distribution twice, by
  `distributionUrl` and by `distributionSha256Sum`, so a wrapper refuses a download whose bytes are
  not the ones Gradle published. A version bump moves three things together. Run
  `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>` with the sum read
  from `https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`; hold the regenerated
  `gradle/wrapper/gradle-wrapper.jar` to `gradle-<v>-wrapper.jar.sha256` from the same place; and
  copy the four wrapper files over the harness's, which keeps a set of its own because it is a
  build of its own. `gradlew` is committed executable in both, and has to be: the root build starts
  the harness's by path.
- Two repositories, each asked only for what it serves. Maven Central carries everything but the
  JitPack-built libraries. JitPack is held by `exclusiveContent` to the three groups this build
  pins - `com.github.simplified-dev`, `com.github.simplified-api` and
  `com.github.minecraft-library` - and those groups are looked up nowhere else. The filter is a
  list of whole group names and not a `com.github.*` pattern, because Maven Central has groups
  under that prefix too, Caffeine's among them, and a pattern would send those to JitPack. The
  block is written twice, in `build.gradle.kts` and in `tooling/build.gradle.kts`, so a dependency
  from a fourth organisation is a line in both.
- The `constraints` block in `build.gradle.kts` holds floors under modules this build never
  names: transitives the JitPack-built libraries bring at a version with published advisories,
  each raised to the first release of its line that has none. A constraint raises a module only
  while something else brings it, so a floor the pins have moved past does nothing and can be
  deleted. They are declared on `api`, which is what writes them into the module metadata the
  library is published with, so a Gradle consumer that reads that metadata resolves the floors
  with the library. A Maven consumer does not: a POM carries them as `dependencyManagement`, and
  Maven does not apply a dependency's management section to the project that depends on it. To
  re-check the set, resolve `runtimeClasspath`, look each module up in an advisory database, and
  ask `./gradlew dependencyInsight --dependency <name> --configuration runtimeClasspath` which
  library brings the one that answers.
- `gradle/verification-metadata.xml` holds a SHA-256 for every jar the build resolves from Maven
  Central and the plugin portal, and Gradle refuses one whose bytes differ. Two things are outside
  it on purpose. The three JitPack groups are trusted by name and carry no checksum: JitPack builds
  what it serves and can build it again, so the bytes behind a fixed version are not promised to
  stay the same, and a checksum there would fail every build on the day one was rebuilt. And
  `verify-metadata` is off, so POM and module files are not checksummed. Gradle reads a parent POM
  or an imported BOM once and answers from its own cache afterwards, so a file written by a
  checkout that has built before leaves those out, passes there, and fails on the first clean
  clone - measured, 26 files short. A jar is hashed every time it is resolved, so the jar list
  comes out complete wherever it is written, and a jar nothing recorded is refused whatever
  metadata named it. Source and javadoc jars are trusted by file name, since an IDE import
  downloads them beside the jars the build uses. Any version that moves - a pin, a catalogue
  entry, a floor, the plugin - owes the file a regeneration:
  `./gradlew --write-verification-metadata sha256 help` resolves every configuration of both
  projects and adds what is new. It never removes an entry, so the ones for versions no longer
  resolved are deleted by hand. The harness is a build of its own and reads no verification file.
