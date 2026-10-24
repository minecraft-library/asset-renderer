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
