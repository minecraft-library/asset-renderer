# asset-renderer

Headless renderer for Minecraft blocks, items, entities, fluids and portals, outputting `ImageData`
(static PNG or animated frames) via `Renderer<O>`. Group `lib.minecraft`, root
`lib.minecraft.renderer.**`.

Gate questions go through the `parity-gate` skill, `.claude/skills/parity-gate/SKILL.md` - except a
`tooling/**` change, which goes through `tooling-flow-gate` first, because every artifact the parity
store holds is blind to one.

## Where the rest is written down

This file is orientation: the build, the gates, and where to look. The rules themselves live beside
the code they bind, so **read the one that covers what you are touching before you touch it** - none
of them is loaded for you.

| Working on | Read |
|---|---|
| `src/main/java/**` - geometry, depth, armour, entities, menus, options | [RENDERER-RULES.md] |
| `tooling/**` - the generator flows and the tables they emit | [tooling/CLAUDE.md] |
| `harness/**` - the vanilla client that makes the ground truth | [harness/CLAUDE.md] |
| `parity/**` - reach declarations, the store's roster and index | [parity/CLAUDE.md] |
| Running a gate - tasks, flags, refusals, runbooks | `.claude/skills/parity-gate/SKILL.md` |
| Diagnosing a mover - probe traps, worked examples, version rosters | `.claude/skills/parity-gate/references/diagnostics.md` |
| An open question nobody owns | `KNOWN-OPEN.md` |

Durable rules belong in one of those files. The measurements and narratives that produced them belong
in the commit that landed them, and in the reason recorded with the baseline they moved. A rule that
is really a question goes in `KNOWN-OPEN.md` instead, because a question filed as a rule reads as
settled.

## Build

- `build.gradle.kts` keeps what every task needs - the toolchain, the vector flag, the asset-property
  forwarding, the dependencies - and applies three scripts from `gradle/` for the rest:
  `tooling.gradle.kts` (the flow shims), `visual.gradle.kts` (the render drivers) and
  `parity.gradle.kts` (the store, the harness runs, the capture steps). They are applied at the END
  of the root script and in that order, because each resolves tasks by name and `named` answers only
  for one already registered.
  - An applied script sees no declaration of the root's and gets no type-safe accessors. Both are why
    `val sourceSets = the<SourceSetContainer>()` opens two of them, and why the one value they need
    from the root - the resolved `-Dasset.*` set - crosses as `extra["assetFlagsInForce"]` rather
    than as the function that computes it.
  - The guards read all four as one text through `BuildScripts.all()`, so what they pin is what the
    build declares rather than which file declares it.
- JDK 21 with the **Vector API incubator** (`--add-modules=jdk.incubator.vector`), wired into
  JavaCompile, Test, JavaExec, JMH and Javadoc in `build.gradle.kts`. Missing it on a JVM launch is a
  class-not-found at load, never a silent fallback; missing it on `javadoc` is `SimdOps` reporting
  the package as not visible. `javadoc` stays red either way - every error it has left is a builder
  an annotation processor produces and the doclet cannot see - so it is wired because the flag
  belongs everywhere it is read, not because the task becomes usable.
- ASM 9.8 reads Java 25 class files; the tooling flows walk client-jar bytecode with it. It is
  declared in the tooling build alone, so it is on no renderer classpath and in no published JAR.
- Four builds sit beside this one: `client`, `tooling` and `parity`, which it includes, and the
  harness, which it reaches by shelling into that wrapper, as it does the generator flows.
- `client/` is a leaf holding client-jar acquisition - `ClientAcquisition`, `ClientOptions`,
  `ClientAssets`, `VanillaSourcePaths` - under `lib.minecraft.renderer.client`. Both this build and
  the generators read it and it reads neither. It is the one place in the repo that touches the
  network, and it raises `ClientException` off `RuntimeException` rather than `RendererException`, so
  a batch renderer's skip-and-continue cannot swallow a client that failed to acquire.
- `parity/` is the smallest leaf - five annotation types and the toolkit's Python package. Every
  build that writes a declaration takes it **`compileOnly`**; see [parity/CLAUDE.md].
- JitPack dependencies are `strictly()`-pinned inline in `build.gradle.kts`; bump by editing the
  version string. `./gradlew dependencies` for the live set.

## Gates

