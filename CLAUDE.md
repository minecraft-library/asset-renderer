# asset-renderer

Headless renderer for Minecraft blocks/items/entities/fluids/portals. Outputs `ImageData` (static PNG or animated frames) via `Renderer<O>` impls. Group `lib.minecraft`, package root `lib.minecraft.renderer.**`.

## Build
- JDK 21 + **Vector API incubator** (`--add-modules=jdk.incubator.vector`). Wired into JavaCompile/Test/JavaExec/JMH in `build.gradle.kts` - missing it anywhere = class-not-found at load, not silent fallback.
- Deps: Gradle Kotlin DSL, `libs.versions.toml`. Strictly-pinned jitpack snapshots from `simplified-dev` (`collections`, `utils`, `image`, `gson-extras`, `client`), `simplified-api` (`mojang`, owns the Feign `MojangContract` for client-jar / textures fetches that `AssetPipeline` proxies), and `minecraft-library` (`text`). Bump by editing the version string.
- ASM 9.8 (Java 25 class files) - `VanillaTintsLoader` parses `BlockColors` from the extracted client jar.

## Tests
- `./gradlew test` - fast tests (excludes `@Tag("slow")`).
- `./gradlew slowTest` - hits network / filesystem cache (client-jar downloads, integration, parallelism, block-entity parity). Not up-to-date-cached.

## Tooling (re-run on Minecraft version bump)
Rewrites JSON in `src/main/resources/lib/minecraft/renderer/`:
- `blockTints` -> `block_tints.json` (ASM scan of `BlockColors`)
- `potionColors` -> `potion_colors.json` (ASM scan of `MobEffects`)
- `glintItems` -> `glint_items.json` (ASM scan of `Items` for `ENCHANTMENT_GLINT_OVERRIDE=true` - the always-foil items)
- `blockModels` -> `block_models.json` (ASM scan of block-entity model classes)
- `blockDefaults` -> `block_defaults.json` (ASM bytewalk of `registerDefaultState` + `createBlockStateDefinition` for each block's default state). Read at runtime by `BlockStateLoader` -> `Block.defaultStateKey`. Variants come from the vanilla blockstate JSON, not this file.
- `entityModels` -> `entity_models.json` + `entity_geometry.json` (ASM scan of vanilla client jar). Entry: `ToolingEntityModels.main` -> `EntityToolingContext.of(jar)` -> per-entity resolver fan-out (see `tooling/entity/`). `entity_models.json` is the **normalized model form** (see below); `EntityRuntimeJsonWriter` builds the flat per-entity tables in-memory and `EntityFamilyJsonWriter` groups them into it. Worn-armour meshes ride `entity_geometry.json` like every other mesh (see below) - there is no armour file.
- `colorMaps` -> `color_maps.json` (vanilla biome colormap PNGs)
- `atlas` / `diagnoseAtlas` / `diagnoseAtlasTask10` -> `build/atlas/`

## Visual inspection (writes to `cache/visual/`)
Group `visual` - main() entry points live in `src/test/java/lib/minecraft/renderer/visual/`. Tasks: `blockRender3D -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2`, `itemRender2D -PitemId=...`, `itemDayCycle [-PrenderSize=256 -PdayFrames=64]`, `portalRenderer` (incl. sub-tick smooth variants), `bedParity`, `loreTooltip`, `stackCountBadge [-Plabel= | -Pdiff=A,B]`, `entityRender3D [-PentityId=... -PrenderSize=512]`, `fluidRenderer`, `entityParityVanilla [-PentityId=...]`.

## Sampling between ticks (`subTickSteps`)
`Timeline.bake` lives on `Timeline` (not `TickTimeline`) and reads each frame's instant off the millisecond axis, so a schedule whose instants are **off** the tick lattice can still drive the one shared bake loop. The integer tick handed to a draw is that instant floored - exact for every tick-native schedule, since the ms axis *is* the tick lattice there.
- `AnimationOptions.subTickSteps` (default `1`) subdivides each step: `Timeline.gameTime(start, frames, ticksPerFrame, subTickSteps)` returns a `SubTickLoop` covering the same span of game time at the same speed. The step's playback delay is shared across its sub-steps with the leftover milliseconds going to the earliest frames (3 steps → `17/17/16`), so a tick still spans exactly 50 ms. `subTickSteps <= 1` returns the whole-tick schedule itself, so the default path is untouched.
- **`SubTickLoop` is deliberately not a `TickTimeline`.** Off-lattice instants have no honest `tickAt`, and claiming one would break the `millisAt(f) == tickAt(f) * 50.0` identity (`TimelineTest`) that makes the lattice checkable.
- A draw that needs the fraction implements `RasterPass.ContinuousRasterizer` (`target, tick, ageInTicks`); everything else keeps the 2-arg `FrameRasterizer`. That separation is what keeps `Textures.*AtTick` on `int` - vanilla's texture interpolation is itself whole-tick, so a fraction there would be *anti*-parity.
- **Only the portal uses it.** A texture flipbook has no state between its frames, so subdividing its ticks would bake duplicates. Use it for a subject whose appearance is a continuous function of time.
- Gate: `./gradlew portalRenderer` writes `*_animated` and `*_animated_smooth` pairs. Every Nth frame of the smooth strip must be byte-identical to the plain strip's frame, and the frames between must be genuinely intermediate (≈1/3 and 2/3 of a tick's motion), not duplicates.

