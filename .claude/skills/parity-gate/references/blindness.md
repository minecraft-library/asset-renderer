# Blindness map

Generated from `blindness.json`. **Do not edit** - regenerate with:

```
./gradlew test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true --rerun
```

**Decide from the JSON, explain from this file.** `parityPlan` resolves reach from
`blindness.json` directly; this rendering is for explaining a verdict to a human and
for checking a rule that is being questioned. Reasoning from the prose instead is the
judged reach resolution the map replaced, and it looks correct while being wrong.

Reach is resolved **one changed path at a time** and the answers are unioned, so a
rule speaks about the files it triggers on and about no others: a file that reaches
nothing, committed beside one that reaches a bundle, still plans that bundle.

Within one path the resolver runs three passes, in this order. **Union**: each
fired rule contributes its `sees`, its mode included. **Demote**: each fired
`demote` rule removes its own `blind` set from that union, taking out what a
different rule selected on this same path along with its own contribution.
**Suppress**: each fired `suppress` rule removes its `sees` and its `blind`
together, the pass that outranks the other two.

Neither removal pass reads a `select` rule's `blind` list, so that list subtracts
nothing and is a statement the plan prints - B10 and B23 below each carry one
naming artifacts outside their own `sees`. What a claim comes to therefore
depends on whether the claiming rule and the selecting rule fire on the **same
path** or on **different paths**, and one pair of rules answers both ways over
one change set.

`BlindnessMapTest.java` alone fires B37 (`select`) and B39 (`demote`, B37's
list) on one path: the demote pass empties the union, SEES is empty, and every
artifact on that list is reported blind with nothing recorded against it. That
file beside `SelfCapture.java` fires B39 on the first path alone, the second
path resolves to B37's list, and the union carries it - SEES holds all of it
and each blind row reads "claimed blind, selected by B37". A `select`
rule's claim resolves by the same arithmetic from the other side: on
`BlockGeometryKit.java` B10 claims `sweep.block` blind while B19 selects it on
that path, so it is in SEES and its row names B19; on `PlayerRenderer.java` B9
claims `sweep.player` and no fired rule selects it, so it is absent from SEES and
its row names nobody.

## Judging a `manifest.portal` mover on the sub-tick path

`portalRenderer` writes each animated subject twice - a plain strip on the tick
lattice and an `_animated_smooth` strip at three sub-steps per tick - and
`manifest.portal` hashes both. A sub-tick change that collapsed the smooth strip
to duplicated frames would move those bytes and read as an ordinary mover, so the
bytes having moved is not by itself the question.

What separates a real intermediate frame from a duplicate: **frame `3n` of the
smooth strip is the plain strip's frame `n` exactly, and the two frames between
each pair differ from both of their neighbours.** Measured over all four animated
subjects, 120 plain frames and 360 smooth: every one of the 120 lattice frames is
identical on every channel, and the smallest margin by which an in-between frame
differs from its nearer neighbour is 20 channel levels on the portal and 47 on the
gateway. Re-measure with `./gradlew portalRenderer` and decode both strips; the
run's own capture step refuses if the working root already holds a finished
capture, which does not affect the strips it writes.

## B2 - CIT and CTM rules are dark in both parityDump configurations, and the rule package's other parsers are not

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/pack/rule/**`, `src/main/java/lib/minecraft/renderer/pipeline/pack/rule/CitParser.java`, `src/main/java/lib/minecraft/renderer/pipeline/pack/rule/CtmParser.java`, `src/main/java/lib/minecraft/renderer/pipeline/pack/rule/RuleScanner.java`
- **sees** `digest.shipped-tables`, `manifest.dump.packs`
- **blind** `manifest.dump.vanilla`
- **source** measured by perturbing ColorProperties.java: 1 of 2 declared sees moved, and 1 declared blind held; CLAUDE.md 'The pack filter'

No pack fixture ships a cit/ or ctm/ tree, so rules.json reports cit_rules: 0 and ctm_rules: 0 on both configs. An empty dump diff proves nothing about them; RuleScannerMergeTest and CtmParserTest are the gate and both run inside ./gradlew test.

*Probe:* read rules.json in either dump config and confirm both counts are 0

## B3 - The multipart when-OR branch is never exercised, because no shipped block produces one

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/**/BlockState*.java`, `src/main/java/lib/minecraft/renderer/pipeline/**/Multipart*.java`
- **sees** `sweep.block`, `manifest.dump.vanilla`
- **blind** -
- **source** measured by perturbing MultipartWhenDeserializer.java: 2 of 2 declared sees moved

The vanilla blockstate corpus contains no multipart apply whose when carries an OR list, so the branch is present, compiled and unreached. A dump diff over blocks.json cannot distinguish a correct OR implementation from a broken one.

*Probe:* grep the shipped blockstate JSON for a when containing an OR key; zero hits means the branch is still unreached and the rule holds

## B4 - A vanilla-only dump leaves the pack-rule code dark, so the packs configuration is not optional - but a loader in this package is not pack-rule code and reaches both dumps

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/**`
- **sees** `manifest.dump.packs`, `sweep.block`, `sweep.item`, `digest.colormap-lut`, `manifest.dump.vanilla`
- **blind** -
- **source** measured by perturbing ColorMapLoader.java: 4 of 5 declared sees moved

With no pack loaded the RuleSet is empty and most PackIdDeriver rungs never execute, so the vanilla dump section for rules is a fixed empty shape whatever the code does. Only the packs configuration puts a rule through the deriver at all. The colormap digests are here because ColorMapLoader sits under this same glob and resolves every LUT off the compiled stack, so what this code hands back IS what those three digests are taken over.

*Probe:* run parityDump and compare rules.json between the two configs: the vanilla one is empty and the packs one is not