`./gradlew test` is the fast suite, excluding `@Tag("slow")`. `./gradlew slowTest` hits the network
and the filesystem cache and is never up-to-date-cached.

`./gradlew check` is `test` plus three gates `test` does not reach: `paritySelfTest`, the parity
toolkit's own suite, which otherwise runs only when a parity task pulls it in; `harnessClasses`,
which compiles the harness through its own wrapper and otherwise runs only when it is asked for by
name; and `toolingTest`, which runs the tooling build's own suite through its wrapper for the same
reason. Both the harness and the tooling flows are separate Gradle builds, so `test` passes over one
that does not compile and the next thing that would catch it is a client boot; the three gates
together cost seconds.

**Gate once per phase, immediately before the commit, and never re-baseline.** The `parity-gate`
skill runs it: `parityPlan` names what the change reaches and what is blind to it, `parityCapture`
writes a capture, `parityCompare` reports movers, `parityPromote` makes a capture the baseline. The
declaration mechanics behind it - how reach is resolved, what coining an artifact owes - are in
[parity/CLAUDE.md].

- Determinism is the precondition for a hash. Prove a producer reproduces before comparing digests;
  `build/atlas/atlas.png` fails that by design.
- Look at a render for a change meant to move pixels; hash one for a change meant to move none.
- What a value reaches is provable: perturb it, re-render, and the outputs that move name the reach.
- Scope an already-red task's output to the package you touched and compare; never read its exit
  code.
- `BlockGeometryKitTest` builds fixtures by reflection into private parser-populated fields, so a
  rename compiles clean and fails at runtime.

**Two renames are a promote rather than a rename**, and both bite before you are anywhere near the
gate: a test class the store homes a row at, and a heading of `RENDERER-RULES.md` a reach rule cites.
[parity/CLAUDE.md] lists which files those are and what an edit to one owes; grep the store for the
class or the heading before touching it.

Task inventories: `./gradlew tasks --group visual`, `--group tooling`, `--group parity`, `--group
build`. The last holds `generateAtlas`, a worked example of driving a renderer rather than a
resource-regenerator, which is why it is not in `tooling`.

## Tooling

The generators are their own Gradle build at `tooling/`, a sibling of the harness with its own
wrapper. Internals live in [tooling/CLAUDE.md]. The renderer drives the eight flows by shelling into
that wrapper under the same task names, which is what keeps the parity artifact table's producer
list resolving.

- Nothing here names a tooling type; the generators read `client` and this build reads them not at
  all. ASM is declared over there alone and is on no renderer classpath and in no published JAR.
- A flow writes to `src/main/resources/lib/minecraft/renderer/` by default and dirties tracked files -
  that is the signal. `-PtoolingOut=<dir>` redirects the whole set, which is how an A/B is taken
  without touching the tree.
- Every gate here reads the **shipped** JSON, which a generator refactor does not regenerate, so a
  green gate is no evidence about a tooling change. Re-run the flow and compare emitted bytes against
  a capture from the clean tree taken before the first edit. Diff the diagnostics log too: a
  byte-identical table is not an unchanged run - and sort both sides, because log line order follows
  task invocation order and an unsorted diff reports every line as moved. The `tooling-flow-gate`
  skill runs that loop and owns the traps.

## Parity: the harness contract

The [harness] is `harness/`, its own Gradle build with its own `gradlew`. It
renders every subject through the real Minecraft client at a locked iso pose; those PNGs are the
byte-stable ground truth the nine sweeps diff against, one sub-tree each under
`cache/asset-renderer/vanilla/<mc>/references/`. Internals live in
[harness/CLAUDE.md]. The Java side walks vanilla model bytecode into
`entity_models.json` and `entity_geometry.json`.

- The sweeps are diagnostic reports, not pass/fail gates; one becomes a gate only when its table is
  compared against a baseline.
- **The player and armour sweeps rescale both sides before diffing, so their delta is a LOOK gauge.**
  Their raw renders are the byte gate: `vanilla.png` / `java.png` are what the renderers produced,
  and `aligned_*.png` is the resample the delta, the diff and the panel come from.