## Time-driven item icons (the clock)
`minecraft:time` is a normalized **sun angle**, not a linear day fraction. In 26.1 the curve is data-driven: `data/minecraft/timeline/day.json` declares a single `360 -> 0` degree keyframe pair anchored at **noon** over a `24000`-tick period, eased by a symmetric cubic Bezier `[0.362, 0.241, 0.638, 0.759]`. `option/SunAngle` reproduces it float-for-float; a linear ramp is off by more than two clock faces at sunrise.
- **Tick 0 is noon and yields exactly `+0.0f`.** That is load-bearing twice: it keeps `gui().atTick(0)` equal to `gui()`, so `ItemRenderer.resolveRenderItem`'s baked fast path survives (a `-0.0f` would silently cost *every* item its fast path and its baked tints), and it makes frame 0 the instant the item parity references are pinned at.
- `AnimationOptions.Schedule.GAME_TIME` (via `Timeline.schedule`) advances world time between frames while holding each for one tick of wall clock - a day in 3.2 s rather than 20 real minutes. `deriveTimeline` also probes the item's tree (`ItemModelNode.timeDispatchSteps`) to set the cadence from its own table; that search must walk **all** branches, since the clock's dispatch sits behind a `context_dimension` select no offline context can evaluate.
- Uniform tick sampling covers **60 of 64** faces (4 repeats, 4 skips). That is the eased curve - the sun lingers near noon and midnight - not a defect.
- **`context_dimension` is pinned to the overworld** (`ItemModelContext.DIMENSION_OVERWORLD`), the one property besides `display_context` answered outright rather than left unevaluable. `clock.json` is the only one of 1506 vanilla item trees that selects on it, and leaving it unevaluable was not the neutral choice it looked like: the tree would degrade to its **fallback**, which is not a degradation branch but the **Nether/End** one, where vanilla clocks spin randomly (`source: random`, against the overworld case's `source: daytime`). Pinning it keeps the branch matched to the daytime input this renderer computes. It is a fixed answer, not a record component, so `gui()` / `isNeutral()` are untouched.
  Byte-neutral when landed: the branches' 65 entries are identical entry-for-entry at the same `scale: 64.0`, differing only in `source` - which is not parsed at all (`ItemModelNodeDeserializer` reads `property`/`scale`/`target`/`index`/`entries`/`fallback`). Both the render sweep and `parityDump` were unmoved, the latter mattering because `ItemIndexBuilder.addDispatchOnlyItems` resolves trees at pipeline time. **If `source` is ever modelled, this pin is what keeps the clock working** - without it the renderer would honour `random` and resolve a different face per render.
- LOOK gate: `./gradlew itemDayCycle` - clock must sweep noon -> dusk (sun right) -> midnight (moon centred) -> dawn (sun left); compass and sword controls must stay at **one distinct frame** (a compass is a bearing from its holder, never time-driven).

## JMH
`./gradlew jmh` with `-PjmhWarmup` (3), `-PjmhIters` (5), `-PjmhForks` (2), `-PjmhInclude=<regex>`, `-PjmhProfilers=gc,stack`. JVM forks get `-Xmx2g` + Vector module. Benches in `src/jmh/java/lib/minecraft/renderer/bench/`.

## Skip these
- `cache/` - runtime texture-packs, test-render output, parity-analysis output. Excluded from IDE module and git. Do not grep/scan.
- `texturepacks/` - same.
- `build/` - Gradle output.
- `.jmh/` - manually-captured JMH session outputs (gitignored scratch).
- Fonts live in sibling `minecraft-text` repo now; `./gradlew fonts` is gone from here.

## Developer scripts
Live in `scripts/` (not bundled into the JAR):
- `scripts/euler_reference_svg.py` - regenerates the SVG embedded in `EulerRotation` javadoc.
- `scripts/parity_analysis/` - tooling that classifies per-entity diff panels into failure clusters. Outputs to `cache/parity_analysis/` (gitignored). See `scripts/parity_analysis/README.md`.

## Parity / vanilla-reference-harness

The sibling [vanilla-reference-harness] drives the actual MC client to render every block + every entity (with variants) at a locked iso pose. Those PNGs are the byte-stable ground truth that `TestEntityParityVanilla` diffs the Java pipeline against. **Harness internals (mixins, bounds walker, state-extraction patterns) live in [vanilla-reference-harness/CLAUDE.md].** This section is the asset-renderer-side knowledge.

### Two pipelines
1. **Java** (vanilla model classes via ASM bytecode walk) - `entity_models.json` + `entity_geometry.json` from the `entityModels` task, consumed at runtime by `EntityRenderer` via `pipeline/loader/EntityModelLoader`.
2. **Vanilla reference** - drives real MC client via the harness; output at `cache/asset-renderer/vanilla/26.1/references/{blocks,entities}/`. **Ground truth.**

### Entity model form (`entity_models.json`)
`entity_models.json` is the **normalized model form**: one entry per base entity (`minecraft:wolf`, not a row per colour), keyed under a top-level `models` map - 90 models in 26.1. Each model carries `geometry_ref`/overlays once, plus:
- **`axes`** - orthogonal dimensions, ALL option-encoded in 26.1. `variant` (colour coat, tropical-fish shape) is carried by the 14 models that have it, `state` (wolf wild/tame/angry) and `age` (adult/baby - `age.baby.geometry_ref` points at the dedicated `Baby<X>Model` mesh) by the models that need them. Option axes are **NOT** id-encoded - they resolve at render from `EntityAppearance` (via `Entity.resolve`).
- **Id-encoding is a dead branch, not a live shape.** The tooling HARDCODES `id_encoded: false` (`EntityVariantAxisResolver:114,272`), so all 14 variant axes ship `false` and the loaded `entityIndex` is exactly **90 rows keyed by plain entity id**: no `minecraft:<id>_<opt>` pseudo-ids are ever synthesised. `variant_of` appears **zero times** in the JSON - it is in-memory only (`EntityIndexBuilder.groupMembership` builds the map at load), never a key on disk. The `idEncoded` branch survives in `EntityIndexBuilder.readDefinition`/`groupMembership` but is unreachable for tooling-generated data.
- **`layers`** - conditional overlays (collar: `tint_by: collar_color`, an option-sourced tint) plus the worn-armour row (`id: "armor"`, see below).
- Per-variant option `textures` hold `{wild, tame, angry, baby}` texture refs; `group_of` carries cross-entity groupings (mooshroom -> cow).

`pipeline/loader/EntityModelLoader` is a thin orchestrator: two pure `document.as` reads - `entity_geometry.json` -> `Map<String, EntityModelData>` and `entity_models.json` -> the raw `pipeline/index/RawEntityModelsFile` DTO tree - handed to `pipeline/index/EntityIndexBuilder.assemble`, which expands the model form into the flat `Map<String, Entity>` (asset.`Entity`) the renderer consumes. The geometry join, the mesh surgery (hidden-bone strip, `retainExactParts` subset, `grow` inflate + the auto-emitted `0.001` depth-clearance bump), the axes pivot, the per-variant sub-`Entity` fold, and the cross-entity grouping all live in `EntityIndexBuilder`; the leaf decodes (hex tint, `blend` token, texture strip, transform ops) happen there too, where the `Diagnostics` handle is available. `Entity` carries `Entity.Axes` (`stateTextures` / `babyModel` / `largeShape` / `sizeModels` / `sizeScales` / `variants`) + `Entity.Layers` (`collar` / `equipment` / `markings` / `humanoidArmor`), plus `withoutBlockOverlays()` and `resolve(EntityAppearance)` (the render-time axis fold). `EntityAppearance` carries the `variant` / `state` / `carried` / `age` / collar-colour / sheared / `size` / `pattern` render selections. **Baby renders skip overlays + block-overlays** (adult geometry would render adult-sized around the smaller baby body). Baby texture source chain: variant-option `baby_texture` -> renderer `isBaby` binding -> `<adult>_baby` naming convention. Schema contract + wolf example in gitignored `notes/entity-family-schema.md` + `notes/sample_model.json`.

### Worn armour (a `layers[]` row, no sibling file)
Vanilla never derives worn armour from the wearer's own mesh - `LayerDefinitions` builds a handful of `ArmorModelSet`s and hands each renderer the one its subject wears, so a skeleton's narrow limbs and a giant's scaled-up body dress in the same boxes. **There is no armour file.** The shell rides the wearer's `layers[]` armour row like any other mesh in the pipeline:
```json
{ "source": "HumanoidArmorLayer", "layer_index": 3, "id": "armor",
  "overlay": { "geometry": "HumanoidModel#createBaseArmorMesh",
               "grow": { "inner": 0.5, "outer": 1.02 } } }
```
`id: "armor"` identifies the row, `overlay.geometry` points into `entity_geometry.json` (so `GeometryRefClosureTest` covers it both ways, forward and reverse, with no special case), and `overlay.grow` carries the set's two layer deformations. **The payload lives in `overlay` because every `layers[]` row's does** - collar (`texture`), markings (`texture_by` / `textures_by_value`), equipment (`geometry` / `layer_type` / `material_assets` / `default_material`) - so `RawLayer` stays a three-member routing shell (`id` / `when` / `overlay`) and armour reuses `RawLayerOverlay.geometry` rather than minting row-level members only it would set. 14 rows over **3 distinct meshes** in 26.1: `HumanoidModel#createBaseArmorMesh`, `ArmorStandArmorModel#createBaseMesh`, `ZombieVillagerModel#createBaseArmorMesh`.
- **The mesh is registered ungrown, and the two deformations travel on the row.** `ArmorMeshIndex` walks `createRoots` for the registrations (set field, base mesh factory behind the set factory's first `INVOKEDYNAMIC`, and the call site's two `CubeDeformation`s) and `EntityLayersResolver.armorRow` registers a plain `GeometryRequest.overlay(..., 64, 32, NO_GROW)` in the shared manifest. Each cube then carries only its own `CubeDeformation.extend` (`hat +0.5`, legs `-0.1`, zombie-villager `body`/legs `+0.1`), and `ArmorKit.armorBoxes` sums `deformation + cube.grow` at render - the same two operands in the same order the parser used, so it is bit-identical to baking it. That collapse is what makes the piglin family (identical to the generic set bar its outer `1.02`) **share** the generic geometry entry instead of duplicating it.
- **Selection is data**: `EntityLayersResolver.resolveArmorMesh` resolves the `ArmorModelSet` field name leaf-first along the renderer's constructor chain, falling back to the armour set among the registration lambda's `ModelLayers` references (the piglin family takes its set as a constructor argument, so no field along the chain names it). All 14 armoured entities resolve one. The name is a lookup key into `ArmorMeshIndex` only - it never ships.
- **Being armoured IS carrying a resolved shell.** `Entity.Layers.humanoidArmor` is one `Optional<Entity.HumanoidArmor>` (mesh + `innerGrow` + `outerGrow`); there is no separate classification flag a wearer could hold while its mesh failed to resolve. A walk failure therefore drops that wearer off `HumanoidArmorRosterTest`'s pinned 14 and fails loudly instead of falling back to a shared mesh.
- **The armour mesh bypasses the entity mesh surgery.** `EntityIndexBuilder.humanoidArmorOf` joins `geometries.get(coord)` raw - no hidden-bone strip, no `retainExactParts` subset, no auto-emitted `0.001` depth-clearance bump. The shell is a shared set vanilla hands the wearer, not a derivative of the wearer's own mesh.
- **The whole-mesh `MeshTransformer.scaling`** three sets are registered through (giant `6.0`, husk `1.0625`, wither skeleton `1.2`) is deliberately **not** carried: vanilla applies the same transformer to those wearers' body layers, and `EntityArmorFrame` already reads that scale off the wearer's torso bone - carrying it here would apply it twice.
- Slot pruning stays in `ArmorKit.SLOT_PARTS`, mirroring vanilla's `ADULT_ARMOR_PARTS_PER_SLOT`. A helmet keeps its part **and that part's children**, which is what puts the head's second `hat` box on a helmet; the other three slots keep exactly the parts they name.
- **Adult sets only.** Vanilla's baby sets take a third `PartPose` argument and a different base mesh (an extra `waist` part, its own unwrap); the walk filters them out by that descriptor, and babies still wear their own bone boxes.

### Iso pose (VANILLA_ISO + renderer-owned facing)
- All iso subjects (block, fluid, portal, player, entity) share `Projection.VANILLA_ISO` = `(30°, 225°, 0°)` + `Lens.ISOMETRIC_BLOCK` (vanilla's `display.gui` pose/scale, technically a dimetric). It is **facing-neutral** - presents the model's `-Z` side. (`Projection` is the sole owner of these poses; `EulerRotation.STANDARD_*` is gone.)
- **Facing is per-renderer**, applied as a model-to-world `Placement` (see `engine.camera.Placement`, composed by `ModelEngine` as `pose · placement · modelSpin`): block/fluid/portal = `IDENTITY`; player = `R_Y(180)`; entity = `R_Y(180)·flip180 = R_Z(180) = diag(-1,-1,1)` (which also un-flips its Y-down model). The camera is a plain **det=+1** display pose; the entity's canvas-fit measures its silhouette through `ModelEngine.orient(spin)` (= `pose · ENTITY_FACING · spin`, the exact render orientation) and hands it to `ModelEngine.rasterizeFitted` via a `FitRequest` (see below). This is what lets any projection be swapped in and still present the subject's front, upright.
- The entity's harness angle `(210°, 45°, 0°)` (`EntityFrameRenderer.ISO_ROTATION`) survives only as `EntityGeometryKit.ENTITY_ISO_LIGHTING` - the plane-cube lighting frame - decoupled from the camera pose. The old fused `det=-1` `entityIsoChain` / `ENTITY_ISO` assembly are **deleted**; the kit is de-flipped (emits Y-up geometry, det=+1 internally).
- Byte-identity is pinned by `VanillaEntityTransformGoldenTest` (the `VANILLA_ISO` pose 16 floats + kit-fixture corners) and `EntityGeometryKitTest` - whose winding invariant is now **"emit-order cross AGREES with the stored normal"** (the kit is det=+1 internally; the chirality reflection re-enters via the `Placement`, so screen-space cull winding is unchanged). Run both before/after any kit or camera change; the entity parity sweep must hold too.

### Canvas fit (unified: player + entity, one authority)
`ModelEngine.rasterizeFitted` + `engine.camera.FitRequest` are the single fit path for player and entity (block/fluid/portal render a unit cube at fixed scale, no fit). The kit emits **fit-neutral** geometry; scale + centring live only in the engine's `prepareFit`, forking on the request mode and the lens kind:
- `FitRequest.autoFill(fill)` - engine measures the triangle silhouette and fills `fill` of the canvas. **Orthographic** bakes a 3D `scale(fit)` (keeps the depth frame - `DEPTH_EPSILON` is not scale-invariant); **perspective/oblique** carry a 2D post-projection `Fit2D`. Used by the player and by the entity's perspective/oblique path (fed **unit-normalized** geometry via `EntityGeometryKit.unitFit` so foreshortening stays well-behaved). This is what fits long entities (cod) uncropped under PORTRAIT / cavalier / cabinet / military - a 3D model-scale fit could not correct strong foreshortening in one pass.
- `FitRequest.nativeScale(ndcScale, projectedBounds)` - caller supplies its native pixels-per-block scale + a pre-measured **alpha-tight (optionally family-unioned)** silhouette box (measured through `ModelEngine.orient`); engine bakes the scale in 3D and centres the box midpoint in screen space. This is the entity's **orthographic VANILLA_ISO** path; parity is byte-stable (re-baseline was a no-op, max drift +0.004, snap-absorbed). Byte-identity of the player auto-fill arms is pinned by `PlayerRasterizeFittedGoldenTest`.

### JOML factory conventions (load-bearing)
JOML's `Quaternionf` has two Tait-Bryan factories with OPPOSITE application order. Vanilla uses both:

| Factory | Quaternion product | Visual application to `v` | Vanilla site |
|---|---|---|---|
| `rotationZYX(z, y, x)` | `q_z·q_y·q_x` | **X first**, then Y, then Z | `ModelPart.translateAndRotate` (bone rotations) |
| `rotationXYZ(x, y, z)` | `q_x·q_y·q_z` | **Z first**, then Y, then X | GUI `display.*` poses (block icon, harness `ISO_ROTATION`) |

The factory NAME orders the quaternion product; application order to `v` is REVERSED because `q · v · q^-1` composes right-to-left. Mixing these up was the Round 5 bone-rotation bug.

Row-form equivalents (this codebase's `v_row × M`; `Matrix4f.createRotationX(θ)` produces a visual `+θ` X-rotation):
- **Bone rotations** (`rotationZYX`, X-first): `createRotationX(pitch).multiply(createRotationY(yaw)).multiply(createRotationZ(roll))` - locked in `EntityGeometryKitJava.pivotCenteredRotation`.
- **GUI display poses** (`rotationXYZ`, Z-first): `createRotationZ(roll).multiply(createRotationY(yaw)).multiply(createRotationX(pitch))` - used by `Camera.fromPose` (the `display.*` pose builder, assembled into named poses by `Projection`'s `VANILLA_*` members).

### Foundation invariants (locked by unit test)
`EntityGeometryKitTest` pins seven invariants on a single-bone single-cube fixture. The load-bearing one is **emit-order cross product ⋅ stored normal > 0**: triangles must be wound so their geometric normal agrees with the stored normal, camera- and projection-independent. Catches: removing/adding kit `FLIP_Y` without updating winding-reversal; changing UV-permutation arrays without UP↔DOWN face swap; breaking the atlas layout coefficients in `EntityFace.defaultUv`.

Run before/after any kit refactor:
```
./gradlew test --tests "lib.minecraft.renderer.engine.kit.EntityGeometryKitTest"
```

### Parity test entry points
| Entry | Path |
|---|---|
| Entity parity sweep | `src/test/java/lib/minecraft/renderer/visual/TestEntityParityVanilla.java` (run via `entityParityVanilla` task) |
| Block parity | `src/test/java/lib/minecraft/renderer/visual/TestBlockRender3D.java` |

The parity sweeps are diagnostic reports (mean ARGB delta + per-subject vanilla/java/diff PNGs, ranked ascending), not pass/fail gates. When landing a new entity/axis, verify it renders naturally by LOOKing at the PNG - bytecode-derived geometry alone is not grounds.

### Re-render vanilla references
```bash
./gradlew :asset-renderer:renderVanillaReferences                              # full sweep ~1m25s warm
./gradlew :asset-renderer:renderVanillaReferences \
  -PrefharnessTargets=minecraft:cow,minecraft:zombie                           # subset
./gradlew :asset-renderer:renderVanillaReferences \
  -Drefharness.pixelsPerBlock=512 -Drefharness.maxCanvasSize=2048              # override harness defaults
```
Re-rendering refreshes ground truth; it doesn't fix asset-renderer regressions. Re-render only on MC version bumps OR when a harness fix changes the canonical pose.

### Vanilla source lookups
The asset-renderer cache holds extracted MC client classes for offline `javap`:
```
cache/dragon-extract/
├── net/minecraft/client/renderer/entity/        # *Renderer classes
├── net/minecraft/client/renderer/entity/state/  # *RenderState classes
├── net/minecraft/client/renderer/entity/layers/ # RenderLayer subclasses
├── net/minecraft/client/model/                  # *Model classes (setupAnim)
└── net/minecraft/world/entity/                  # entity classes
```
Recipe: `cd cache/dragon-extract && javap -c -p <path/to/Class>.class | head -120`. Multi-method classes: `grep -A 60 "method signature"` after `javap -c -p`. Bridge methods (4-param variants) typically just call the narrowed (5-param-equiv) ones.

### Per-renderer override gotchas
When porting a new entity, ALWAYS check if its renderer overrides `setupRotations` or has a scale override - replicate in the asset-renderer kit's transform chain.

- **`setupRotations` overrides (14 in 26.1)**: ArmorStand, Cat, Cod, Drowned, Fox, IronGolem, Panda, Phantom, Pufferfish, Salmon, Shulker, Squid, TropicalFish. Notable: `SquidRenderer.setupRotations` adds `translate(0, -1.2, 0)` after standard ops; without it tentacles fall below canvas.
- **`scale(state, ps)` overrides**: WitherBoss → 2×, Giant → 6×, Zoglin (when `isBaby`) → ~0.5×.
- **`MeshTransformer.scaling(F)` (LayerDefinition-time scale)**: vanilla wraps some `createBodyLayer()` outputs. Bytecode-derived semantics: `pose.scaled(F).translated(0, 24.016*(1-F), 0)` - NOT uniform scale. Naive `multiply pivot+origin+size by F` bake regresses (wrong anchor at origin vs `y=24.016` feet; UV double-application because cube face UVs come from `cube.size` via `EntityFace.defaultUv`). Catalog (26.1): `PolarBearModel` 1.2, `HappyGhastModel` 4.0, `GhastModel` 4.5, `GuardianModel` (`ELDER_GUARDIAN_SCALE` static field) 2.35. Currently covered via `RENDERER_SCALE_OVERRIDES` workaround (uniform vertex scale at screen-midpoint anchor) - close-enough for flat-hierarchy entities, diverges from vanilla's feet-anchor for off-center geometry.

### Session-refresh checklist
1. `git log --oneline master..HEAD | head -30` for branch state.
2. Look at a single entity's failure: `cache/visual/entity-parity-vanilla/<entity_id>/diff_panel.png`.
3. Run single-entity parity: `./gradlew entityParityVanilla -PentityId=minecraft:X -q`.
4. Pixel-level debug: `-Dasset.entity.pixel.dump=x0,y0,x1,y1` and `-Dasset.entity.bounds.dump=true` system properties on the parity task. Walk back from the WRITE log to the texel + shade + blend that produced the mismatch. (All custom asset JVM flags live under `asset.*` and auto-forward to every JavaExec/Test fork via the global forwarder in `build.gradle.kts` - new flags need no per-task wiring.)
5. For vanilla source lookups, `javap` the relevant class in `cache/dragon-extract/` (recipe above).

[vanilla-reference-harness]: ../vanilla-reference-harness
[vanilla-reference-harness/CLAUDE.md]: ../vanilla-reference-harness/CLAUDE.md
