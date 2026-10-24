# asset-renderer tooling

The generator flows: ASM walks over the extracted Minecraft client jar that produce the shipped JSON
tables the renderer loads at runtime. The `:tooling` subproject of the renderer build. Package root
`lib.minecraft.renderer.tooling.**`. Re-run on an MC version bump.

## Where the rest is written down

The rules are under `docs/tooling/`, one topic a file; read the one that covers what you are
touching before you touch it. The renderer's own are routed from
[asset-renderer/CLAUDE.md](../CLAUDE.md).

| Working on | Read |
|---|---|
| Where a table's tokens come from - `PoseChannel`, `PoseOperator`, `PoseExpr.Answered`, `BoneFlag` - and `PoseFold`'s flag carrier | [Vocabulary](../docs/tooling/Vocabulary.md) |
| `entity_poses.json` - the `shared` graph, `PoseFold`'s frame, a collector over a pose, `PoseStates`, split keys, the poser a body or a baby names | [PoseTable](../docs/tooling/PoseTable.md) |
| `PoseWalk` - the fork machinery and `EnumConstantTable`, `Held`, the model root, `<init>`, `RenderTransformWalk`, a container write, a carried figure, `Vec3`, static tables | [PoseWalk](../docs/tooling/PoseWalk.md) |
| `block_defaults.json`, `EntityIndexes`, a texture scroll, a layer alpha, a stride's pace | [Flows](../docs/tooling/Flows.md) |
| The bone order `entity_geometry.json` and `block_geometry.json` are written in - `PartTree`, `GeometryFlow.bakeOrder` | [BakeOrder](../docs/tooling/BakeOrder.md) |
| `rest_defaults`, `input_defaults`, `question_defaults`; equipment and carried-block folds; `ageScale` | [RestingSubject](../docs/tooling/RestingSubject.md) |
| `EntityMeshOverlays`, `GeometryIds`, `EntityOverlayResolver`'s `retain_bones`, a geometry key, `GeometryManifest` | [MeshDerivation](../docs/tooling/MeshDerivation.md) |
| `policy/` - `NavigationPolicy`, the `*Policies` enums, `Trace`, `PolicyPurityTest` | [PolicySpi](../docs/tooling/PolicySpi.md) |
| A jar pin failing after a bump - what holds the geometry table to the jar | [Gates](../docs/tooling/Gates.md) |

## Build

`tooling/build.gradle.kts` takes `project(":")` on `implementation`, so the renderer's production
types resolve here against the working tree. **Reach for one rather than re-declaring it.** A type
this build spells for itself is a type that drifts from the renderer's, and the four that did -
`PoseOperator`, `VanillaMth`, `VanillaEase` and the diagnostics sink - were identical on every code
line while their javadoc explained why they could not be. Client-jar acquisition comes with it, at
`lib.minecraft.renderer.content.client`. Which renderer rosters a shipped table is then written in
is [Vocabulary](../docs/tooling/Vocabulary.md).

The direction is one-way and has to be: a composite substitutes an INCLUDED build into its root and
never the reverse, so a generator depending on the renderer resolves only inside one build. That is
why this is a subproject. ASM is declared here alone and `:tooling` is taken by nobody, so it is on
the renderer's classpath nowhere and in no published JAR.

**The packages here are ordered, and `guard/TierOrderTest` holds the order** in a table of its own
beside the renderer's, under the same strictly-downhill rule: `exception` and `names` < `asm` <
`run` < `interp` < `walk` < `policy` < `geometry` < `index` < `animation` < the corpora (`entity`,
`blockentity`, `colormap` and `item`, with `block` above `item`) < the flows at the root, and the
suite's `gate` above them all. A generator still names the renderer up to the content index and
nothing above it. When a lower package needs something a higher one holds, the member moves down
rather than the import running uphill - which is why `ToolingException` has a leaf of its own, and
why the lambda and branch predicates sit on `Insn` and the concat placeholder on `ClassKit`.

`../parity` is the five `@Parity` annotation types, taken **`compileOnly` on both source sets**.
Retention is `SOURCE`, so javac drops the descriptor before it writes a class file: nothing here can
read a declaration at run time and no emitted table is a function of one. Both packages' claims are
declared in their own `package-info.java` rather than as a glob in the renderer's blindness map, so
moving one moves the rule.

The eight flows keep their names in the renderer's own `tooling` group as aliases onto this
project's tasks, so `./gradlew blockTints` works from either side and the names are what the parity
artifact table lists as `manifest.tooling-tables`' producers - it resolves each with `named` on the
root project.

- `./gradlew generateTables` runs every flow; `-Pflows=blockTints,glintItems` runs a subset and
  refuses a name that is not one of the eight.
