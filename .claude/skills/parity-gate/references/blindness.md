# Blindness map

Generated from `blindness.json`. **Do not edit** - regenerate with:

```
./gradlew test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true --rerun
```

**Decide from the JSON, explain from this file.** `parityPlan` resolves reach from
`blindness.json` directly; this rendering is for explaining a verdict to a human and
for checking a rule that is being questioned. Reasoning from the prose instead is the
judged reach resolution the map replaced, and it looks correct while being wrong.

Three modes, and the order they apply in is the whole of the arithmetic. `select`
contributes its `sees` to the union. `demote` contributes too and then removes its
`blind` set **after** the union is taken, which is the only way a rule can speak
about artifacts it does not itself select. `suppress` marks an artifact inadmissible
outright, whatever selected it.

## B2 - CIT and CTM rules are dark in both parityDump configurations

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/pack/rule/**`, `src/main/java/lib/minecraft/renderer/pipeline/**/RuleScanner*.java`, `src/main/java/lib/minecraft/renderer/pipeline/**/Ctm*.java`, `src/main/java/lib/minecraft/renderer/pipeline/**/Cit*.java`
- **sees** `digest.shipped-tables`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** 07/blindness#2; CLAUDE.md 'The pack filter'

No pack fixture ships a cit/ or ctm/ tree, so rules.json reports cit_rules: 0 and ctm_rules: 0 on both configs. An empty dump diff proves nothing about them; RuleScannerMergeTest and CtmParserTest are the gate and both run inside ./gradlew test.

*Probe:* read rules.json in either dump config and confirm both counts are 0

## B3 - The multipart when-OR branch is never exercised, because no shipped block produces one

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/**/BlockState*.java`, `src/main/java/lib/minecraft/renderer/pipeline/**/Multipart*.java`
- **sees** `sweep.block`, `manifest.dump.vanilla`
- **blind** -
- **source** 07/blindness#3

The vanilla blockstate corpus contains no multipart apply whose when carries an OR list, so the branch is present, compiled and unreached. A dump diff over blocks.json cannot distinguish a correct OR implementation from a broken one.

*Probe:* grep the shipped blockstate JSON for a when containing an OR key; zero hits means the branch is still unreached and the rule holds