- **`renderVanillaAllReferences` writes the whole tree in ONE boot**, all nine sweeps, `idle/`
  and `walk/` included. A freeze is armed per sweep off `PoseState` rather than read once per JVM, and
  `HarnessMode.resolve` orders a run `BIND` before `IDLE` before `WALK` - which is a correctness
  requirement, not tidiness: a freeze SKIPS `setupAnim` rather than undoing it, so a posed sweep ahead
  of a frozen one would leave every bone it touched posed and the frozen sub-trees would record that.
  Every narrower run still leaves the sub-trees it does not name exactly as it found them.
- **The two posed sub-trees are one sweep at two gaits**, `-Drefharness.walking=true` selecting the
  second, and the asset side is one driver at `asset.parity.gait=walk`. Each names its own gait -
  `idle/` and `walk/` on the harness side, `idle` and `walk` on the property - so the two sides map a
  sub-tree by one spelling. Neither doubles: what a gait changes is two render-state fields, not a
  work list.
- Re-rendering refreshes ground truth and does not fix a regression, and only
  `renderVanillaAllReferences` refreshes the whole tree.
- A reference that moves on a re-render with your change stashed was stale, not moved.

Capture, compare and promote go through the `parity-gate` skill; the re-render runbook is its
`references/procedures.md`.

## JMH

`./gradlew jmh` with `-PjmhWarmup` (3), `-PjmhIters` (5), `-PjmhForks` (2), `-PjmhInclude=<regex>`
and `-PjmhProfilers=gc,stack`. Forks get `-Xmx2g` plus the Vector module. Benches live in
`src/jmh/java/lib/minecraft/renderer/bench/`.

## Skip these

- `cache/` - texture packs, render output, harness ground truth.
- `texturepacks/` - the same.
- `build/` - Gradle output.
- `.jmh/` - captured JMH session output.
- `notes/` - gitignored working notes: research packs, ledgers, probe tables. Read one when picking
  up a live effort; nothing downstream reads them.

**Do not cite a `notes/` path from a tracked file, and do not cite a working note's entry by
number.** The directory is gitignored by decision, so a citation from a tracked file resolves for
nobody who clones this, and an entry number - the `L<n>`, `Q-<n>`, `I-<n>` and phase-`P` spellings the
notes here have used - names that same unresolvable place with fewer characters. Inline what the note
establishes; state the measurement rather than where it was written down. The rule binds what is
authored - prose, comments, javadoc, a diagnostic or an exception message, commit text - and the
shapes above are written as shapes, so this paragraph names no entry a reader could go looking for.

Only the path half is greppable: `git ls-files | xargs grep -l 'notes/'` at review, deliberately not
in the pre-commit hook. What survives that grep is the `notes/**` blindness glob, in the map and in
the skill's rendered view of it; the test exempting that glob from the assertion that every
`no_reach` glob matches a tracked path; the ignore rule that makes the directory gitignored; this
section; the skill's list of commit kinds that skip the gate; and the two toolkit tests asserting
that `notes/parity` is refused as a working root. Telling those from a citation is a reading rather
than a pattern. The entry-number half has no one pattern to grep for, a note's ids being whatever
that note chose, so it is read rather than matched. An id a tracked file defines itself is not a
citation at all: the reach rules carry their own `B<n>` in an `id` member.

The phase-number spelling has a review grep of its own, and the tree is clean of what it forbids:
`git ls-files | xargs grep -nE '\bP-?[0-9]{1,2}\b' | grep -vE 'P[0-9]+[a-zA-Z]'` answers today with
the promoted artifacts, whose recorded reason is the exemption below, and the two Gradle wrapper
jars, which answer as binaries.

The one exemption is a frozen measurement already promoted into the store, which moves by
re-promoting and never by an edit - see [parity/CLAUDE.md]. The next reason written still follows the
rule.

## Developer scripts

`parity/scripts/parity/` is the parity toolkit, run as `python parity/scripts/parity <command>` and
documented in its own `README.md`. Every other developer script lives in
`src/test/resources/scripts/`, which `processTestResources` excludes whole, so one is neither a
fixture nor a shipped resource - `euler_reference_svg.py` regenerates the SVG in `EulerRotation`'s
javadoc and is the only one today.

[RENDERER-RULES.md]: RENDERER-RULES.md
[parity/CLAUDE.md]: parity/CLAUDE.md
[harness]: harness
[harness/CLAUDE.md]: harness/CLAUDE.md
[tooling/CLAUDE.md]: tooling/CLAUDE.md
