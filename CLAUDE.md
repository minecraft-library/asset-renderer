# asset-renderer

Headless renderer for Minecraft blocks, items, entities, fluids and portals, answering a
`RenderResult` - the `ImageData` drawn (static PNG or animated frames) and every stand-in drawn in it -
via `Renderer<O>`. Group `lib.minecraft`, root `lib.minecraft.renderer.**`.

Gate questions go through the `parity-gate` skill, `.claude/skills/parity-gate/SKILL.md` - except a
`tooling/**` change, which goes through `tooling-flow-gate` first, because every artifact the parity
store holds is blind to one.

## Where the rest is written down

This file is orientation: the build, the gates, and where to look. The rules live under `docs/`, one
topic a file, and `tooling/`, `harness/` and `parity/` each keep a `CLAUDE.md` of their own that
loads when you work there and routes to their part of `docs/`. **Read the file that covers what you
are touching before you touch it** - none of them is loaded for you. A topic file that settles
refusals closes on its own *Decisions that stay closed*, where refused changes and accepted gaps
share a shape and are listed together; a refusal whose stated mechanism is not in the code is void -
check it against source before honouring one.

| Touching | Read |
|---|---|
| `call/**` - the `*Options` bags, `RenderResult` and the composite results, `LayerSlot`; laying a finished render down (`BlendMode`); `ItemContext` and an item definition's walk; the `vanilla/appearance` vocabulary | [RequestsAndResults](docs/renderer/RequestsAndResults.md) |
| `content/read` and `filter.block`; the `content/json` value adapters; a pack's text shader, `TextPaletteLoader` and `TextPalette`; a texture sidecar's sections, `MCMetaParser` | [PackContent](docs/renderer/PackContent.md) |
| A lookup that can miss - the checkerboard, `Substitution`, the missing model, `MissingMesh`, `-PhideTextures` | [MissingTextures](docs/renderer/MissingTextures.md) |
| `Flipbook` and `.mcmeta` animation, `Timeline` and `SubTickLoop`, the `minecraft:time` clock | [Timelines](docs/renderer/Timelines.md) |
| `engine/geometry` - `Face`, `CornerPhase`, `Unwrap`, `AxisSigns`, `Box`, `EulerRotation`; `BoxKit`, `BoneKit`, `HumanoidPart`; JOML factories; the order corners round in | [Geometry](docs/renderer/Geometry.md) |
| `Projection`; `engine/light` - `LightingFrame`, `Shading`, the lighting fold; `Placement`; `Rasterizer.prepareFit`; the entity canvas and its family-fit pre-pass; a bounds walk; a canvas sized to its subject, a tooltip's and chat text's included | [ProjectionAndFit](docs/renderer/ProjectionAndFit.md) |
| Depth in `engine/raster` - `DepthMath`, the snapped depth plane, coplanar ties, draw order, the sort, the fill rule | [Depth](docs/renderer/Depth.md) |
| The texel fetch - `RasterMath.texelOf`, `faceTexel`, `lastTexel`, a pass's wrap; the corner snap's grid and tie rule; the GPU's own coordinate | [TexelFetch](docs/renderer/TexelFetch.md), [TexCoordProbe](docs/renderer/TexCoordProbe.md) |
| `bake/armor` - `Shell`, `ShellIndex`, the armour kits, `WornBox`, the wings; `ArmorSlot` and `ArmorForm` | [Armour](docs/renderer/Armour.md) |
| How `entity_models.json` loads - `EntityIndexBuilder`, axes, overlays, carried blocks and the poses forms swap in; porting a new entity | [EntityModelForm](docs/renderer/EntityModelForm.md) |
| Posing: style rows, `PosePlayer` writing a row onto a mesh, pose keys | [pose/Catalog](docs/renderer/pose/Catalog.md) |
| Posing: `setupRotations` steps, the container and `$container`, the `root` clip channel, `EntityMeshFacing`, `EntityMeshShift` | [pose/Container](docs/renderer/pose/Container.md) |
| Posing: what a frame answers - `PoseStyle.frameAt`, `StyleDriver`, selection groups, flags, the two clocks | [pose/Drivers](docs/renderer/pose/Drivers.md) |
| Posing: `PoseStrips`, `StripFit`, the `idle/` and `walk/` references, a pass's opacity, the posed canvas | [pose/Strips](docs/renderer/pose/Strips.md) |
| Posing: `author/**`, `PoseCompiler`, the `PoseOperator` roster | [pose/Authoring](docs/renderer/pose/Authoring.md) |
| A block item's icon, how a slot draws an item, `SpecialTransform`, the block index's first-variant pick | [Blocks](docs/renderer/Blocks.md) |
| `ScreenMetrics`, `MenuLayout`, `MenuRenderer`, `Window`, marks, `bake.gui` | [Menus](docs/renderer/Menus.md) |
| A parity row disagreeing with its reference | [Debugging](docs/renderer/Debugging.md); probe traps and worked examples: `.claude/skills/parity-gate/references/diagnostics.md` |
| A `@Parity` declaration; a `blindness.json` rule; `ParityArtifacts` and `index.json`; renaming a class, heading or file the store cites | [DeclaringReach](docs/parity/DeclaringReach.md), [ReferenceGraph](docs/parity/ReferenceGraph.md), [RosterAndIndex](docs/parity/RosterAndIndex.md), [StoreState](docs/parity/StoreState.md) |
| `build.gradle.kts` and `gradle/*.gradle.kts`, the source sets, the Vector flag, ASM | [BuildScripts](docs/project/BuildScripts.md) |
| Which suite a test belongs in - the cache extensions, the slow tag | [TestSuites](docs/project/TestSuites.md) |
| `content/client`; the exception hierarchy - `ClientException`, `ToolingException` | [ClientAcquisition](docs/project/ClientAcquisition.md) |
| `./gradlew jmh`; the scripts under `src/test/resources/scripts/` | [Jmh](docs/project/Jmh.md), [DeveloperScripts](docs/project/DeveloperScripts.md) |
| `tooling/**`, `harness/**`, `parity/**` | [tooling/CLAUDE.md], [harness/CLAUDE.md], [parity/CLAUDE.md] - each loads on entry and routes to its part of `docs/` |
| Running a gate - tasks, flags, refusals, runbooks | `.claude/skills/parity-gate/SKILL.md` |
| An open question nobody owns | [KnownOpen](docs/KnownOpen.md) |