## B5 - A Pipeline.Result-level dump would clear a broken index loader, so the dump's altitude must be the renderer context

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/**`, `src/main/java/lib/minecraft/renderer/pipeline/loader/**`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.block`, `sweep.item`, `sweep.entity`, `pin.corpus-count`
- **blind** -
- **source** measured by perturbing BlockDefaultsLoader.java: 3 of 6 declared sees moved

BlockIndexBuilder, ItemIndexBuilder and EntityIndexBuilder run between the loaders and the renderer context, so a dump taken before them serialises inputs that are identical whatever the builders did with them. The dump is taken after, which is what makes an index change visible. The corpus counts ride this glob because BlockDefaultsLoader and GlintItemsLoader are both under it and both counts are the size of what they return.

*Probe:* PipelineParityDump builds a PipelineRendererContext before dumping; check the dump entry point resolves the index rather than the LoadResult

## B6 - The dump sees data rather than behaviour, so a resolution-logic change is not pinned by index identity

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/pack/rule/*Resolver*.java`, `src/main/java/lib/minecraft/renderer/pipeline/index/*Builder.java`
- **sees** `sweep.block`, `sweep.item`, `sweep.entity`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** measured by perturbing ItemIndexBuilder.java: 3 of 5 declared sees moved

A resolver that answers the same for every shipped input and differently for an unshipped one leaves every dumped byte identical. The dump's probes.json exists for exactly this: it samples resolution outcomes rather than the table they were resolved from.

*Probe:* perturb a resolver on a branch no shipped id takes and confirm the dump is byte-identical while probes.json is not

## B7 - Intermediates are deliberately not dumped, so a LoadResult-shaped rework is dump-invisible whenever its outputs match

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/BlockStateLoader.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.block`
- **blind** -
- **source** measured by perturbing BlockStateLoader.java: 3 of 3 declared sees moved

BlockStateLoader.LoadResult is consumed by BlockIndexBuilder and never serialised, so a rework that changes its shape while producing the same index moves no dumped byte. That is the intended altitude and the reason the block sum stays in SEES for this path.

*Probe:* reshape LoadResult without changing what the builder emits and confirm all 30 dump files are byte-identical

## B8 - sweep.player asserts nothing, so it can never fail - which is not the same as its rows not moving

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/visual/TestPlayerParityVanilla.java`
- **sees** `pin.player-crc`, `manifest.player-sheets`, `manifest.player-raw`, `sweep.player`
- **blind** -
- **source** measured by perturbing TestPlayerParityVanilla.java: 2 of 4 declared sees moved; the per-gate reach sentence moved out of CLAUDE.md and this map is its home

TestPlayerParityVanilla is a main that alpha-crops AND rescales both sides to a common box before diffing, so it cannot detect a part-placement or fit change of any size. Its number is a LOOK gauge; the byte gates are the CRC pin, the contact-sheet manifest and the raw pair the sweep writes beside its rescaled one.

*Probe:* read TestPlayerParityVanilla for an assert of any kind; there is none, and the id is kept separate from B9 so the citation survives

## B9 - No artifact renders BUST, the cape, or any 2D player path, and the 3D player geometry under these paths reaches every player artifact including the sweep

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/PlayerRenderer.java`, `src/main/java/lib/minecraft/renderer/engine/kit/ElytraKit.java`, `src/main/java/lib/minecraft/renderer/face/HumanoidPart.java`, `src/main/java/lib/minecraft/renderer/option/*Player*.java`
- **sees** `pin.player-crc`, `manifest.player-sheets`, `manifest.player-raw`, `sweep.player`
- **blind** -
- **source** measured by perturbing HumanoidPart.java: 4 of 4 declared sees moved; the per-gate reach sentence moved out of CLAUDE.md and this map is its home

The player byte pin is the three CRC32 values PlayerRendererFittedGoldenTest reads out of the store and compares its own renders against: FULL and SKULL bare, and FULL again in a full iron set. None of the three wears a cape. Everything else on the player surface is covered only by the 104-file contact-sheet manifest, and the elytra and both cape views live in the toggles group alone.

*Probe:* perturb the wing build's fit frame and re-render -Psheets=toggles: elytra_only_3_4_ and elytra_cape_3_4_ move and none of the other eleven cells do

## B10 - BlockRenderer never calls buildBox, and both item buildBox call sites are FaceTextures.uniform

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/kit/BlockGeometryKit.java`
- **sees** `sweep.entity`, `sweep.armor`, `pin.player-crc`, `manifest.player-sheets`, `manifest.portal`, `manifest.player-raw`
- **blind** `sweep.block`, `sweep.item`
- **source** measured by perturbing BlockGeometryKit.java: 4 of 6 declared sees moved, and 2 declared blind held; the per-gate reach sentence moved out of CLAUDE.md and this map is its home

The block and item parity sums are structurally blind to the box BUILDER, so a clean block sum is not evidence about buildBox. The gates that see it are the 14 armoured entity rows, the armour sweep, the player CRC pin, the player contact sheets, the raw player and armour renders, the portal manifest - PortalRenderer builds its end-portal slab with buildBox and its gateway cube through unitCube, which is a buildBox call - and BlockGeometryKitQuadFanTest, which calls buildBox directly and is the only one of them that runs in the fast suite. The blindness is to that one method: the same file also holds the block element path and the single fan emitter, which BlockRenderer does reach, so B19 correctly keeps the block and item sums in SEES for a change anywhere else in it and this rule's blind list surfaces only when nothing else selects them.

*Probe:* ./gradlew playerRender -Psheets=core-matrix,toggles,armor-per-slot and hash either side with git stash push -- src between the two renders

## B12 - A short -Psheets= list is a hole rather than a sample

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/visual/TestPlayerRender.java`
- **sees** `manifest.player-sheets`
- **blind** -
- **source** measured by perturbing TestPlayerRender.java: 1 of 1 declared sees moved; the per-gate reach sentence moved out of CLAUDE.md and this map is its home

The ten offline sheet groups hash as 104 files and the elytra and both cape views appear in toggles alone, so a list naming the armour and trim groups but not toggles is blind to the wing build and to both cape views and reads as a clean pass. The capture is suppressed on -Psheets for exactly this reason.

*Probe:* render with -Psheets=armor-3d,trims and confirm the resulting file set is a strict subset of the 104 the full run produces

## B13 - Every test and sweep in the repo is structurally blind to a tooling/ change

- **mode** demote
- **triggers** `tooling/src/main/java/**`
- **sees** `manifest.tooling-tables`, `report.diagnostics-log`
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.dump.vanilla`, `manifest.dump.packs`, `digest.shipped-tables`, `pin.player-crc`, `pin.block-crc`, `pin.portal-crc`, `manifest.player-raw`
- **source** measured by perturbing ToolingPotionColors.java: 0 of 2 declared sees moved, and 13 declared blind held; CLAUDE.md 'Tooling'

They all read the SHIPPED JSON that a generator refactor does not regenerate, so a green test plus five green sums says nothing either way. The only gate is re-running the flow and comparing emitted bytes and the diagnostics log.

*Probe:* run the flow, diff the emitted table against a capture taken at the clean tree (never git diff, which conflates a byte this change moved with one that was going to move regardless), and diff the flow's INFO log

## B14 - A byte-identical emitted table is not the same claim as an unchanged run

- **mode** select
- **triggers** `tooling/src/main/java/**`
- **sees** `report.diagnostics-log`, `manifest.tooling-tables`
- **blind** -
- **source** measured by perturbing GlintItemsWalk.java: 0 of 2 declared sees moved; CLAUDE.md 'Tooling'

Each index build records its own INFO entries, so reordering two of them is invisible in every emitted table and plainly visible in the log. That is what caught an accidental reordering in the entityModels flow: the JSON matched byte for byte while one line moved from position 9 to 6.

*Probe:* reorder two index builds and confirm the tables are byte-identical while the log is not

## B15 - atlas.png can never be a byte gate

- **mode** suppress
- **triggers** `src/main/java/lib/minecraft/renderer/AtlasRenderer.java`, `src/main/java/lib/minecraft/renderer/option/Atlas*.java`, `src/test/java/lib/minecraft/renderer/example/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead; CLAUDE.md 'Gates'

AtlasRenderer dispatches its tiles on parallelStream by design, so two runs place the same sprites at different offsets. The output is not a value that can be captured, compared or promoted, which is why it is registered as no artifact and why manifest.visual excludes it. The entry point that drives it sits in the test tree and emits, so B33's claim that those sources only assert is false for it; this rule is where the same answer is written down rather than inferred from an excuse.

*Probe:* run generateAtlas twice with --rerun-tasks and hash build/atlas/atlas.png; the two differ

## B16 - The probes.json resolveIn sample is itself guarded against a salt-randomized findFirst

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/pack/PackAcquisition.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** measured by perturbing PackAcquisition.java: 2 of 2 declared sees moved

PackAcquisition.namespaces builds a per-run-salted set, so a findFirst over it flaps between runs. The dump emits a count always and a sample only when the count is at most one, which is the only shape that is both informative and reproducible.

*Probe:* run parityDump over five JVMs and confirm probes.json is byte-identical in all five

## B17 - synthesis.json dumps the SOURCES rather than the synthesizer's registry

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/texture/TextureSynthesizer*.java`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `sweep.item`
- **blind** -
- **source** measured by perturbing TextureSynthesizer.java: 0 of 3 declared sees moved

Dumping the registry would be a second copy of a production rule, and a dump that restates the rule it is checking cannot catch that rule being wrong. The sources are inputs, so they move only when something upstream does.

*Probe:* read synthesis.json and confirm every key is an input texture id rather than a synthesized one

## B18 - CatharsisConfig is not itself dumped, and a Catharsis condition still reaches the packs dump through what it selects

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/pack/cats/**`, `src/main/java/lib/minecraft/renderer/pipeline/pack/CatharsisCondition.java`, `src/main/java/lib/minecraft/renderer/pipeline/pack/CatharsisConfig.java`, `src/main/java/lib/minecraft/renderer/pipeline/pack/CatharsisOverlays.java`, `src/main/java/lib/minecraft/renderer/pipeline/pack/CatharsisTarget.java`
- **sees** `sweep.block`, `sweep.item`, `manifest.dump.packs`
- **blind** `manifest.dump.vanilla`
- **source** measured by perturbing CatharsisCondition.java: 1 of 3 declared sees moved, and 1 declared blind held

The fabric:overlays plus catharsis:pack half of pack resolution has no dump section, so an identical dump is silent about it. What sees a change there is a render against a fixture that carries an overlay.

*Probe:* grep the 14 dump sections for any catharsis or overlay key; there is none

## B19 - parityDump is blind to everything downstream of the load, so an engine or renderer change is demoted regardless of the dump verdict

- **mode** demote
- **triggers** `src/main/java/lib/minecraft/renderer/*Renderer.java`, `src/main/java/lib/minecraft/renderer/engine/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `manifest.fluid`, `manifest.portal`, `manifest.player-sheets`, `manifest.player-raw`, `manifest.visual`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing ModelEngine.java: 12 of 14 declared sees moved, and 2 declared blind held

An identical dump proves the render INPUTS are identical, which implies identical output only while the render code itself is untouched. The dump serialises loaded data and never renders. Everything this glob reaches is a render, which is why the three render CRC pins are on the list beside the manifests they were taken over, and why the glint sweep and manifest.visual are too: the glint is an engine-composited overlay, and manifest.visual hashes what the visual mains draw.

*Probe:* PipelineParityDump serialises loaded data and never calls BlockRenderer.resolveVariant; grep the dump for any renderer entry point

## B20 - The dump serialises what a pipeline read layer loaded, so every read layer reaches it

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/**`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** measured by perturbing BlockTagLoader.java: both declared sees moved

The dump is a serialisation of the loaded pipeline state, so a read layer that resolves a different value moves a dumped byte. This is the catch-all beside the narrower rules that name a package each - B5 for index and loader, B4 for the pack readers, B7 for the blockstate loader - and what it covers alone is pipeline/util/ and the context class. It reaches no render, so the sweeps and the CRC pins are not on it; B19 carries those.

*Probe:* perturb a value a read layer resolves and confirm the dump files move while no render artifact does

## B21 - sweep.item is blind to the whole of BlockIndexBuilder, by two independent hops

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/BlockIndexBuilder.java`
- **sees** `sweep.block`, `sweep.entity`
- **blind** `sweep.item`
- **source** measured by perturbing BlockIndexBuilder.java: 2 of 2 declared sees moved, and 1 declared blind held

ItemIndexBuilder.load takes its beEntries from BlockModelLoader directly, a sibling of the block index rather than its output, so no product of BlockIndexBuilder is an input. And ItemRenderer's only findBlock sits inside its GuiIcon sub-renderer, which the item sweep does not render.

*Probe:* read ItemIndexBuilder.load's parameter list for any block-index type, and grep ItemRenderer for findBlock outside GuiIcon; both come back empty

## B22 - Block#modelIcon has no key in any dump section, and Block.Variant.noPosition has none either

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/pipeline/index/BlockIndexBuilder.java`
- **sees** `sweep.block`, `sweep.entity`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing BlockIndexBuilder.java: 1 of 2 declared sees moved, and 2 declared blind held; the dump carrying no key for either field is stated here and nowhere else

blocks.json carries every block row's id, digest, textures, variants, tags, tint and source and no modelIcon, so a change flipping it on hundreds of blocks leaves all 30 dump files identical and its only gate is the block sum. noPosition's only reader is EntityRenderer's carried-block path, so its only gate is enderman~carried=grass_block in the entity sweep.

*Probe:* grep the 14 dump sections for modelIcon and for noPosition; both come back empty

## B23 - No parity sweep reaches the paletted trim permutation

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/kit/TrimKit.java`
- **sees** `manifest.player-sheets`, `pin.armor-span`
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`
- **source** measured by perturbing TrimKit.java: 1 of 2 declared sees moved, and 7 declared blind held

A throw-probe on TrimKit.permuteFrom gets 0 hits across all five sweeps: the item sweep renders untrimmed icons and its 18 trim-named rows are flat smithing-template sprites that permute nothing, and the armour sweep's seven subjects carry no trim. The gates are ArmorKitTest and the trims sheet group's 11 cells.

*Probe:* throw from TrimKit.permuteFrom and run all five sweeps; none of them fires it

## B24 - The option surface reaches every renderer that takes options, and nothing else

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/option/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`, `manifest.visual`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing OutputOptions.java: 11 of 14 declared sees moved, and 2 declared blind held

Every renderer entry point takes a RenderOptions, so a default or a resolution rule here reaches whatever that renderer draws. That is the same population B19 reaches and the list is the same: the three render CRC pins beside their manifests, the glint sweep, and the cache/visual producers. The dump is blind to all of it for B19's reason: it serialises loaded pipeline data and never constructs an options record.

*Probe:* change a default on an option record and confirm the five sweeps move while all 30 dump files are byte-identical

## B25 - The asset DTO layer is what every renderer reads and what the dump serialises, so it reaches both

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/asset/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `digest.colormap-lut`, `manifest.dump.vanilla`, `manifest.dump.packs`, `manifest.player-raw`, `manifest.visual`
- **blind** -
- **source** measured by perturbing ModelElement.java: 4 of 10 declared sees moved

asset.** holds the records the pipeline builds and the renderers consume, and the dump's 14 sections are a projection of exactly those records. A change here is visible on both sides, which is why it is the one package family with no blindness to claim. ColorMap is one of those records and its pixel buffer is the exact form the colormap digests are taken over, so they move with it.

*Probe:* add a field to a dumped record and confirm both the dump and a sweep move

## B26 - The tensor math is under every projected vertex, so it reaches every render and is pinned by two golden float vectors

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/tensor/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `pin.player-crc`, `pin.vanilla-iso-pose`, `pin.kit-corners`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`, `manifest.visual`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing Matrix4f.java: 10 of 15 declared sees moved, and 2 declared blind held

Matrix4f and Vector3f are on the path of every vertex the engine projects, and the two golden pins hold 16 and 24 exact floats through that math - so an arithmetic change fails them before any sum moves. Being under every projected vertex is what puts the other three render CRC pins, the glint sweep and the cache/visual producers on the list as well. The dump never projects a vertex.

*Probe:* perturb a Matrix4f multiply and confirm pin.vanilla-iso-pose fails while the dump is byte-identical

## B27 - The face vocabulary decides winding, UV pairing and per-face shade, so it reaches every 3D render

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/face/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`, `manifest.visual`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing CornerPhase.java: 11 of 14 declared sees moved, and 2 declared blind held

CornerPhase fixes which corner a quad starts at and therefore which diagonal the fan splits on, and Unwrap fixes which texels a face reads; both are evaluated per quad at render time and neither is a loaded value the dump could carry. Every 3D render goes through them, which is why the three render CRC pins, the glint sweep and the cache/visual producers are on the list beside the sums. CornerPhaseTest and HumanoidPartCropTest pin the tables themselves.

*Probe:* flip one CornerPhase index array and confirm CornerPhaseTest fails while all 30 dump files are byte-identical

## B28 - The exception types carry no behaviour a parity artifact can observe

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/exception/**`
- **sees** -
- **blind** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`
- **source** measured by perturbing RendererException.java: 0 of 0 declared sees moved, and 7 declared blind held

These are message and constructor shapes on throwables. Nothing renders differently because a detail message changed, and no stored artifact records a message - so the gate is ./gradlew test compiling and passing, which is not an artifact this store holds. A rewiring of the hierarchy that changed which catch block runs would show up as a sweep failing outright rather than as a moved row.

*Probe:* change a detail message and confirm no sweep row moves; change a supertype and confirm the sweep fails to run at all

## B29 - A harness render change rewrites the ground truth every sweep diffs against

- **mode** select
- **triggers** `harness/build.gradle.kts`, `harness/gradle.properties`, `harness/gradle/**`, `harness/gradlew`, `harness/gradlew.bat`, `harness/run-profile/**`, `harness/settings.gradle.kts`, `harness/src/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.menu`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.references`, `manifest.player-raw`
- **blind** -
- **source** measured by perturbing PipTarget.java: 8 of 8 declared sees moved; CLAUDE.md 'Parity: the harness contract'

The harness produces the reference tree, so a change to a frame renderer or the bounds walker moves the bytes every sweep compares to - and moves them for sweeps nobody re-ran, which is how stale ground truth was left on disk twice. Only renderVanillaAllReferences refreshes the whole tree, so a partial refresh is the failure mode rather than the fix. The triggers are the harness's SOURCE and the wiring that boots it, enumerated rather than written as one glob over the tree: the claim is about a render, and a markdown file under harness/ cannot move a reference byte while a rule matching it costs a whole-client re-render. The root build compiles none of those sources itself - the harness is its own Gradle build with its own wrapper - so ./gradlew test passes over a harness that does not compile and the next thing to notice is a client boot. harnessClasses shells to that wrapper and costs seconds, and ./gradlew check depends on it.

*Probe:* re-render with the change stashed: a reference that moves was stale, and one that does not was not reached

## B30 - A toolkit change alters how every artifact is COMPUTED and how none of them is produced

- **mode** select
- **triggers** `scripts/parity/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead; the toolkit is the one producer of every stored byte

The toolkit reads a producer's output and writes the canonical form; it renders nothing, so no artifact's producer bytes move. What can move is the captured form itself, and the gate for that is paritySelfTest, which every parity task depends on. A capture taken across a toolkit change is compared with the OLD store, so a form change surfaces as movers on every artifact at once, which is the signature to look for. One file under this glob is more than a reader: the member map in manifest.py DECLARES the population of the two manifests that share cache/visual rather than measuring it, and B41 names those artifacts for that file. This list stays empty because the rest of the toolkit does only what the claim says.

*Probe:* run the selftest, then capture one artifact either side of the change and diff the two canonical files

## B31 - The build wiring decides what runs, and renders nothing itself

- **mode** select
- **triggers** `gradle.properties`, `gradle/**`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`, `src/jmh/**`
- **sees** -
- **blind** -
- **source** measured by perturbing a task registration in build.gradle.kts: 0 of 0 declared sees moved. A version pin in that same file is a different kind of edit and does move rows, which is B47's measurement and why the file is no longer on this list

A task registration, a finalizer edge or a property read moves no rendered byte: what it changes is which producer runs and what argv it runs with. The gate is running the tasks and reading their argv, which is why the Gradle phases of this effort gate on task lists and resolved command lines rather than on a sum. Where a version is written down this stops being true, and B47 speaks for those two files instead - the root build file, which also declares the dependency set, and the version catalog under this glob. What is left here is wiring around producers rather than a statement of what one of them covers, and the member list that decides what the two cache/visual manifests hold is B41's claim over the two files it is typed in.

*Probe:* read back the resolved commandLine of every task the change touches and compare it to the one it replaced

## B32 - The visual mains are the producers, so a change to one changes what its artifact holds

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/parity/AppearanceCodec.java`, `src/test/java/lib/minecraft/renderer/parity/AppearanceKey.java`, `src/test/java/lib/minecraft/renderer/parity/AppearanceKeyTest.java`, `src/test/java/lib/minecraft/renderer/parity/ParityMetrics.java`, `src/test/java/lib/minecraft/renderer/parity/ReferenceKeyRoundTripTest.java`, `src/test/java/lib/minecraft/renderer/parity/SweepReport.java`, `src/test/java/lib/minecraft/renderer/parity/SweepSortDirectionTest.java`, `src/test/java/lib/minecraft/renderer/visual/**`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.menu`, `sweep.player`, `sweep.armor`, `sweep.glint`, `manifest.visual`, `manifest.player-sheets`, `manifest.fluid`, `manifest.portal`, `manifest.player-raw`
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`
- **source** measured by perturbing ParityMetrics.java: 6 of 11 declared sees moved, and 2 declared blind held

Each sweep and render main is the entry point its Gradle task runs, so its own code decides the rows a table carries and the files a manifest hashes. A change to how a sweep MEASURES moves every row of its table without a single rendered pixel moving, which is why a writer reshape is registered as its own parity-risk cluster rather than folded into a render change.

*Probe:* re-run the sweep and diff the captured table: a measurement change moves every row and a render change moves some

## B33 - The rest of the test suite asserts rather than emits, so no stored artifact sees it

- **mode** select
- **triggers** `src/test/java/**`, `src/test/resources/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead

A test class and a test fixture are read by ./gradlew test and by nothing that writes a captured byte. The gate for a change here is the suite itself, which is not an artifact this store holds - so the honest answer is that the parity store cannot see it, rather than that nothing can. The exceptions are the sources that DO emit, and each has a rule of its own: B32 for the visual mains, B37 for the write path behind the dump sections and every self-captured file, B38 for the tests that compute the value each self-captured one carries, and B15 for the atlas entry point, whose output is unhashable by construction.

*Probe:* run ./gradlew test, then capture any artifact and confirm it is byte-identical

## B34 - The parity store's own files are the baseline a comparison reads, never something a run produces

- **mode** select
- **triggers** `src/test/resources/lib/minecraft/renderer/parity/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead; the store is the oracle rather than an output

Editing a stored artifact by hand does not change what a producer emits; it changes what the emitted bytes are compared against, which is the one thing a capture cannot detect. index.json carries each file's digest over its normalized bytes for exactly this, and the reader is ./gradlew test - ParityIndexTest re-derives every row's digest from the file it names. The compare is NOT that reader: it opens the base payload directly and never index.json, so a hand-edited baseline reaches it as agreement about the edited value.

*Probe:* hand-edit a promoted artifact and run ./gradlew test: ParityIndexTest names the row whose file no longer hashes to its recorded digest. The compare says nothing, which is why the check lives in the suite

## B35 - The ten shipped tables are pipeline INPUT, so a change to one reaches every render that loads it

- **mode** select
- **triggers** `src/main/resources/lib/minecraft/renderer/*.json`
- **sees** `manifest.tooling-tables`, `digest.shipped-tables`, `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `pin.corpus-count`, `manifest.player-raw`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** measured by perturbing block_tints.json: 3 of 11 declared sees moved; the gap refusal R1 stopped on - no rule covered the files two artifacts are defined over

src/main/resources/lib/minecraft/renderer/ holds exactly the ten ASM-derived tables the tooling flows emit and the loaders read at runtime, so an edit here is indistinguishable at render time from a generator change that produced it. manifest.tooling-tables is a manifest over these very files and digest.shipped-tables digests the same ten, so both see any edit directly; the sweeps and the dumps see it through the index the loaders build. Two of the ten are counted as well as read - block_defaults.json and glint_items.json - and pin.corpus-count holds exactly those two sizes, so adding or dropping a row moves it directly rather than through an index. This is the converse of B13: that rule says a tooling/ SOURCE change is invisible because it does not regenerate the tables, and this one says changing the tables themselves is visible to everything.

*Probe:* edit one value in block_tints.json and re-run the block sweep and parityDump; both move, and manifest.tooling-tables moves whether or not a generator ran

## B36 - The GsonContributor service registration configures every pipeline decode, so nothing that loads is blind to it

- **mode** select
- **triggers** `src/main/resources/META-INF/services/**`
- **sees** `digest.shipped-tables`, `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `sweep.glint`, `manifest.player-raw`, `manifest.dump.vanilla`, `manifest.dump.packs`
- **blind** -
- **source** measured by perturbing dev.simplified.gson.GsonContributor: 0 of 9 declared sees moved, 2 not measurable in that run; the second uncovered path under src/main/resources/

META-INF/services/dev.simplified.gson.GsonContributor is how PipelineGsonContributor is discovered, and that contributor installs the adapters every pipeline JSON decode goes through. Losing or repointing it changes how every shipped table and every pack file is read, so its reach is the union of everything that loads - which is wider than any one table's, and is why it is its own rule rather than a second glob on B35.

*Probe:* delete the registration and run any sweep; the pipeline fails to decode outright rather than decoding differently, which is what makes this a load-time reach rather than a per-value one

## B37 - The dump sections and every self-captured file are written by these two packages, so a change to one rewrites the FORM of everything below it

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/parity/**`, `src/test/java/lib/minecraft/renderer/pipeline/dump/**`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `digest.shipped-tables`, `digest.colormap-lut`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `pin.corpus-count`, `pin.kit-corners`, `pin.vanilla-iso-pose`
- **blind** -
- **source** measured by perturbing SelfCapture.java: 5 of 11 declared sees moved; the emitters B33's glob covers and its claim excludes

B33's claim - that the test tree asserts rather than emits - is false for these two packages. PipelineParityDump is the only writer of the dump section files both dump manifests hash, and SelfCapture is the only writer of the file every digest set and every pin is stored as. What they own is the emitted form rather than the measurement: the envelope, the canonical JSON and the path. The VALUE inside each file is computed by the test that hands it over, and B38 covers that half. The globs take the two packages whole rather than naming the writers one by one, so a new file joining the write path is reached without anybody remembering to list it. B39 is the demotion that pays for that polarity, and it names its readers ONE FILE AT A TIME rather than carving a shape out of these globs: a reader it does not name answers with this whole list, which costs a run, where a writer it wrongly named would cost an unnoticed regression. Several files here are readers B39 does not name, and that is the cheap direction working as intended rather than an omission to close.

*Probe:* perturb the envelope SelfCapture writes and re-run the suite that feeds it; every pin and digest below moves while no renderer and no sweep does

## B38 - Each of these tests declares a self-captured artifact and computes the value stored under it, so its own edit is what moves that value

- **mode** select
- **triggers** `src/test/java/lib/minecraft/renderer/BlockRendererRasterPinTest.java`, `src/test/java/lib/minecraft/renderer/FluidRendererFrameBakePinTest.java`, `src/test/java/lib/minecraft/renderer/PlayerRendererFittedGoldenTest.java`, `src/test/java/lib/minecraft/renderer/PortalRendererFrameBakePinTest.java`, `src/test/java/lib/minecraft/renderer/engine/camera/VanillaEntityTransformGoldenTest.java`, `src/test/java/lib/minecraft/renderer/pipeline/ClientAcquisitionIntegrationTest.java`, `src/test/java/lib/minecraft/renderer/pipeline/loader/CorpusCountPinTest.java`, `src/test/java/lib/minecraft/renderer/pipeline/util/BundledResourceShaTest.java`
- **sees** `digest.shipped-tables`, `digest.colormap-lut`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `pin.corpus-count`, `pin.kit-corners`, `pin.vanilla-iso-pose`
- **blind** -
- **source** measured by perturbing FluidRendererFrameBakePinTest.java: 1 of 9 declared sees moved; the ARTIFACT declarations B37's two globs do not contain

B37 covers the mechanism that writes a self-captured file; none of the values in one is decided there. Each artifact below is named by an ARTIFACT constant in one of these tests, which builds the payload and hands it to SelfCapture, directly or through PinSet - the four CRC pins off a render the class configures, pin.corpus-count and pin.kit-corners and pin.vanilla-iso-pose off what it measures, and both digest sets off the collection it walks. Under B33 alone every one of them resolved to reaching nothing, which is the same false answer B37 was written for one level up. The sees list is the union across the eight, because a rule answers per glob set rather than per file: an edit to one of them plans the others too, and over-selecting costs a run where under-selecting costs an unnoticed regression.

*Probe:* change what one of them measures - a render option, a subject list, the table set a digest is taken over - and re-run it; the artifact it declares moves and no other does

## B39 - These are the parity package's own suites, the two renderers of its markdown views, and the sweep-side machinery the visual mains measure and report through; none of them writes a byte any artifact B37 names digests

- **mode** demote
- **triggers** `src/test/java/lib/minecraft/renderer/parity/AppearanceCodec.java`, `src/test/java/lib/minecraft/renderer/parity/AppearanceKey.java`, `src/test/java/lib/minecraft/renderer/parity/AppearanceKeyTest.java`, `src/test/java/lib/minecraft/renderer/parity/BlindnessMapTest.java`, `src/test/java/lib/minecraft/renderer/parity/ParityIndexTest.java`, `src/test/java/lib/minecraft/renderer/parity/ParityMetrics.java`, `src/test/java/lib/minecraft/renderer/parity/ParityReferences.java`, `src/test/java/lib/minecraft/renderer/parity/ParityReferencesTest.java`, `src/test/java/lib/minecraft/renderer/parity/ParityViews.java`, `src/test/java/lib/minecraft/renderer/parity/ParityViewsTest.java`, `src/test/java/lib/minecraft/renderer/parity/ReferenceKeyRoundTripTest.java`, `src/test/java/lib/minecraft/renderer/parity/SweepReport.java`, `src/test/java/lib/minecraft/renderer/parity/SweepSortDirectionTest.java`
- **sees** -
- **blind** `manifest.dump.vanilla`, `manifest.dump.packs`, `digest.shipped-tables`, `digest.colormap-lut`, `pin.player-crc`, `pin.block-crc`, `pin.fluid-crc`, `pin.portal-crc`, `pin.corpus-count`, `pin.kit-corners`, `pin.vanilla-iso-pose`
- **source** measured by perturbing ParityViews.java: 0 of 0 declared sees moved, and 11 declared blind held; the readers inside B37's write-path packages

Four suites that read the store to assert against it, and the two renderers behind them, whose output is markdown - the skill's reference files and the store's own README, neither of which any artifact digests. Listed here rather than cut out of B37, so a file ADDED to that package keeps answering with B37's whole list until somebody decides otherwise. A demotion answers for the paths that fired it and for no others, which is what makes this list safe to widen: a commit carrying one of these readers beside a real writer still plans the writer's bundle, and that pairing is the ordinary shape of work in this package rather than an edge case.

*Probe:* edit any one of them and capture any artifact B37 names; every stored byte is identical, and the only thing that fails is the suite that gates the file

## B40 - The root package's documentation declares nothing, so it is read by a reader and by no producer

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/package-info.java`
- **sees** -
- **blind** -
- **source** measured by perturbing package-info.java: 0 of 0 declared sees moved; the one match the blanket package-info glob had

This is the one package-info in the tree that sits in no package another rule already claims - the library root, whose own types are claimed by B19's renderer glob and whose sub-packages are claimed one by one. A rule rather than a no_reach glob because a blanket **/package-info.java entry was defeated on every other match, a rule having claimed the file first, so it read as a javadoc exemption while being one nowhere; and a javadoc edit inside a ruled package still plans that package's bundle, which is the rule-wins precedence working. This file has no such package below it to plan for, and what it reaches is nothing.