- `-PtoolingOut=<dir>` redirects where every flow writes. It defaults to the renderer's
  `src/main/resources/lib/minecraft/renderer/`, which is what makes a run dirty tracked files - that
  is the signal, not a problem. Point it at a scratch directory to take an A/B without touching the
  tree, and note that a parity capture over a redirected run gates unregenerated bytes.
- Every path a flow resolves is relative to the RENDERER root, not this build: the tasks pin
  `workingDir` there so a path typed in Java, in the renderer's build file and in this one all name
  the same directory.
- `TableEnvelope.mint` builds both header segments from its one `flow` argument, so renaming a flow
  rewrites every table's header.

## Walks

- **Every instruction walk here is an `AsmWalker` chain** - the one hand-written instruction loop
  left is `EntityGeometryRefResolver.collectBakedModelLayers`, whose body is bake-triple consumer
  accounting rather than a fold: a constructor consuming two or more models takes the last n fresh
  triples in argument order and always empties the fresh buffer, even when it held fewer than n, and
  there is no chain token for that. The walker is a reusable descriptor: sources
  `over`/`clinit`/`from`/`after`/`before`, geometry `real()`/`until`/`limit`, match stages that
  narrow, fold stages (`gather`/`latch` + `commitAt`) that replace the old `pending*` locals, and
  eager terminals; branch-following is `trace` with an always-on cycle guard; three of the four
  bytecode interpreters ride one `Interpreter<V>` chassis, and `GeometryParser`'s stack/slot half
  stays site-owned pending the same machine-view tokens. Do NOT reintroduce a
  `for (AbstractInsnNode ...)` loop - the engine's cascade rules (claiming, commit-before-reset,
  strict-adjacency) are pinned by the `walk` test suite, and a hand loop silently re-derives them.
  One-hop neighbour reads (`AsmWalker.nextReal`/`previousReal`) are expressions, not walks, and stay
  statics. `EntityGeometryRefResolverTest` reaches the declined member by reflection under its own
  name, so renaming it compiles clean and fails at runtime.

## Gates

`./gradlew :tooling:test` is this project's whole suite, and there is no second one. Most of it is
hand-built ASM nodes, a `ZipOutputStream` jar under `@TempDir` and reflection over the tooling
classes; nine walks read the real client jar - `EnumConstantTableTest`,
`KeyframeDefinitionParserTest`, `PosePartIndexTest`, `PoseWalkTest`, `GeometryParserTest`,
`BoneRestRadianTest`, `GeometryBakeOrderTest`, `EntityPaceJarTest` and `ClockRosterJarTest`. The
renderer's `check` schedules it as `toolingTest`, which is the name it had when this was a build of
its own.

**A walk reads the jar the cache already holds and never downloads one.** Each gates on
`ClientOptions.defaults().vanillaRoot()` holding a `client.jar` and abandons its class where nothing
has cached one, so the suite reaches no network and costs a stat rather than 25MB.
`ClientJarGuardTest` is the one test that FAILS on an absent jar, because nine classes assuming
away in silence is a suite reporting green over what it did not run.

Qualify the project when filtering. A bare `--tests` applies to EVERY `Test` task, so a pattern
naming only tooling classes fails on the renderer's own `test` and the other way round.

Every parity gate in the renderer reads the **shipped** JSON, which a refactor here does not
regenerate, so a green gate is no evidence about a change in this build. Re-run the flow and compare
emitted bytes against a capture from the clean tree taken before the first edit - `-PtoolingOut` is
what makes that non-destructive. Diff the diagnostics log too: a byte-identical table is not an
unchanged run. The log names each table it wrote by file name alone, so a redirected run's log
compares line for line with one written into the tree.

**Diff the emitted bytes MEMBER BY MEMBER, not just for equality**, and do it before the commit
rather than after the suite. A pass here edits a shared tree in place, so its blast radius is every
member of every node it walks and both suites stay green over the parts it should not have touched.
Two shapes this has already caught, neither of which any test would have:

- **A strip that runs where it was not wanted.** A pass taking members off the node it derived from
  took the same members off every node it merely VISITED, and an armour row's `grow` - the two layer
  deformations, nothing to do with a pass's inflate - went with them on fourteen subjects. A
  surgery's own members are what it may remove, and only on a node it is actually deriving.
- **Identity that is not identity.** `JsonTree` wraps a fresh object per access, so tracking nodes in
  an `IdentityHashMap` or a `Collections.newSetFromMap` silently never matches and every membership
  test answers false. Discriminate on what a node CARRIES, never on which object came back.

`PolicyPurityTest`, `GeometryRefClosureTest`, `GeometryParserTest`, `BoneRestRadianTest`,
`PosePartIndexTest`, `PoseWalkTest`, `PoseFlowEmitTest`, `GeometryFlowPartRestsTest` and
`EntityPaceJarTest` hold filesystem paths relative to the renderer root. A directory move breaks
them at runtime rather than at compile time.