`guard/TierOrderTest` holds the package order: a package imports only what sits below it in the
tiers the test's tables declare, and its `KNOWN` ledger is the exact list of edges that still break
that order.

Durable rules belong in one of those files. The measurements and narratives that produced them
belong in the commit that landed them, and in the reason recorded with the baseline they moved. A
rule that is really a question goes in [KnownOpen](docs/KnownOpen.md) instead, because a question
filed as a rule reads as settled. A new topic file takes a ProperCase name, opens on a line saying
when to load it and which `CLAUDE.md` indexes it, and is linked from that file.

## Build

- `build.gradle.kts` keeps what every task needs - the toolchain, the vector flag, the asset-property
  forwarding, the dependencies - and applies three scripts from `gradle/` for the rest:
  `tooling.gradle.kts` (the flow shims), `visual.gradle.kts` (the render drivers) and
  `parity.gradle.kts` (the store, the harness runs, the capture steps). They are applied at the END
  of the root script and in that order, because each resolves tasks by name and `named` answers only
  for one already registered.
- JDK 21 with the **Vector API incubator** (`--add-modules=jdk.incubator.vector`); a JVM launch
  missing the flag is a **silent scalar fallback**, not a class-not-found.
- `:tooling` is a SUBPROJECT of this build, not a build beside it, and that is what lets the
  generators resolve this project's production types instead of re-declaring them. The direction is
  one-way - `:tooling` takes `project(":")` on `implementation` - so nothing it declares comes back.
- Two builds sit beside this one: `parity`, which it includes, and the harness, which it reaches by
  shelling into that wrapper. `parity` stays a build of its own because the harness includes it and
  an included build has to be standalone.
- `parity/` is the smallest leaf - five annotation types and the toolkit's Python package. Every
  build that writes a declaration takes it **`compileOnly`**; see [parity/CLAUDE.md].
- JitPack dependencies are `strictly()`-pinned inline in `build.gradle.kts`; bump by editing the
  version string. `./gradlew dependencies` for the live set.

What an applied script cannot see, which task types carry the Vector flag and why `javadoc` stays
red either way, ASM, the source sets and which way the dependency runs are
[BuildScripts](docs/project/BuildScripts.md). Client acquisition - `content.client`, the one place
in the repo that touches the network - and the refusal that keeps its exception off the renderer's
root are [ClientAcquisition](docs/project/ClientAcquisition.md).

## Gates

`./gradlew test` is the fast suite, excluding `@Tag("slow")`. `./gradlew slowTest` selects the tag
and is never up-to-date-cached.

**What the tag separates is the NETWORK, not the cache.** The fast suite reads the client assets as
a matter of course: a test that needs them installs `ClientAssetsExtension`, which reads the vanilla
pack out of the client jar cached at the root `ClientOptions` itself defaults to, once per JVM, and
ABANDONS the class where no jar is cached - so a fast run cannot download. A class that renders text
installs `MinecraftFontsExtension` the same way, since the text library's own fallback reaches the
network. `ClientJarGuardTest` and `FontCacheGuardTest` are the tests that FAIL on an absent client
jar or font cache, so a suite thinned by assumption says so once rather than reporting green over
coverage it skipped; nothing fails on an absent pack. The five classes that keep the tag, and the
packs a fast test may read, are [TestSuites](docs/project/TestSuites.md).

A `--tests` filter applies to EVERY `Test` task, so an unqualified one that names only renderer
classes fails on `:tooling:test` with `No tests found for given includes`. Write `:test --tests` when
filtering.