*Probe:* open the file: it carries a package declaration and javadoc and no member, and no artifact digests a javadoc

## B41 - The two manifests a member list separates are DECLARED in these two files, so editing either adds or drops rows with no producer having run

- **mode** select
- **triggers** `gradle/visual.gradle.kts`, `scripts/parity/manifest.py`
- **sees** `manifest.visual`, `manifest.player-raw`
- **blind** -
- **source** measured by perturbing manifest.py: 2 of 2 declared sees moved; correction to B30 and B31's claim that a toolkit or build-script change reaches nothing - these two files hold the membership the two cache/visual manifests are defined over, and the build file holds their producers' render defaults

manifest.visual and manifest.player-raw both take cache/visual as their source and are told apart by a member list rather than by a directory of their own. An edit to either reaches them because that member list is typed in these two files: the toolkit's member map holds the sub-directory names for both artifacts, and the visual script holds the registrations that decide which producers write those directories - visualSweepProducers for one, the player-raw aggregator's dependencies for the other. A producer names its own output directory a third time in its Java source, so a member those three disagree about is a directory nothing writes. Adding a name to any of those lists admits every file under that directory to the artifact and dropping one takes them away, so the edit REDEFINES the artifact rather than measuring it again: the rows move with no producer having run, the promoted baseline goes on describing the membership it was promoted over, and the next compare reports added or dropped rows rather than movers - a RED a promotion clears and a re-render does not. Membership is not the whole of what these two files reach. That script also carries each producer's render defaults - renderSize for projectionSmoke, blockRender3D, entityProjections, entityRender3D, itemDayCycle and itemRender2D, with ssaa, supersample, antiAlias and dayFrames beside it - so editing one of those literals hands the producer a different argv and re-renders that member's sub-tree the next time it runs, which moves stored rows while adding and dropping no member. Six of manifest.visual's eight members read a default typed in the build file, which is why both artifacts are PLANNED for an edit to either file rather than exempted from the edits that look like wiring: the plan states what the edit can reach and the capture is what says whether anything moved. The trigger takes each file whole because neither a Kotlin script nor a Python module offers a glob any sub-file address, and a narrower trigger reads correct until the day somebody edits a member list or a render default in the same commit as the wiring it was written to exclude. B30 and B31 keep their empty sees lists and the union does the rest, which is what keeps a wrapper bump or a JMH edit from planning a render.

