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
- `entityModels` -> `entity_models.json` + `entity_geometry.json` (ASM scan of vanilla client jar). Entry: `ToolingEntityModels.main` -> `EntityToolingContext.of(jar)` -> per-entity resolver fan-out (see `tooling/entity/`).
- `colorMaps` -> `color_maps.json` (vanilla biome colormap PNGs)
- `atlas` / `diagnoseAtlas` / `diagnoseAtlasTask10` -> `build/atlas/`

## Visual inspection (writes to `cache/visual/`)
Group `visual` - main() entry points live in `src/test/java/lib/minecraft/renderer/visual/`. Tasks: `blockRender3D -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2`, `itemRender2D -PitemId=...`, `bedParity`, `loreTooltip`, `stackCountBadge [-Plabel= | -Pdiff=A,B]`, `entityRender3D [-PentityId=... -PrenderSize=512]`, `fluidRenderer`, `portalRenderer`, `entityParityVanilla [-PentityId=...]`.

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

### Iso pose (locked invariants)
- Entities: `Projection.VANILLA_ENTITY.basePose()` = `(210°, 45°, 0°)` matching harness's `EntityFrameRenderer.ISO_ROTATION = rotationXYZ(210°, 45°, 0°)`. (`Projection` is the sole owner of these poses; `EulerRotation.STANDARD_*` is gone.)
- Blocks: `Projection.VANILLA_BLOCK.basePose()` = `(30°, 225°, 0°)` - distinct from entity iso on purpose.
- Entity iso transform chain has `det=-1` (chirality fix); 5 coupled invariants pinned together: iso constant, engine camera chain, kit emission winding, plane-cube culling, canvas-sizing helpers. The foundation test's "cross OPPOSES stored normal" invariant guards against accidental re-flipping.
- DO NOT touch `composeIsoTransform` / `Projection.entityIsoChain` (the shared entity iso prefix). Rotation-order swap is math-proven equivalent and an empirical retry regressed piglin 10.27 -> 184.34.

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
| Allowlist (`ACHIEVED_PARITY`) | top of that file, `Set<String>` |
| Block parity | `src/test/java/lib/minecraft/renderer/visual/TestBlockRender3D.java` |

**Allowlist policy**: only add when the static render looks natural - the bytecode-derived geometry alone isn't grounds; verify visually first.

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