## B4 - A vanilla-only dump leaves the pack-rule code dark, so the packs configuration is not optional

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/**`
- **sees** `manifest.dump.packs`, `sweep.block`, `sweep.item`
- **blind** `manifest.dump.vanilla`
- **source** 07/blindness#4

With no pack loaded the RuleSet is empty and most PackIdDeriver rungs never execute, so the vanilla dump section for rules is a fixed empty shape whatever the code does. Only the packs configuration puts a rule through the deriver at all.

*Probe:* run parityDump and compare rules.json between the two configs: the vanilla one is empty and the packs one is not

## B5 - A Pipeline.Result-level dump would clear a broken index loader, so the dump's altitude must be the renderer context

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/**`, `src/main/java/lib/minecraft/renderer/pipeline/loader/**`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.block`, `sweep.item`, `sweep.entity`
- **blind** -
- **source** 07/blindness#5

BlockIndexBuilder, ItemIndexBuilder and EntityIndexBuilder run between the loaders and the renderer context, so a dump taken before them serialises inputs that are identical whatever the builders did with them. The dump is taken after, which is what makes an index change visible.

*Probe:* PipelineParityDump builds a PipelineRendererContext before dumping; check the dump entry point resolves the index rather than the LoadResult

## B6 - The dump sees data rather than behaviour, so a resolution-logic change is not pinned by index identity

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/*Builder.java`, `src/main/java/lib/minecraft/renderer/asset/pack/rule/*Resolver*.java`
- **sees** `sweep.block`, `sweep.item`, `sweep.entity`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** 07/blindness#6

A resolver that answers the same for every shipped input and differently for an unshipped one leaves every dumped byte identical. The dump's probes.json exists for exactly this: it samples resolution outcomes rather than the table they were resolved from.

*Probe:* perturb a resolver on a branch no shipped id takes and confirm the dump is byte-identical while probes.json is not

## B7 - Intermediates are deliberately not dumped, so a LoadResult-shaped rework is dump-invisible whenever its outputs match

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/BlockStateLoader.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.block`
- **blind** -
- **source** 07/blindness#7

BlockStateLoader.LoadResult is consumed by BlockIndexBuilder and never serialised, so a rework that changes its shape while producing the same index moves no dumped byte. That is the intended altitude and the reason the block sum stays in SEES for this path.

*Probe:* reshape LoadResult without changing what the builder emits and confirm all 30 dump files are byte-identical

## B8 - sweep.player asserts nothing, so it can never fail

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/visual/TestPlayerParityVanilla.java`
- **sees** `pin.player-crc`, `manifest.player-sheets`, `manifest.player-raw`
- **blind** `sweep.player`
- **source** 07/blindness#8; CLAUDE.md 'Which gate sees what'

TestPlayerParityVanilla is a main that alpha-crops AND rescales both sides to a common box before diffing, so it cannot detect a part-placement or fit change of any size. Its number is a LOOK gauge; the byte gates are the CRC pin, the contact-sheet manifest and the raw pair the sweep writes beside its rescaled one.

*Probe:* read TestPlayerParityVanilla for an assert of any kind; there is none, and the id is kept separate from B9 so the citation survives

## B9 - No artifact renders BUST, the cape, or any 2D player path

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/PlayerRenderer.java`, `src/main/java/lib/minecraft/renderer/engine/kit/ElytraKit.java`, `src/main/java/lib/minecraft/renderer/option/*Player*.java`, `src/main/java/lib/minecraft/renderer/face/HumanoidPart.java`
- **sees** `pin.player-crc`, `manifest.player-sheets`, `manifest.player-raw`
- **blind** `sweep.player`
- **source** 07/blindness#8,#9,#12; CLAUDE.md 'Which gate sees what'

The player byte pin is PlayerRasterizeFittedGoldenTest's two CRC32 constants, which cover FULL and SKULL with no cape and no armour. Everything else on the player surface is covered only by the 104-file contact-sheet manifest, and the elytra and both cape views live in the toggles group alone.

*Probe:* perturb the wing build's fit frame and re-render -Psheets=toggles: elytra_only_3_4_ and elytra_cape_3_4_ move and none of the other eleven cells do

## B10 - BlockRenderer never calls buildBox, and both item buildBox call sites are FaceTextures.uniform

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/kit/BlockGeometryKit.java`
- **sees** `sweep.entity`, `sweep.armor`, `pin.player-crc`, `manifest.player-sheets`, `manifest.portal`, `manifest.player-raw`
- **blind** `sweep.block`, `sweep.item`
- **source** 07/blindness#10; CLAUDE.md 'Which gate sees what'

The block and item parity sums are structurally blind to the box BUILDER, so a clean block sum is not evidence about buildBox. The gates that see it are the 14 armoured entity rows, the player CRC pin and the player contact sheets. The blindness is to that one method: the same file also holds the block element path and the single fan emitter, which BlockRenderer does reach, so B19 correctly keeps the block and item sums in SEES for a change anywhere else in it and this rule's blind list surfaces only when nothing else selects them.

*Probe:* ./gradlew playerRender -Psheets=core-matrix,toggles,armor-per-slot and hash either side with git stash push -- src between the two renders

## B11a - Blocks and items are structurally immune to a DEPTH change: 0 of 1055 and 0 of 479 rows move

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/ModelEngine.java`
- **sees** -
- **blind** `sweep.block`, `sweep.item`
- **source** 07/blindness#11; CLAUDE.md depth section

Their coplanar pairs are exactly coincident, so both interpolation forms agree bit for bit and there is no crossing to find. This is the mechanism working rather than the gate missing them, which is what makes it a diagnostic discriminator when a rasterizer change moves something unexpected.

*Probe:* -Dasset.depth.range=N sweeps entity rows and leaves block and item at 0 moved

## B11b - The block and item immunity is to DEPTH only and does not generalise

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/ModelEngine.java`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `manifest.player-raw`
- **blind** -
- **source** 07/blindness#11; audit 09/G7; CLAUDE.md depth section

A coverage or texel-fetch change in the same file reaches blocks like anything else: bounding the fetch to the face's own UV rect moved 31 block rows, all better. So the block and item sums stay in SEES for this path and B11a is never a licence to skip them.

*Probe:* -Dasset.snap.grid=N and a texel-fetch perturbation both move block rows where -Dasset.depth.range=N does not

## B12 - A short -Psheets= list is a hole rather than a sample

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/visual/TestPlayerRender.java`
- **sees** `manifest.player-sheets`
- **blind** -
- **source** 07/blindness#12; CLAUDE.md 'Which gate sees what'

The ten offline sheet groups hash as 104 files and the elytra and both cape views appear in toggles alone, so a list naming the armour and trim groups but not toggles is blind to the wing build and to both cape views and reads as a clean pass. The capture is suppressed on -Psheets for exactly this reason.

*Probe:* render with -Psheets=armor-3d,trims and confirm the resulting file set is a strict subset of the 104 the full run produces

## B13 - Every test and sweep in the repo is structurally blind to a tooling/ change

- **mode** demote
- **triggers** `src/main/java/lib/minecraft/renderer/tooling/**`
- **sees** `manifest.tooling-tables`, `report.diagnostics-log`
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.dump.vanilla`, `manifest.dump.packs`, `digest.shipped-tables`, `pin.player-crc`, `pin.block-crc`, `pin.portal-crc`, `manifest.player-raw`
- **source** 07/blindness#13,#14; CLAUDE.md 'The gate for a tooling change'

They all read the SHIPPED JSON that a generator refactor does not regenerate, so a green test plus five green sums says nothing either way. The only gate is re-running the flow and comparing emitted bytes and the diagnostics log.

*Probe:* run the flow, diff the emitted table against a capture taken at the clean tree (never git diff, which conflates a byte this change moved with one that was going to move regardless), and diff the flow's INFO log

## B14 - A byte-identical emitted table is not the same claim as an unchanged run

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/tooling/**`
- **sees** `report.diagnostics-log`, `manifest.tooling-tables`
- **blind** -
- **source** 07/blindness#14; CLAUDE.md 'The gate for a tooling change'

Each index build records its own INFO entries, so reordering two of them is invisible in every emitted table and plainly visible in the log. That is what caught an accidental reordering in the entityModels flow: the JSON matched byte for byte while one line moved from position 9 to 6.

*Probe:* reorder two index builds and confirm the tables are byte-identical while the log is not

## B15 - atlas.png can never be a byte gate

- **mode** suppress
- **triggers** `src/main/java/lib/minecraft/renderer/tooling/atlas/**`
- **sees** -
- **blind** -
- **source** 07/blindness#15; CLAUDE.md 'atlas parallel non-deterministic'

AtlasRenderer dispatches its tiles on parallelStream by design, so two runs place the same sprites at different offsets. The output is not a value that can be captured, compared or promoted, which is why it is registered as no artifact and why manifest.visual excludes it.

*Probe:* run atlas twice with --rerun-tasks and hash build/atlas/atlas.png; the two differ

## B16 - The probes.json resolveIn sample is itself guarded against a salt-randomized findFirst

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/PackAcquisition.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** 07/blindness#16

PackAcquisition.namespaces builds a per-run-salted set, so a findFirst over it flaps between runs. The dump emits a count always and a sample only when the count is at most one, which is the only shape that is both informative and reproducible.

*Probe:* run parityDump over five JVMs and confirm probes.json is byte-identical in all five

## B17 - synthesis.json dumps the SOURCES rather than the synthesizer's registry

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/texture/TextureSynthesizer*.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.item`
- **blind** -
- **source** 07/blindness#17

Dumping the registry would be a second copy of a production rule, and a dump that restates the rule it is checking cannot catch that rule being wrong. The sources are inputs, so they move only when something upstream does.

*Probe:* read synthesis.json and confirm every key is an input texture id rather than a synthesized one

## B18 - CatharsisConfig is not dumped at all

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/**Catharsis*.java`, `src/main/java/lib/minecraft/renderer/asset/pack/cats/**`
- **sees** `sweep.block`, `sweep.item`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** 07/blindness#18

The fabric:overlays plus catharsis:pack half of pack resolution has no dump section, so an identical dump is silent about it. What sees a change there is a render against a fixture that carries an overlay.

*Probe:* grep the 14 dump sections for any catharsis or overlay key; there is none

## B19 - parityDump is blind to everything downstream of the load, so an engine or renderer change is demoted regardless of the dump verdict

- **mode** demote
- **triggers** `src/main/java/lib/minecraft/renderer/engine/**`, `src/main/java/lib/minecraft/renderer/*Renderer.java`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `manifest.fluid`, `manifest.portal`, `manifest.player-sheets`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** 07/blindness#1,#19

An identical dump proves the render INPUTS are identical, which implies identical output only while the render code itself is untouched. The dump serialises loaded data and never renders.

*Probe:* PipelineParityDump serialises loaded data and never calls BlockRenderer.resolveVariant; grep the dump for any renderer entry point

## B20 - The dump is blind to the Diagnostics scope tree

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/**`, `src/main/java/lib/minecraft/renderer/tooling/kernel/Diagnostics.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `report.diagnostics-log`
- **blind** -
- **source** 07/blindness#20; CLAUDE.md 'The gate for a tooling change'

A read layer that lost its child(name) scoping would move no dumped byte, because the scope tree is a property of how the diagnostics were recorded rather than of the data that was read.

*Probe:* drop a child(name) call in a loader and confirm all 30 dump files are byte-identical while the flow log's scope prefixes change

## B21 - sweep.item is blind to the whole of BlockIndexBuilder, by two independent hops

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/BlockIndexBuilder.java`
- **sees** `sweep.block`, `sweep.entity`
- **blind** `sweep.item`
- **source** CLAUDE.md 'The item sum is structurally blind to BlockIndexBuilder'

ItemIndexBuilder.load takes its beEntries from BlockModelLoader directly, a sibling of the block index rather than its output, so no product of BlockIndexBuilder is an input. And ItemRenderer's only findBlock sits inside its GuiIcon sub-renderer, which the item sweep does not render.

*Probe:* read ItemIndexBuilder.load's parameter list for any block-index type, and grep ItemRenderer for findBlock outside GuiIcon; both come back empty

## B22 - Block#modelIcon has no key in any dump section, and Block.Variant.noPosition has none either

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/BlockIndexBuilder.java`
- **sees** `sweep.block`, `sweep.entity`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** CLAUDE.md 'PipelineParityDump is the right gate for an index or decode change'

blocks.json carries every block row's id, digest, textures, variants, tags, tint and source and no modelIcon, so a change flipping it on hundreds of blocks leaves all 30 dump files identical and its only gate is the block sum. noPosition's only reader is EntityRenderer's carried-block path, so its only gate is enderman~carried=grass_block in the entity sweep.

*Probe:* grep the 14 dump sections for modelIcon and for noPosition; both come back empty

## B23 - No parity sweep reaches the paletted trim permutation

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/kit/TrimKit.java`
- **sees** `manifest.player-sheets`, `pin.armor-span`
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`
- **source** CLAUDE.md 'No parity sweep reaches the paletted trim permutation'

A throw-probe on TrimKit.permuteFrom gets 0 hits across all five sweeps: the item sweep renders untrimmed icons and its 18 trim-named rows are flat smithing-template sprites that permute nothing, and the armour sweep's seven subjects carry no trim. The gates are ArmorKitCitCompositeTest and the trims sheet group's 11 cells.

*Probe:* throw from TrimKit.permuteFrom and run all five sweeps; none of them fires it

## R1 - The option surface reaches every renderer that takes options, and nothing else

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/option/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** reach baseline; coverage gap measured at P9 (109 of 356 files uncovered by the 23)

Every renderer entry point takes a RenderOptions, so a default or a resolution rule here reaches whatever that renderer draws. The dump is blind to all of it for B19's reason: it serialises loaded pipeline data and never constructs an options record.

*Probe:* change a default on an option record and confirm the five sweeps move while all 30 dump files are byte-identical

## R2 - The asset DTO layer is what every renderer reads and what the dump serialises, so it reaches both

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `manifest.dump.vanilla`, `manifest.dump.packs`, `manifest.player-raw`
- **blind** -
- **source** reach baseline; coverage gap measured at P9

asset.** holds the records the pipeline builds and the renderers consume, and the dump's 14 sections are a projection of exactly those records. A change here is visible on both sides, which is why it is the one package family with no blindness to claim.

*Probe:* add a field to a dumped record and confirm both the dump and a sweep move

## R3 - The tensor math is under every projected vertex, so it reaches every render and is pinned by two golden float vectors

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/tensor/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `pin.vanilla-iso-pose`, `pin.kit-corners`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** reach baseline; coverage gap measured at P9

Matrix4f and Vector3f are on the path of every vertex the engine projects, and the two golden pins hold 16 and 24 exact floats through that math - so an arithmetic change fails them before any sum moves. The dump never projects a vertex.

*Probe:* perturb a Matrix4f multiply and confirm pin.vanilla-iso-pose fails while the dump is byte-identical

## R4 - The face vocabulary decides winding, UV pairing and per-face shade, so it reaches every 3D render

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/face/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** reach baseline; coverage gap measured at P9

CornerPhase fixes which corner a quad starts at and therefore which diagonal the fan splits on, and Unwrap fixes which texels a face reads; both are evaluated per quad at render time and neither is a loaded value the dump could carry. FacePhaseTest and HumanoidPartCropTest pin the tables themselves.

*Probe:* flip one CornerPhase index array and confirm FacePhaseTest fails while all 30 dump files are byte-identical

## R5 - The exception types carry no behaviour a parity artifact can observe

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/exception/**`
- **sees** -
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`
- **source** reach baseline; coverage gap measured at P9

These are message and constructor shapes on throwables. Nothing renders differently because a detail message changed, and no stored artifact records a message - so the gate is ./gradlew test compiling and passing, which is not an artifact this store holds. A rewiring of the hierarchy that changed which catch block runs would show up as a sweep failing outright rather than as a moved row.

*Probe:* change a detail message and confirm no sweep row moves; change a supertype and confirm the sweep fails to run at all

## R6 - A harness render change rewrites the ground truth every sweep diffs against

- **mode** select
- **triggers** `harness/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.references`, `manifest.player-raw`
- **blind** -
- **source** reach baseline; CLAUDE.md 'renderVanillaReferences is not a full sweep'

The harness produces the reference tree, so a change to a frame renderer or the bounds walker moves the bytes every sweep compares to - and moves them for sweeps nobody re-ran, which is how stale ground truth was left on disk twice. Only renderVanillaAllReferences refreshes the whole tree, so a partial refresh is the failure mode rather than the fix.

*Probe:* re-render with the change stashed: a reference that moves was stale, and one that does not was not reached

## R7 - A toolkit change alters how every artifact is COMPUTED and how none of them is produced

- **mode** select
- **triggers** `scripts/parity/**`
- **sees** -
- **blind** -
- **source** reach baseline; the toolkit is the one producer of every stored byte

The toolkit reads a producer's output and writes the canonical form; it renders nothing, so no artifact's producer bytes move. What can move is the captured form itself, and the gate for that is paritySelfTest, which every parity task depends on. A capture taken across a toolkit change is compared with the OLD store, so a form change surfaces as movers on every artifact at once, which is the signature to look for.

*Probe:* run the selftest, then capture one artifact either side of the change and diff the two canonical files

## R8 - The build wiring decides what runs, and renders nothing itself

- **mode** select
- **triggers** `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `gradle.properties`, `gradlew`, `gradlew.bat`, `src/jmh/**`
- **sees** -
- **blind** -
- **source** reach baseline; P5 and P6 gated exactly this way

A task registration, a finalizer edge or a property read moves no rendered byte: what it changes is which producer runs and what argv it runs with. The gate is running the tasks and reading their argv, which is why the Gradle phases of this effort gate on task lists and resolved command lines rather than on a sum.

*Probe:* read back the resolved commandLine of every task the change touches and compare it to the one it replaced

## R9 - The visual mains are the producers, so a change to one changes what its artifact holds

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/visual/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.visual`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** reach baseline; P13's writer reshape is exactly this path

Each sweep and render main is the entry point its Gradle task runs, so its own code decides the rows a table carries and the files a manifest hashes. A change to how a sweep MEASURES moves every row of its table without a single rendered pixel moving, which is why a writer reshape is registered as its own parity-risk cluster rather than folded into a render change.

*Probe:* re-run the sweep and diff the captured table: a measurement change moves every row and a render change moves some

## R10 - The rest of the test suite asserts rather than emits, so no stored artifact sees it

- **mode** select
- **triggers** `src/test/java/**`, `src/test/resources/**`
- **sees** -
- **blind** -
- **source** reach baseline

A test class and a test fixture are read by ./gradlew test and by nothing that writes a captured byte. The gate for a change here is the suite itself, which is not an artifact this store holds - so the honest answer is that the parity store cannot see it, rather than that nothing can.

*Probe:* run ./gradlew test, then capture any artifact and confirm it is byte-identical

## R11 - The parity store's own files are the baseline a comparison reads, never something a run produces

- **mode** select
- **triggers** `src/test/resources/lib/minecraft/renderer/parity/**`
- **sees** -
- **blind** -
- **source** reach baseline; the store is the oracle rather than an output

Editing a stored artifact by hand does not change what a producer emits; it changes what the emitted bytes are compared against, which is the one thing a capture cannot detect. index.json carries each file's digest over its normalized bytes for exactly this, so a hand-edit surfaces as the compare reporting the file as edited rather than as a mover.

*Probe:* compare after a hand-edit: the digest in index.json no longer matches the file

## R12 - The ten shipped tables are pipeline INPUT, so a change to one reaches every render that loads it

- **mode** select
- **triggers** `src/main/resources/lib/minecraft/renderer/*.json`
- **sees** `manifest.tooling-tables`, `digest.shipped-tables`, `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** P15; the gap R1 refused on - no rule covered the files two artifacts are defined over

src/main/resources/lib/minecraft/renderer/ holds exactly the ten ASM-derived tables the tooling flows emit and the loaders read at runtime, so an edit here is indistinguishable at render time from a generator change that produced it. manifest.tooling-tables is a manifest over these very files and digest.shipped-tables digests the same ten, so both see any edit directly; the sweeps and the dumps see it through the index the loaders build. This is the converse of B13: that rule says a tooling/ SOURCE change is invisible because it does not regenerate the tables, and this one says changing the tables themselves is visible to everything.

*Probe:* edit one value in block_tints.json and re-run the block sweep and parityDump; both move, and manifest.tooling-tables moves whether or not a generator ran

## R13 - The GsonContributor service registration configures every pipeline decode, so nothing that loads is blind to it

- **mode** select
- **triggers** `src/main/resources/META-INF/services/**`
- **sees** `digest.shipped-tables`, `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** P15; the second uncovered path under src/main/resources/

META-INF/services/dev.simplified.gson.GsonContributor is how PipelineGsonContributor is discovered, and that contributor installs the adapters every pipeline JSON decode goes through. Losing or repointing it changes how every shipped table and every pack file is read, so its reach is the union of everything that loads - which is wider than any one table's, and is why it is its own rule rather than a second glob on R12.

*Probe:* delete the registration and run any sweep; the pipeline fails to decode outright rather than decoding differently, which is what makes this a load-time reach rather than a per-value one

## Paths that reach nothing

Covered and reaching nothing is a different answer from "I do not know". A changed
path matching neither a rule nor one of these is `UNKNOWN`, and refusal R1 stops the
plan rather than guessing.

`**/package-info.java`, `**/*.md`, `notes/**`, `.claude/**`, `.gitattributes`, `.gitignore`, `scripts/euler_reference_svg.py`