`./gradlew check` is `test` plus four gates `test` does not reach: `paritySelfTest`, the parity
toolkit's own suite, which otherwise runs only when a parity task pulls it in; `harnessClasses`,
which compiles the harness through its own wrapper and otherwise runs only when it is asked for by
name; `toolingTest`, which is `:tooling:test` under the name it had when the generators were a
build of their own; and `parityReachCheck`, which holds the committed reach graph,
`parity/reach.json`, to the compiled tree. The harness is a separate Gradle build, so `test` passes
over one that does not compile and the next thing that would catch it is a client boot; the four
gates together cost seconds.

**Gate once per phase, immediately before the commit, and never re-baseline.** The `parity-gate`
skill runs it: `parityPlan` names what the change reaches and what is blind to it, `parityCapture`
writes a capture, `parityCompare` reports movers, `parityPromote` makes a capture the baseline. The
declaration mechanics behind it - how reach is resolved, what coining an artifact owes - are in
[DeclaringReach](docs/parity/DeclaringReach.md), [ReferenceGraph](docs/parity/ReferenceGraph.md) and
[RosterAndIndex](docs/parity/RosterAndIndex.md).

- Determinism is the precondition for a hash. Prove a producer reproduces across fresh JVMs before
  comparing digests: `Set.of`, `Map.of` and their `copyOf` forms iterate in an order salted per JVM,
  as does a hash keyed by identity, so one run cannot show it.
- Look at a render for a change meant to move pixels; hash one for a change meant to move none.
- What a value reaches is provable: perturb it, re-render, and the outputs that move name the reach.
- Scope an already-red task's output to the package you touched and compare; never read its exit
  code.
- `BlockGeometryKitTest` builds fixtures by reflection into private parser-populated fields, so a
  rename compiles clean and fails at runtime.

**Two renames are a promote rather than a rename**, and both bite before you are anywhere near the
gate: a test or visual-set class the store homes a row at, and a heading a reach rule cites - of
this file or of a topic file under `docs/` - or the path of the file that holds it.
[StoreState](docs/parity/StoreState.md) lists which files those are, the five that pin a line number
as well, and what an edit to one owes; grep the store for the class or the heading before touching
it.

Task inventories: `./gradlew tasks --group visual`, `--group tooling`, `--group parity`, `--group
build`. The last holds `generateAtlas`, a worked example of driving a renderer rather than a
resource-regenerator, which is why it is not in `tooling`.

## Tooling

The generators are the `:tooling` subproject at `tooling/`. Internals live in [tooling/CLAUDE.md].
The eight flows keep their names in this project's `tooling` group as aliases onto `:tooling`'s own
tasks, which is what keeps the parity artifact table's producer list resolving - it looks each up
with `named` on THIS project.

- **A renderer change can now move an emitted table**, which it structurally could not while the
  generators re-declared what they needed. `tooling-flow-gate` is the measurement either way, and it
  is owed by a change on this side too rather than only by one under `tooling/`.
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
byte-stable ground truth the parity sweeps diff against, one sub-tree each under
`cache/asset-renderer/vanilla/<mc>/references/`. Internals live in
[harness/CLAUDE.md]. The Java side walks vanilla model bytecode into
`entity_models.json` and `entity_geometry.json`.

- The sweeps are diagnostic reports, not pass/fail gates; one becomes a gate only when its table is
  compared against a baseline.
- **The player and armour sweeps rescale both sides before diffing, so their delta is a LOOK gauge.**
  Their raw renders are the byte gate: `vanilla.png` / `java.png` are what the renderers produced,
  and `aligned_*.png` is the resample the delta, the diff and the panel come from.
- **`renderVanillaAllReferences` writes the whole tree in ONE boot**, every sweep, `idle/`
  and `walk/` included. Every narrower run still leaves the sub-trees it does not name exactly as
  it found them.
- Re-rendering refreshes ground truth and does not fix a regression, and only
  `renderVanillaAllReferences` refreshes the whole tree.
- One pack's tooltip drawings are ground truth beside the tree, not in it: the pack-tooltip run
  draws them, the whole-tree boot does not, and a capture of their gate re-runs it.
- A reference that moves on a re-render with your change stashed was stale, not moved.

Why the boot orders its sweeps, the two posed sub-trees, and the roster they are rendered at are
[SweepArchitecture](docs/harness/SweepArchitecture.md).

Capture, compare and promote go through the `parity-gate` skill; the re-render runbook is its
`references/procedures.md`.

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

What a review greps for to hold both halves, and what survives the grep, is
[ReviewGreps](docs/ReviewGreps.md).

The one exemption is a frozen measurement already promoted into the store, which moves by
re-promoting and never by an edit - see [RosterAndIndex](docs/parity/RosterAndIndex.md). The next
reason written still follows the rule.

[harness]: harness
[harness/CLAUDE.md]: harness/CLAUDE.md
[parity/CLAUDE.md]: parity/CLAUDE.md
[tooling/CLAUDE.md]: tooling/CLAUDE.md