*Probe:* add a directory name to one of the member lists and build that manifest either side: the file count moves with nothing having been rendered, and ParityIndexTest fails outright on the two visual lists disagreeing. For the other half, edit renderSize's default beside one producer and re-run it: the member count holds and its sub-tree's images move

## B42 - The tooling source set's own tests assert over hand-built bytecode and emit nothing

- **mode** select
- **triggers** `tooling/src/test/java/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead

Every test here drives hand-built ASM nodes, a ZipOutputStream jar under @TempDir, or reflection over the tooling classes themselves. None runs a flow, so none writes a shipped table or a flow log, and no stored artifact can see one change. The gate is the suite, which `check` schedules by shelling into the tooling wrapper, because a separate build is one this one compiles nothing of. This is the tooling half of what B33 says for src/test/java.

*Probe:* run ./gradlew toolingTest, then capture any artifact and confirm it is byte-identical

## B43 - The tooling build's own script decides what the flows emit and where

- **mode** select
- **triggers** `tooling/build.gradle.kts`, `tooling/settings.gradle.kts`
- **sees** `manifest.tooling-tables`, `report.diagnostics-log`
- **blind** -
- **source** declared from what the script wires; the flows resolve their classpath and output directory from it and from nothing else

The generators are a separate Gradle build, so its build script is what puts ASM and the renderer on their classpath, what registers each flow against its entry point, and what decides the directory a table lands in. An edit here moves emitted bytes with no walker having changed, which is the same reach B13 and B14 carry for the walkers themselves.

*Probe:* change the flow classpath or the output default and re-run a flow; the emitted table moves with no Java source having changed

## B44 - The tooling wrapper selects a Gradle version and emits nothing

- **mode** select
- **triggers** `tooling/gradle/**`, `tooling/gradlew`, `tooling/gradlew.bat`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead

A wrapper script and its distribution pin decide which Gradle runs the tooling build, not what any flow walks or writes. The gate for an edit here is that the build still starts, which `toolingTest` answers on every verification run. This is the tooling half of what B31 says for the renderer's own wrapper.

*Probe:* bump the wrapper distribution and re-run a flow; the emitted tables are byte-identical

## B45 - Client acquisition decides which bytes both the renderer and the generators read at all

- **mode** select
- **triggers** `client/src/main/java/**`
- **sees** `manifest.dump.vanilla`, `manifest.dump.packs`, `manifest.tooling-tables`, `report.diagnostics-log`
- **blind** -
- **source** declared from what the module writes; both the pack stack and the class walks resolve against the tree it extracts

This module downloads the client jar and lays out the extracted tree every other read in the repo starts from - the pack stack the dump serialises, and the classes the generator flows walk. A change to what it extracts or where it puts it therefore reaches both sides at once, which no other rule covers: B4 speaks for the pack readers over that tree, and B13/B14 for the walkers over the same jar, but neither for the acquisition itself. The renders are not on it - they read the pack stack, and a change that moved one would move the dump first.

*Probe:* change what extractClientJar streams out and re-run the dump and a flow; both move, because both read the tree it wrote

## B46 - The client build's script wires a leaf and emits nothing

- **mode** select
- **triggers** `client/build.gradle.kts`, `client/settings.gradle.kts`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead

The client module's build script declares its dependencies and its toolchain. Neither decides a byte any producer writes - what the module DOES is B45's claim, over its sources. It carries no wrapper of its own: both dependent builds resolve it through an included build rather than invoking it, so there is no third Gradle to select. The gate for an edit here is that both of those still compile, which `check` reaches through `test` and `toolingTest`.

*Probe:* bump a dependency pin and capture any artifact; every stored byte is identical

## B11a - Blocks and items are structurally immune to a DEPTH change: 0 of 1055 and 0 of 479 rows move

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/ModelEngine.java`, `src/main/java/lib/minecraft/renderer/engine/raster/DepthMath.java`
- **sees** -
- **blind** `sweep.block`, `sweep.item`
- **source** measured by perturbing ModelEngine.java: 0 of 0 declared sees moved, and 2 declared blind held; CLAUDE.md 'Depth: the contract'

Their coplanar pairs are exactly coincident, so both interpolation forms agree bit for bit and there is no crossing to find. This is the mechanism working rather than the gate missing them, which is what makes it a diagnostic discriminator when a rasterizer change moves something unexpected.

*Probe:* -Dasset.depth.range=N sweeps entity rows and leaves block and item at 0 moved

## B11b - The block and item immunity is to DEPTH only and does not generalise

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/engine/ModelEngine.java`, `src/main/java/lib/minecraft/renderer/engine/raster/DepthMath.java`
- **sees** `sweep.entity`, `sweep.block`, `sweep.item`, `sweep.armor`, `pin.player-crc`, `manifest.player-raw`
- **blind** -
- **source** measured by perturbing ModelEngine.java: 2 of 6 declared sees moved; CLAUDE.md 'Depth: the contract'; audit 09/G7

A coverage or texel-fetch change in the same file reaches blocks like anything else: bounding the fetch to the face's own UV rect moved 31 block rows, all better. So the block and item sums stay in SEES for this path and B11a is never a licence to skip them.

*Probe:* -Dasset.snap.grid=N and a texel-fetch perturbation both move block rows where -Dasset.depth.range=N does not

## B47 - A version declaration decides which rendering code runs, so bumping one can move any rendered byte

- **mode** select
- **triggers** `build.gradle.kts`, `gradle/libs.versions.toml`
- **sees** `digest.colormap-lut`, `manifest.fluid`, `manifest.player-raw`, `manifest.player-sheets`, `manifest.portal`, `manifest.visual`, `pin.block-crc`, `pin.fluid-crc`, `pin.kit-corners`, `pin.player-crc`, `pin.portal-crc`, `pin.vanilla-iso-pose`, `sweep.armor`, `sweep.block`, `sweep.entity`, `sweep.glint`, `sweep.item`, `sweep.player`
- **blind** `digest.shipped-tables`, `manifest.references`, `manifest.tooling-tables`, `pin.corpus-count`
- **source** measured by bumping the minecraft-library:text pin from 117775e to 172ed90: 13 of 199 manifest.visual rows moved, every one of them a render that draws text, and the other thirteen artifacts captured beside it held

These two files are where a dependency version is written down - ten strictly() pins in the build file and the third-party versions in the catalog, JOML's among them - and a version is a statement about which code a producer runs, not about which producer runs. So an edit here can move a rendered byte without a line of this repo's source changing, which is the opposite of what B31 says about the wiring it still speaks for. The four artifacts listed blind are the ones no library reaches: the shipped-tables digest and the corpus count are taken over bytes and fixtures that ship in this repo, and the reference manifest and the tooling tables are produced by two separate Gradle builds whose own dependency declarations live in their own files. The sees list is the mechanism's consequence rather than one perturbation's: a single pin was measured and the rest follow from a version swapping code, so narrowing it is a measurement somebody can take.

*Probe:* bump one strictly() pin to a sha jitpack serves, re-run the render bundle, and read which rows move

## B48 - A menu reaches the visual manifest and nothing else this store holds

- **mode** demote
- **triggers** `src/main/java/lib/minecraft/renderer/MenuRenderer.java`, `src/main/java/lib/minecraft/renderer/engine/compose/ChromeDecomposition.java`, `src/main/java/lib/minecraft/renderer/engine/compose/ChromeSlicer.java`, `src/main/java/lib/minecraft/renderer/engine/compose/Decoration.java`, `src/main/java/lib/minecraft/renderer/engine/compose/MenuLayout.java`, `src/main/java/lib/minecraft/renderer/engine/compose/MenuScreen.java`, `src/main/java/lib/minecraft/renderer/engine/compose/Stencil.java`, `src/main/java/lib/minecraft/renderer/engine/compose/Window.java`, `src/main/java/lib/minecraft/renderer/option/MenuOptions.java`, `src/main/java/lib/minecraft/renderer/option/slot/MenuSlot.java`, `src/test/java/lib/minecraft/renderer/visual/MenuRenderDriver.java`
- **sees** `manifest.visual`, `sweep.menu`
- **blind** `manifest.fluid`, `manifest.player-raw`, `manifest.player-sheets`, `manifest.portal`, `pin.block-crc`, `pin.fluid-crc`, `pin.player-crc`, `pin.portal-crc`, `sweep.armor`, `sweep.block`, `sweep.entity`, `sweep.glint`, `sweep.item`, `sweep.player`
- **source** measured by rewriting MenuRenderer, MenuOptions, MenuScreen, MenuLayout, Window and the visual driver together: manifest.visual alone moved and every other artifact the plan planned on those paths held; measured again on MenuRenderer alone, where the menu rows of manifest.visual moved and everything captured beside them held; and measured a third time for Decoration alone, by widening the arrow's extent by one Minecraft pixel and re-running the two menu producers - the sweep's crafting table went 0.0407 to 0.20 and its anvil 0.0000 to 0.16, three menu-render digests moved with them, and reverting restored all fifty-four producer outputs byte-identically. Nothing else in this store can load the class: Decoration is referenced by MenuRenderer, MenuLayout, MenuScreen and Window and by no other production file, and all four are trigger paths here. Stencil rides the same closure and is narrower still - it is package-private, so nothing outside this package can name it at all, and the two production files that do are Window and Decoration

A menu is drawn by one renderer over one option record and one layout, and no other producer in this store draws one. The five sweeps render entities, blocks, items, the player and its armour, and the glint sweep an overlay over an item; the four CRC pins are taken over renders of those same subjects; and the four other manifests hold fluid, portal and player-sheet output. manifest.visual is the only artifact that hashes what the menu driver writes, so it is the only one a menu can move. Three rules select on these same paths for reasons that are true of the directories rather than of these files - the engine glob, the option surface and the visual mains - and the demote is what narrows the answer, since a select rule's blind list subtracts nothing. The trigger list is verbatim paths and never a glob: the compose package holds the compositor, the timeline and the tooltip chrome, the slot package holds nine other slot enums, and the option package holds every option record there is, so a glob over any of the three would hide a sweep from a change that moves it.

*Probe:* perturb a menu geometry number - a band depth, a margin, a cell size - re-run the whole render bundle and read which artifacts move; the menu rows of manifest.visual move and nothing else does

## B49 - The parity vocabulary states where a claim applies and renders nothing

- **mode** select
- **triggers** `src/main/java/lib/minecraft/renderer/parity/**`
- **sees** -
- **blind** -
- **source** declares no store artifact, so its reason names the gate that answers instead; retention is SOURCE and no class file carries the descriptor

A new top-level sub-package of the renderer root is reached by no other rule's triggers, so it needs one of its own or the coverage check has a tracked file nothing speaks for. What it holds is the annotation a package or a type declares its own parity reach with and the three closed vocabularies that annotation names. Retention is SOURCE, and javac drops the descriptor before it writes a class file - so no compiled artifact carries a declaration, no renderer classpath is widened by one, and nothing on any producer's path can read one. What does read them is the planner, from source, when it resolves which artifacts a change set can move, which is why an edit here moves what a plan proposes to capture and never what a producer emits. The gate is ./gradlew test, which compiles the package and holds the roster to the renderers this library ships.

*Probe:* javap -v the compiled package-info of a package that declares one: no @Parity descriptor is in the constant pool. Capture any artifact either side of an edit here and every stored byte is identical

## Paths that reach nothing

Covered and reaching nothing is a different answer from "I do not know". A changed
path matching neither a rule nor one of these is `UNKNOWN`, and refusal R1 stops the
plan rather than guessing. A **rule wins** where both match, so an entry here speaks
only for paths no rule claims.

### `**/*.md`

Markdown is documentation and no producer reads one, so an edit moves no captured byte. Two of the files this glob speaks for are generated - the skill's artifacts.md and blindness.md - and ParityReferencesTest asserts they regenerate byte-identically, which is a gate in the fast suite rather than a stored artifact. The store's own README is generated too and does not reach this glob at all: B34 claims the directory it sits in, which is the rule-wins precedence doing its job.

*Probe:* edit a word in any tracked markdown file and capture any artifact; every stored byte is identical. The two generated references are the one place an edit is caught, and ./gradlew test is what catches them

### `notes/**`

Gitignored working notes - research packs, ledgers and probe tables. Nothing tracked reads one, so a note is not an input to anything a capture measures. It is the one glob here that matches zero TRACKED files by decision, which is why an orphan check over this list has to exempt it by name rather than by accident.

*Probe:* git ls-files notes/ comes back empty, so no capture and no producer can read one

### `.claude/**`

The skill and its pre-commit hook decide when the gate is CONSULTED and never what any producer emits, so no stored byte moves with them. Of the reference files under it the two generated ones are gated by ParityReferencesTest and the hand-written ones by review, and the toolkit the hook shells out to is gated by paritySelfTest. The hook itself has no automated test, so its own gate is a fixture payload run by hand - stated here rather than left as an omission, because it is the only automatic detector in the loop.

*Probe:* run the hook against a fixture payload and read its exit status; no parity artifact changes, and nothing in ./gradlew test fails when the hook is deleted outright

### `.gitattributes`

Line-ending and diff attributes are a checkout property. The toolkit writes every stored file LF itself, so what this file decides never reaches a captured byte.

*Probe:* check out the tree with the file removed and re-capture: the stored bytes are LF either way, because the toolkit normalizes on write rather than relying on the checkout

### `**/.gitignore`

An ignore rule decides what git tracks and no producer consults it. Both the root file and the harness's own are covered; the harness's deliberately leaves Loom's run directory unignored so a run/ that fills with config and logs shows up in git status, which is a statement about what a reader sees rather than about any rendered byte.

*Probe:* add a pattern and capture: no artifact's population changes, because every manifest walks an allowlist of member directories rather than asking git what is tracked

### `scripts/euler_reference_svg.py`

A standalone authoring script that regenerates one javadoc illustration. It is not imported by the toolkit, not invoked by any Gradle task, and its output is a comment.

*Probe:* run it and diff the tree: the only file it writes is the SVG inlined in EulerRotation's javadoc, and no artifact digests a javadoc

### `harness/COMMIT-MAP.tsv`

The old-to-new sha map recorded when the harness was imported as a subtree. It is a provenance record that nothing at build or render time opens, so it sits under harness/ without being part of what B29 speaks about.

*Probe:* grep the harness build for any read of it; there is none, and the client renders identically with the file deleted
