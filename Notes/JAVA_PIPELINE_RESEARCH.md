# Java Pipeline Parity Research

Branch: `research/java-pipeline-parity`. The parallel bedrock pipeline was purged
in Task #36 (2026-05-15); this work targets `EntityRenderer` (the canonical name
since the rename) exclusively against the vanilla-reference-harness ground truth.

If the entity-models JSON files (`entity_geometry.json`, `entity_models.json`,
`entity_geometry_handedits.json`) need to diverge from current contents to better
match the harness output, **that is the correct outcome**. Source of truth is the
harness output PNGs at `cache/asset-renderer/vanilla/26.1/references/entities/`,
NOT the existing JSON.

---

## Resume here (2026-05-16 pt 3, post-Tasks #38-42)

**Buckets: <1: 51 / <5: 86 / <20: 97** (Tasks #38 enderman/spider/breeze
emissive-overlay inflate, #39 ghast tentacle MT scaling, #40 generalized
proc-loop MT post-pass, #41 emissive blend ADD->NORMAL + ender_dragon
overlay, #42 alpha-tight UV walker off-by-one in both kit and harness).

All 14 canaries bit-stable or improved. Spider canary moved lower as
requested (0.57 -> 0.26).

**Task #38 emissive eye Z-fight (2026-05-16 pt 3)** -
`ToolingEntityModels` auto-emits `inflate: 0.001` on any emissive
overlay sharing the base entity's `geometry_ref` (cleared the
`ModelEngine.depthFails` equal-Z reject). Enderman 5+ -> 1.86,
breeze/cave_spider/phantom/spider eye-overlays land cleanly. Spider was
accidentally fine before due to FP noise on its rotated leg bones;
emissive auto-inflate is the principled fix.

**Task #39+#40 ghast tentacle MT scaling (2026-05-16 pt 3)** - the
shared block-entity Parser's `applyMeshTransformerScaling` bakes
`MeshTransformer.scaling(F)` into the linearly-parsed bones (ghast body
at `0 * 4.5 + 24.016 * (1-4.5) = -4.856`), but procedurally-emitted
bones added by `EntityProceduralLoops.augment()` were missed.
Task #39 hardcoded F into `applyGhastTentacles`; Task #40 generalized
as a post-pass that snapshots bone JsonObject references before
augment(), then scales every applier-emitted bone using whatever F any
pre-existing bone carries. Ghast 120.08 -> 0.92, into `<1` bucket.
Removed the redundant `geometry.guardian_elder` handedit (681 lines)
and `minecraft:elder_guardian` override.

**Task #41 emissive blend ADD -> NORMAL (2026-05-16 pt 3)** -
`ModelEngine.selectBlendMode` returned `BlendMode.ADD` for emissive
triangles based on a comment claiming vanilla's `RenderType.eyes` used
`glBlendFunc(SRC_ALPHA, ONE)`. **Wrong.** Vanilla's
`RenderPipelines.EYES` uses `BlendFunction.TRANSLUCENT`
(`SRC_ALPHA, ONE_MINUS_SRC_ALPHA`). At `alpha == 255` that's a clean
REPLACE - the eye texel replaces the underlying lit pixel directly.
Java was producing `lit_skin + eye_texel` (enderman pixel
`(255,144,255)` = lit-skin `(51,144,5)` + eye `(204,0,250)`,
saturating R/B); vanilla produces pure `(204,0,250)`. Fixed: emissive
triangles use `BlendMode.NORMAL`. Added manual
`minecraft:ender_dragon` overlay in `entity_models_overrides.json`
(EnderDragonRenderer dispatches eye via inline `submit()` not
`addLayer()`, so the resolver missed it). Targets: enderman
1.86 -> 0.57, ender_dragon 0.53 -> 0.35, spider (canary) 0.57 -> 0.26.
Bonus phantom 0.38 -> 0.30, cave_spider 0.62 -> 0.31.

**Task #42 alpha-tight walker off-by-one (2026-05-16 pt 3)** -
`pxMax = floor(uMax * W)` over-included the next-row texel when
`uMax * W` landed on an exact integer boundary. For HumanoidModel
outer-layer sleeves with `vMax = 48/64 = 0.75` exactly, this admitted
row 48 which sits in the adjacent base-layer's UP-face UV region;
otherwise-transparent sleeve cubes "looked" opaque and padded the
bounds by 5 pixels per side on piglins / wardens / skeletons.
**Fix in both pipelines**: `pxMax = ceil(uMax * W) - 1` (and `pyMax`
symmetrically), in `EntityGeometryKit.contributeFaceAlphaTight` AND
the sibling `vanilla-reference-harness EntityFrameRenderer
.contributePolygonExtents`. Baselines re-rendered.

After fix: canvases shrank (`warden 498x905 -> 476x905`,
`skeleton 193x512 -> 182x512`, `skeleton_horse 504x482 -> 461x482`,
piglin family `232x534 -> 227x528` / `235x534 -> 230x528`). Some
deltas grew slightly because the same diff-pixel count divides over a
smaller silhouette (`warden 0.80 -> 0.83`, `skeleton 6.16 -> 6.55`,
`skeleton_horse 9.16 -> 9.95`, `piglin 9.93 -> 10.27`,
`piglin_brute 10.69 -> 11.31`) - NOT regressions, just metric
rebalancing on tighter silhouettes.

**Residual piglin canvas gap = yaw rotation composition mismatch**
(deferred to its own task; see "Open task" below). Even with both
walkers fixed, piglin/piglin_brute still have a 3-px canvas-width
gap (java 227, vanilla 230); zombified_piglin / silverfish canvases
now match exactly. The kit's `EntityRenderer.composeIsoTransform`
produces a row-vector composition equivalent to applying R_Y(45)
then R_X(210), but vanilla's pose-stack composes (column-vector)
R_X(210) then R_Y(45) then R_Y(180) from `LivingEntityRenderer
.setupRotations`. Net X-projection formulas:
 - Ours: `X_out = 0.707x - 0.707z` (no Y contribution)
 - Vanilla: `X_out = 0.707x + 0.354y + 0.612z` (Y leaks into X
   via pitch-then-yaw chain)
On tall-Y entities like piglins, vanilla's `+0.354y` term adds 3-4
canvas pixels of X-extent that ours doesn't have.

## Open task - align composeIsoTransform with vanilla's pose-stack chain

`EntityRenderer.composeIsoTransform` was authored to mirror
`IsometricEngine.entityStandard`'s row-vector camera matrix, NOT
vanilla's column-vector `LivingEntityRenderer.submit` pose-stack
composition. The two evolved separately and the canaries (cod /
polar_bear / etc.) were tuned under our chain, accepting it as
"correct" even though it produces a slightly different iso projection
than vanilla.

**Vanilla's column-vector chain** (rightmost applied first):
```
v -> T(0, -1.501, 0) -> S(-1,-1,1) -> R_Y(180) -> R_X(210) -> R_Y(45) -> S(1,1,-1)
```

**Equivalent in row-vector** (leftmost applied first, after transpose
and rotation-sign negation):
```
v -> S(-1,-1,1) -> R_Y(-180) -> R_X(-210) -> R_Y(-45) -> S(1,1,-1)
```

After simplifying `S(-1,-1,1) * R_Y(180) = S(1,-1,-1)`:
```
v -> S(1,-1,-1) -> R_X(-210) -> R_Y(-45) -> S(1,1,-1)
```

**Our current chain**:
```
v -> S(1,-1,-1) -> R_Y(45) -> R_X(210) -> S(1,-1,-1)
```

Three differences:
1. Rotation order: ours applies R_Y first, vanilla applies R_X first.
2. Rotation angles: vanilla uses negated angles (R_X(-210), R_Y(-45)).
3. Trailing scale: ours `S(1,-1,-1)`, vanilla `S(1,1,-1)`.

**Why not just swap?** I tried swapping R_X/R_Y order in
`composeIsoTransform` alone (without rebalancing surrounding scales)
- canvas wrecked to 348x497 vs vanilla 235x534. The full refactor
needs to coordinate scale flips with rotation angles to land on the
right combined transform.

**Scope:** `EntityRenderer.composeIsoTransform` +
`IsometricEngine.entityStandard` + likely the block / item
`IsometricEngine` paths since they share the engine camera matrix.
Need canary verification after - this touches every entity render.

**ROI:** estimated 10-15 delta off piglin family (currently
`piglin 10.27`, `piglin_brute 11.31`) once chains match. Other
entities may shift slightly too; canary set will need re-baselining.
Risk: high. Defer until other groups have been worked.

## Resume here (2026-05-16 pt 2, post-Task #31 sheep tint + tooling generalization)

**Buckets: <1: 49 / <5: 85 / <20: 96** (Task #31 sheep tint landed +
texture resolver entity-id match). polar_bear allowlist preserved. All
14 canaries bit-stable (cod 0.56, spider 0.57, dolphin 0.46, warden
0.80, blaze 0.91, creeper 0.92, horse 1.01, polar_bear 1.08, stray
1.92, cat 2.58, bogged 2.84, skeleton 6.16, skeleton_horse 9.16,
guardian 0.54).

**Task #31 sheep wool tint (2026-05-16 pt 2)** - `sheep 21.83 -> 1.30`.
Root cause: vanilla's `SheepWoolLayer.submit` passes
`state.getWoolColor()` which routes `DyeColor.WHITE` through
`ColorLerper.SHEEP.getColor()` -> `getModifiedColor()` which returns
**`0xFFE6E6E6`** (`(230, 230, 230)`) - NOT the raw
`DyeColor.WHITE.textureDiffuseColor` (`0xFFF9FFFE`). Tint extraction
added in `EntityOverlayResolver`: walks `layer.submit()` -> finds
`INVOKESTATIC coloredCutoutModelCopyLayerRender` -> traces color arg
back to `state.get<X>Color()` `INVOKEVIRTUAL` -> recurses into state
class -> finds `ColorLerper$Type.getColor(DyeColor)` pattern ->
resolves field's default `DyeColor` from `<init>` `PUTFIELD` walk ->
returns `0xFFE6E6E6` for `WHITE` branch. `ToolingEntityModels` emits
`tint_color` in `entity_models.json` when non-WHITE.
[[overlay-tint-support]] [[group-c-composite-overlays]]

**Tooling: entity-id texture match (2026-05-16 pt 2)** -
`EntityTextureResolver.resolve` takes `entityId`. New
`pickFieldByEntityIdMatch` prefers a static texture field whose path
ends with `/<entityId>.png` over the default-branch walker. Removes
the manual hand-edit for `piglin_brute` (`PiglinRenderer`'s
`state.isBrute ? PIGLIN_BRUTE_LOCATION : ...` branch was returning
`piglin.png` for both entities). Regen-stable: diff vs HEAD now
contains ONLY the intended sheep-tint + piglin-brute-texture
emissions, no hand edits.

**Falsified Task #31 hypothesis**: there is NO cross-cutting ~12/255
brightness drift. Cardinal-axis shading verified bit-identical to
vanilla via `light.glsl` source comparison. `RenderEngine
.computeEntityInUiLighting` matches vanilla's
`minecraft_mix_light_separate` exactly; `INVENTORY_DIFFUSE_LIGHT_0/1`
constants verified against `com.mojang.blaze3d.platform.Lighting`.
The sheep "brightness shift" was just the wrong tint - `0xFFF9FFFE`
applied where vanilla applies `0xFFE6E6E6`. tropical_fish 30.46
residual is a SEPARATE root cause (KOB pattern + dual-tint state),
not the same drift.

## Resume here (2026-05-16, post-Groups A/C/D + tint plumbing)

**Buckets: <1: 49 / <5: 84 / <20: 95** (Task #37 piglin, llama
family, Group A equine equipment, Group C villager/zombie_villager
composite overlays, Group D mostly resolved organically + tropical_fish
modest tint improvement, tint plumbing landed). polar_bear allowlist
preserved. Canaries unchanged (cod, polar_bear, spider, dolphin,
horse, blaze, creeper, skeleton_horse, warden, skeleton, stray,
bogged, cat, guardian).

**Task #36 (2026-05-15)** purged the parallel bedrock pipeline.
Canonical names are `EntityRenderer` / `EntityGeometryKit` /
`ToolingEntityModels`; override file shrank 1348 -> 118 lines.

**Task #37 (2026-05-15)** - piglin family
(`piglin_brute` texture_ref + `head.clearChild("hat")` mirroring +
right-side overlay pivot bug). `piglin 38.47 -> 9.93`,
`piglin_brute 68.40 -> 10.69`, `zombified_piglin 47.38 -> 1.33`.

**Llama family (2026-05-15)** - `LlamaModelMixin` in harness +
`hidden_bones` + trader carpet overlay. `llama 1.16 -> 1.29`,
`trader_llama 44.86 -> 4.46`.

**Group A equine (2026-05-15)** - `DonkeyModelMixin` in harness +
donkey-ear cube-rotation hand-edit. `donkey 47.93 -> 0.58`,
`mule 45.40 -> 0.79`. Skeleton_horse 9.16 untouched - different
cause (texture-variant drift, not equipment visibility).

**Group C composite overlays (2026-05-16)** -
`ZombieVillagerStateMixin` pinning random profession to NONE +
type=plains overlay with **0.001 inflate to clear ModelEngine's
equal-Z depth-fail**. `villager 69.29 -> 0.81`,
`zombie_villager 79.66 -> 4.09`, `tropical_fish 35.88 -> 30.46`.
Sheep tint deferred until tint support landed.

**Group D canvas-padding (2026-05-16)** - mostly already resolved
by earlier iso/lighting rounds. warden / creaking / drowned /
zombie / pufferfish / pig_warm / cow_cold / cow_warm / pig_cold all
under 2.0 with `dW=dH=0`. Only tropical_fish (composite-overlay)
and silverfish (rasterizer drift) outliers remain.

**Overlay tint plumbing (2026-05-16)** -
`EntityGeometryKit.buildTriangles` gained a `tintArgb` overload;
`OverlayLayer.tintArgb` + `EntityDefinition.baseTintArgb` fields
threaded through `EntityRenderer.render`; new `parseTintArgb` helper
accepts `0xRRGGBB`/`0xAARRGGBB`/`#`-prefixed forms.
`entity_models_overrides.json` honours `base_tint` on the entity and
`tint_color` on each overlay. tropical_fish 32.68 -> 30.46
(-7%), sheep 24.31 -> 21.83 (-10%) - small because the remaining
~12/255 brightness gap is a separate cross-cutting shading drift
(Task #31), not tint.

Canonical file layout:
- `entity_geometry.json` (generated by `ToolingEntityModels`)
- `entity_geometry_handedits.json` (hand-edited; merged at load with
  precedence on key collision)
- `entity_models.json` (generated)
- `entity_models_overrides.json` (hand-edited; honoured keys now
  include `geometry_ref`, `texture_ref`, `hidden_bones`, `overlays`,
  `force_opaque`, **`base_tint`**, and on each overlay
  **`tint_color`** + **`inflate`** + **`emissive`**)

Canonical class names (no Java suffix anywhere):
- `lib.minecraft.renderer.EntityRenderer`
- `lib.minecraft.renderer.kit.EntityGeometryKit`
- `lib.minecraft.renderer.tooling.ToolingEntityModels`
- `lib.minecraft.renderer.tooling.entity.Entity{Block,Layer,...}{Overlay,Definition,...}Resolver`
- `lib.minecraft.renderer.pipeline.loader.EntityModelLoader` -
  surviving entry points `load()` and `loadFamilies()`

## Pattern catalogue (proven on multiple cases)

1. **SkipSetupAnimMixin bypass** - per-model mixin in harness hides
   equipment-driven bones at construction RETURN; asset-renderer
   mirrors via `hidden_bones`. Cases: [[llama-family-chest-fix]],
   [[equine-family-fix]], [[group-c-composite-overlays]]
   (`ZombieVillagerStateMixin` pins random profession to NONE).

2. **Overlay z-fight clearance** - any overlay sharing geometry with
   the base mesh needs `inflate >= 0.001` to win
   `ModelEngine.depthFails`'s equal-Z rejection (overlays drawn
   AFTER base lose at exact Z). Cases:
   trader_llama carpet (`inflate 0.5`), villager type/plains
   (`0.001`), zombie_villager type/plains (`0.001`), tropical_fish
   pattern (`0.001`).

3. **Composite-texture overlay** - vanilla draws base + 1-3 textured
   passes (profession, pattern, decor). Modeled as
   `overlays: [{geometry_ref, texture_ref, inflate, tint_color}]`.
   Cases: villager (type/plains), zombie_villager, tropical_fish
   (pattern), sheep (wool), trader_llama (decor).

4. **Cube-level rotation for tooling-missed PartPose** - when the
   bytecode tooling misses a non-ZERO
   `PartPose.offsetAndRotation` on a child bone (e.g.
   `DONKEY_TRANSFORMER.modifyMesh` ear replacement), bake the
   offset into the cube origin and set `cube.pivot` +
   `cube.rotation`. Kit applies cube rotation BEFORE the bone chain
   - mathematically equivalent to vanilla's
   `R_ear * v_local` step. Cases: [[equine-family-fix]] donkey ears.

## Next session pickup - remaining work grouped by commonality

Snapshot 2026-05-16 pt 2 (post Task #31 sheep tint, post entity-id
texture-resolver generalization). Buckets <1:49 / <5:85 / <20:96 / 100.
Polar_bear allowlist (1.08) preserved. All 14 canaries bit-stable.

Below: every entity with `delta > 2.0`, grouped by shared root cause,
each group sorted by largest delta first. Group totals indicate where
a single piece of pipeline work unlocks the most absolute progress.

### Group I - Translucent / emissive feature layer compositing (~285 Δ)

Vanilla draws an additional `LivingEntityEmissiveLayer` or
`RenderType.entityTranslucent` pass that the static iso renderer does
not reproduce. Largest single bucket by far - one pipeline addition
(translucent overlay compositing with alpha-respecting blend) likely
moves all 4 entities together.

| Δ | Entity | Diagnosis |
|---|---|---|
| 122.59 | breeze | wind-currents `LivingEntityEmissiveLayer` (translucent) - not yet rendered |
| 120.08 | ghast | hanging tentacles - likely PartPose pivot bug (mirrors piglin right-sleeve from Task #37) or culling; investigate parse before assuming layer-render is the cause |
| 26.48 | slime | outer 8x8x8 translucent shell over inner 6x6x6 body - opaque rasterization makes it a solid larger cube |
| 16.22 | happy_ghast | tentacle / harness translucent layer (same family as ghast) |

Also tracked here (delta 1.86, below the 2.0 group-table cutoff but
same root cause family):

| Δ | Entity | Diagnosis |
|---|---|---|
| 1.86 | enderman | eyes wrong colour - vanilla `(204, 0, 250)` pure purple, java `(255, 144, 255)` pink. Spider eyes (`(255, 14, 14)` vs `(255, 21, 21)`) match within rounding, so the emissive additive blend path itself works. Enderman's eye overlay shares the base mesh's geometry exactly - likely a Z-fight where the base face wins `ModelEngine.depthFails` at equal Z, letting the eye additive add onto the lit skin texel (skin G≈144 + eye G=0 -> result G=144). Same root cause as [[group-c-composite-overlays]] - probably needs `inflate >= 0.001` on the emissive overlay entry, same trick the profession / pattern overlays use. |

### Group II - State-conditional default-variant texture (~70.43 Δ)

Vanilla's `getTextureLocation(state)` branches on a state field that
is non-zero in the default render state (variant enum, weathering,
biome, dye). The bytecode walker picks the all-defaults branch - which
isn't always the "canonical" variant. Some of these may convert to
overlays / hidden_bones / cube-rotation fixes once investigated.

| Δ | Entity | Cause hypothesis |
|---|---|---|
| 15.13 | chicken_cold | crown / feather variant - biome-specific cube tweaks |
| 15.03 | hoglin | bristle / tusk rotations - variant-specific PartPose |
| 8.78 | mooshroom | mushroom block overlays positioning (`MushroomCowMushroomLayer`) |
| 8.63 | copper_golem | weathering state default mismatch |
| 8.32 | chicken_warm | same family as chicken_cold |
| 6.87 | ocelot | variant texture / pose mismatch |
| 3.90 | shulker | dye color default + peek state |
| 2.97 | parrot | variant texture |
| 2.58 | cat | variant texture (canary - bit-stable) |
| 2.27 | witch | hat / potion held item |

### Group III - Composite-overlay residual (per-entity tint or layer math) (~38.92 Δ)

Composite-overlay path is wired; what remains is per-entity correctness
of the tint constant or the overlay's blend semantics. Tropical_fish
in particular: KOB variant has separate `baseColor` + `patternColor`
state fields; current pipeline emits one `base_tint` + one overlay
`tint_color`, but vanilla applies both through the dual-tint state
which our `state.getXxxColor()` extractor does NOT yet decode (Task
#31 generalization only handled the WHITE branch).

| Δ | Entity | Status |
|---|---|---|
| 30.46 | tropical_fish | partial - pattern overlay + `0.001` inflate landed; per-fish KOB tint mismatch residual |
| 4.46 | trader_llama | LANDED (carpet overlay); residual is harness's `NO_RENDER_LAYER_SUFFIXES` skipping `LlamaDecorLayer` in bounds-walk, leaving canvas-padding |
| 4.09 | zombie_villager | LANDED (`ZombieVillagerStateMixin` + type/plains + `0.001` inflate); residual likely Z-fight on edge-coverage pixels |

### Group IV - Held-item / clothing layer not rendered (~12.26 Δ)

`ItemInHandLayer` and similar per-mob clothing layers are suffix-
skipped by the asset-renderer. Skeleton's bow specifically needs the
item-model render path that the entity pipeline doesn't have. Bogged /
stray were partially mitigated via `overlays` for the clothing texture.

| Δ | Entity | Layer |
|---|---|---|
| 6.16 | skeleton | held bow (default-held - `ItemInHandLayer`) (canary - stable) |
| 3.26 | bee | stinger pose / nectar variant |
| 2.84 | bogged | mushrooms (`BogShroomsLayer`) - PARTIALLY done (canary - stable) |

### Group V - Rasterizer sub-pixel edge drift (Task #8, deferred) (~24.32 Δ)

Cross-entity edge-coverage convention difference between our barycentric
rasterizer and vanilla's GL polygon rasterization. Geometry / shading /
lighting are bit-accurate; the residual is silhouette-edge anti-aliasing
on 1-3 pixel boundaries.

| Δ | Entity | Notes |
|---|---|---|
| 10.69 | piglin_brute | sub-pixel rasterizer edge drift |
| 9.93 | piglin | same |
| 3.70 | silverfish | same |

### Group VI - Single-renderer multi-entity texture/state ambiguity (~9.16 Δ)

The entity-id texture-match fix from this session covered `piglin_brute`.
`skeleton_horse` is the remaining case in this family - texture variant
drift driven by a state field the walker doesn't statically resolve.

| Δ | Entity | Notes |
|---|---|---|
| 9.16 | skeleton_horse | texture-variant drift (`AbstractHorseRenderer` state branch) (canary - stable) |

### Group VII - Standalone (~4.42 Δ)

| Δ | Entity | Notes |
|---|---|---|
| 2.25 | squid | minor pose-extraction drift on tentacle procedural loop |
| 2.17 | wither | translucent / armor-state composite |

### Totals + ROI ordering

| Group | Total Δ | Highest-ROI work |
|---|---|---|
| I  Translucent layers | ~285 | Pipeline: translucent / emissive overlay compositing |
| II  Variant defaults | ~70 | Per-entity overrides or state-field walker enhancement |
| III  Composite residual | ~39 | Tropical_fish dual-tint state extractor |
| V  Rasterizer drift | ~24 | Task #8 - cross-entity edge coverage rewrite |
| IV  Held-item layers | ~12 | Item-model render path bolt-on |
| VI  Single-renderer multi-entity | ~9 | Skeleton_horse state-field walker |
| VII  Standalone | ~4 | Per-entity polish |

Single largest-ROI lever is Group I - translucent layer support
unlocks ~285 delta across breeze + ghast + slime + happy_ghast in one
pipeline change. Second largest is Group II's variant defaults
(~70 delta, spread across 10 entities, each fixable independently).

Canonical file layout after Task #36:
- `entity_geometry.json` (generated by `ToolingEntityModels`)
- `entity_geometry_handedits.json` (hand-edited; merged at load with
  precedence on key collision)
- `entity_models.json` (generated)
- `entity_models_overrides.json` (hand-edited; honoured keys are
  `geometry_ref`, `texture_ref`, `hidden_bones`, `overlays`,
  `force_opaque`)

Canonical class names (no Java suffix anywhere):
- `lib.minecraft.renderer.EntityRenderer`
- `lib.minecraft.renderer.kit.EntityGeometryKit`
- `lib.minecraft.renderer.tooling.ToolingEntityModels`
- `lib.minecraft.renderer.tooling.entity.Entity{Block,Layer,...}{Overlay,Definition,...}Resolver`
- `lib.minecraft.renderer.pipeline.loader.EntityModelLoader` -
  surviving entry points `load()` and `loadFamilies()`

## Task #37 - NEXT: piglin family CANVAS_PADDING

Highest single-fix ROI in the focus pool. Three entities all show the
same `+8 height` symptom which the diff-panel audit pinned to a
bind-pose miss shared across the piglin Model hierarchy:

| Delta | Entity | Sil delta |
|---|---|---|
| 68.40 | piglin_brute | +8 height in ours |
| 47.38 | zombified_piglin | +7 height in ours |
| 38.48 | piglin | +8 height in ours |

Total: ~154 delta points across three entries. The +8 figure matches a
torso-length pixel offset, suggesting one bone (head or upper body) is
parented or pivoted differently in the Java pipeline than vanilla's
runtime expects.

**Investigation order:**

1. Render a piglin standalone via `./gradlew entityRender3D
   -PentityId=minecraft:piglin -PrenderSize=512` and side-by-side with
   `cache/visual/entity-parity-vanilla/piglin/diff_panel.png` to
   confirm where the extra 8px lives (top? bottom? both?).
2. Grep `PiglinModel` / `PiglinRenderer` in
   `W:/Workspace/Java/Minecraft-Library/vanilla-reference-harness/` for
   bone-level transforms not captured by the bytecode tooling. Likely
   suspects: `head` y-offset, ear bones (`left_ear` / `right_ear`),
   any `MeshTransformer.scaling` or `PartPose.offset` applied at
   `LayerDefinitions.createPiglin*`.
3. Diff `entity_geometry.json` against the harness's
   `PiglinModel.createBodyLayer` output - if a bone is missing or its
   pivot differs by ~8 units, that's the fix.
4. If the gap is a static bind-pose offset (`PartPose.offset(y=...)`)
   that's missed by the tooling, add a hand-edit to
   `entity_geometry_handedits.json` mirroring the harness's bone tree.
   If it's a vanilla state-dependent override applied in `setupAnim`
   we don't replicate, consider whether the override pool
   (`SETUP_ROTATIONS_OVERRIDES` in `EntityRenderer.java:122`) should
   carry the piglin family.

**Validation:** the canary list MUST stay unchanged. The piglin fix is
allowed to perturb piglin / piglin_brute / zombified_piglin only;
anything else moving is a regression. Use `./gradlew entityParityVanilla
-PentityId=minecraft:piglin` etc. to spot-check, then a full sweep at
the end to confirm bucket counts.

## Top remaining work after Task #37

From the focus pool, ordered by ROI:

- **Tier 1 MISSING_CONTENT** (real geometry/feature gaps, large
  single-entity wins):
  - breeze 122 - translucent wind currents
    (`LivingEntityEmissiveLayer`); needs translucent compositing
    implementation, but the BOUNDS contribution alone closes most of
    the canvas-size gap
  - ghast 120 - hanging tentacles missing or culled; investigate
    `GhastModel.createBodyLayer` tentacle PartPose vs our parsed JSON
- **Composite-texture cluster** (mirrors the stray/bogged outer-layer
  pattern via `SkeletonClothingLayer`):
  - villager 69 (profession overlay)
  - zombie_villager 80 (profession overlay)
  - trader_llama 45 (carpet saddle)
- **Equine family CANVAS_PADDING** (donkey 48, mule 45) - same shape
  as skeleton_horse's old 133 fix but smaller; HorseEquipmentLayer
  state-gating
- **chicken_cold 15.13, mooshroom 8.78** - texture-variant residuals
- **Tier 2-3 polish** - rabbit/axolotl/cat/sheep TEXTURE_VARIANT
  fine-tunes (last few delta points each)

Cumulative cluster recoveries through Task #35: zombie 160 -> 1.10,
husk 146 -> 1.00, drowned 148 -> 1.76, skeleton 206 -> ~56,
wither_skeleton 246 -> 1.87, piglin 152 -> 38, piglin_brute 198 -> 68,
zombified_piglin 166 -> 47, zombie_villager 197 -> 80, cat 162 -> 2.58,
giant 171 -> 36. Wolf / horse / zombie_horse silhouettes 1px-identical
to vanilla.

**Diff-panel audit (2026-05-15)** - ranked highest-to-lowest delta, each
entity classified by the empirical canvas/silhouette comparison and visual
inspection of `cache/visual/entity-parity-vanilla/<entity>/diff_panel.png`.
Three top-level categories:
1. **MISSING_CONTENT** (`SIL_DIFF` with vanilla wider/taller): an entire
   piece of vanilla geometry never reaches our render (held items, layered
   clothing, wind currents, block overlays). Usually a missing renderer
   layer or an asset-renderer feature we haven't implemented.
2. **CANVAS_PADDING** (`SAME_SIL` / identical pixels in silhouette
   intersection): silhouette matches vanilla but our canvas has a few
   extra rows/columns. The alpha-tight bound walker produces bounds wider
   or taller than vanilla's despite the algorithms being spec-identical.
   Subtle bilinear-interp or plane-cube edge case.
3. **TEXTURE_VARIANT** (`PIXEL_DIFF_ONLY`): canvas and silhouette both
   match exactly. Per-pixel diff is the renderer choosing a different
   default texture variant from vanilla (rabbit brown vs white, villager
   profession default, etc.) or a small lighting / UV interpretation
   delta.

### Tier 1 (100+ delta)

| Delta | Entity | Class | Hypothesis |
|---|---|---|---|
| 160.82 | bogged | MISSING_CONTENT | Vanilla shows green mushrooms across shoulders/back + held bow. Ours: bare skeleton. Missing `BogShroomsLayer` (mushroom overlay model) + `state.isHoldingBow=true` default so `ItemInHandLayer` renders the bow. |
| 137.14 | stray | MISSING_CONTENT | Vanilla shows tattered frost cape + held bow. Ours: bare skeleton. Missing `StrayClothingLayer` (frost overlay model) + bow visibility from skeleton's default-held-bow. |
| 133.00 | skeleton_horse | CANVAS_PADDING (mostly) | Silhouettes nearly pixel-identical (455x482 vs 454x482), but vanilla canvas 504x482 vs ours 461x498. Anchor / margin asymmetry: vanilla L44 R6 T0 vs ours L0 R6 T16. AbstractEquineRenderer adds `HorseEquipmentLayer` whose model still walks despite being suffix-matched; either the layer's class name diverges from our `*EquipmentLayer` pattern OR the layer's submit is gated state-dependent in a way our `isLayerActiveForState` doesn't catch. |
| 125.65 | elder_guardian | CANVAS_PADDING | Vanilla 1024x787 (capped via shrink), ours 1024x830. Silhouettes show vanilla with 6+ radial spikes spread wide; ours has fewer spikes visible. `GuardianModel` has 12 spike bones with authored rotations - check if our parser captures all spike rotations. Possibly elder_guardian-specific spike bone scale we miss. |
| 125.28 | warden | CANVAS_PADDING | Silhouettes pixel-identical (475x898 vs 475x897), our canvas 544x1022 vs vanilla 498x905 - 46 wider, 117 taller. Bounds-walker discrepancy on plane-cube tendrils. Per-cube diagnostic: our `contributeFaceAlphaTight` produces marginally different bilinear corners from vanilla harness's `contributePolygonExtents` for size-z=0 cubes. Probably an off-by-one or normal-direction flip in face-vertex order for plane cubes. |
| 122.59 | breeze | MISSING_CONTENT | Vanilla 408x462 (large swirling wind currents around body); ours 198x310 (just head + small body). Wind currents are `LivingEntityEmissiveLayer` instances with translucent textures - we don't render them at all. Translucent compositing would need its own implementation; the BOUNDS contribution alone would close most of the canvas-size gap. |
| 120.08 | ghast | MISSING_CONTENT | Vanilla 726x1024 (tentacles hang down visibly from body); ours 921x1024 (no tentacles, body wider). Tentacles in vanilla are 9 hanging bones; in our render they may be culled (back-face) or not parsed. Body width mismatch (+195 wider in ours) suggests an X-direction bone we extend beyond what vanilla shows - investigate `GhastModel.createBodyLayer` tentacle PartPose vs our parsed JSON. |
| 110.03 | rabbit | TEXTURE_VARIANT | Canvas 228x276 == silhouette 228x276 in both. Vanilla shows brown rabbit; ours shows white/gray. Default texture variant differs. `RabbitRenderer` chooses brown as the canonical at runtime; we default to a different stem. Add `minecraft:rabbit` to entity texture overrides. |
| 107.91 | chicken_cold | MISSING_CONTENT (height) | Same canvas width 193, vanilla 256 tall vs ours 231 (-25). Vanilla cold-chicken has a feathered/fluffed crown bone our parser may miss, OR vanilla setupAnim raises the head/comb at zero state. Same family as chicken_warm. |
| 100.86 | mooshroom | CANVAS_PADDING | Vanilla canvas 442x482 (silhouette 387x482, L12 R43), ours 385x469. Vanilla wider by ~57 because the mushroom block-overlay extends past the cow body. Mooshroom mushrooms are `MushroomCowMushroomLayer` (block-rendering, not Model-typed) which neither pipeline walks for bounds - vanilla's 57-px gap is from the harness's own bound computation being looser for block layers. Block-overlay positioning of mushrooms diverges. |
| 100.74 | tropical_fish | CANVAS_PADDING | Silhouette pixel-identical 102x92. Vanilla 103x94 (margins L0 R1 T1 B1); ours 148x118 (L46 R0 T25 B1). Our pipeline pads the canvas with ~45 extra width and 24 extra height. Same family as warden: alpha-tight bound walker over-extends for fish plane cubes (fins). |
| 100.47 | chicken_warm | MISSING_CONTENT (height) | Identical issue to chicken_cold. |

### Tier 2 (50-100 delta)

| Delta | Entity | Class | Hypothesis |
|---|---|---|---|
| 99.68 | silverfish | CANVAS_PADDING | Silhouette identical 215x147. Vanilla 215x157 (T9), ours 215x174 (T26). +17 px extra top. Same family as warden / tropical_fish. |
| 79.66 | zombie_villager | TEXTURE_VARIANT | Canvas + silhouette match (227x551 == 227x550). Texture difference - vanilla picks an unspecified profession; we default to another. |
| 75.69 | cow_cold | MISSING_CONTENT (width) | Vanilla 442x482 silhouette; ours 385x469 (-57 width). cold-cow has fur/horn bone our parser misses, OR a fur-overlay layer model. |
| 75.39 | cow_warm | MISSING_CONTENT (width) | Vanilla 418x469 sil, ours 385x469 (-33 width). Warm-cow horns extend wider than our parsed mesh. Same family as cow_cold but smaller gap. |
| 69.29 | villager | TEXTURE_VARIANT | Canvas + silhouette match (259x516 == 258x516). Vanilla picks default profession (farmer brown?), we render the generic flat villager. Visual diff is robe color/pattern + nose shading. |
| 68.40 | piglin_brute | CANVAS_PADDING (height) | Sil +8 height in ours. Piglin family has bone rotations our parser may capture slightly differently from vanilla bind pose. |
| 59.79 | pig_cold | CANVAS_PADDING (height) | Vanilla 363x348 sil, ours 363x335 (-13 height). cold-pig has fur bone we miss. |
| 59.42 | wither_skeleton | CANVAS_PADDING | Sil +1 width. Slight mismatch, probably bow/stone-sword item. |
| 58.88 | creaking | CANVAS_PADDING (height) | Silhouettes pixel-identical 249x654. Vanilla 283x660; ours 283x694 (+34 extra bottom rows). Creaking has a "creaking_heart" emissive layer model walked by vanilla but possibly not by us; OR our alpha-tight pads extra Y. |
| 56.99 | pufferfish | CANVAS_PADDING (height) | Sil -5 height. PufferfishStateMixin forces full puff. Slight bind-pose difference on spikes. |
| 55.98 | skeleton | CANVAS_PADDING | Sil +1 width. Skeleton's default bow not held in ours. Same as bogged/stray but smaller because vanilla skeleton doesn't have extra clothing/mushroom overlays - just the bow. |

### Tier 3 (10-50 delta)

| Delta | Entity | Class | Hypothesis |
|---|---|---|---|
| 47.93 | donkey | CANVAS_PADDING (height) | Sil -15 height. Equine family - chest bags / saddle layer interaction same as skeleton_horse. |
| 47.38 | zombified_piglin | CANVAS_PADDING (height) | Sil +7 height. Piglin family bind-pose. |
| 45.40 | mule | CANVAS_PADDING (height) | Sil -17 height. Equine variant; same as donkey. |
| 44.86 | trader_llama | TEXTURE_VARIANT | Canvas + sil exact match. Trader llama gear (carpet decoration) hidden by Java pipeline but rendered slightly different in vanilla. |
| 40.23 | cat | CANVAS_PADDING (height) | Sil -1 height. Cat has multiple texture variants and default selection differs. |
| 38.48 | piglin | CANVAS_PADDING (height) | Sil +8 height. Piglin family. |
| 38.01 | axolotl | TEXTURE_VARIANT | Canvas + sil exact match. Default variant texture differs (lucy vs blue, etc.). |
| 36.02 | giant | MISSING_CONTENT (size) | Sil -10x-24. Giant is 6x zombie. Vanilla taller and slightly wider. Maybe the giant scale baking doesn't account for shrink to MAX_CANVAS_SIZE cap interaction. |
| 32.58 | drowned | CANVAS_PADDING (height) | Sil 0, canvas +13. Pure padding issue. |
| 31.00 | pig_warm | CANVAS_PADDING (height) | Sil 0, canvas -12 in ours (vanilla wider). |
| 28.46 | husk | CANVAS_PADDING | Sil -1. Slight mismatch in undead family. |
| 26.48 | slime | CANVAS_PADDING | Sil +1x-2. Slime outer-cube interaction with inner. |
| 25.70 | zombie | CANVAS_PADDING (height) | Sil 0, canvas +12. Pure padding. |
| 24.31 | sheep | TEXTURE_VARIANT | Canvas + sil exact. Sheep wool color variant (white in vanilla?). |
| 21.58 | pillager | CANVAS_PADDING | Sil +1 width. Likely the crossbow item not rendered in ours. |
| 16.22 | happy_ghast | CANVAS_PADDING | Sil +21x+24 in ours (bigger). Tentacles / harness layer geometry mismatch. |
| 15.03 | hoglin | CANVAS_PADDING | Sil +5 width. Hoglin bristle/tusk bone rotations. |

### Tier 4 (1-10 delta) - mostly TEXTURE_VARIANT, low-priority polish

| Delta | Entity | Class | Notes |
|---|---|---|---|
| 8.63 | copper_golem | TEXTURE_VARIANT | Default weathering state (unweathered vs other). |
| 6.87 | ocelot | CANVAS_PADDING | Sil +1 width. |
| 3.90 | shulker | TEXTURE_VARIANT | Default dye color or peek state. |
| 3.26 | bee | TEXTURE_VARIANT | Wing pose / lighting on translucent wings. |
| 2.97 | parrot | TEXTURE_VARIANT | Variant color (red by default). |
| 2.27 | witch | TEXTURE_VARIANT | Cape / nose shading. |
| 2.25 | squid | TEXTURE_VARIANT | Tentacle lighting. |
| 2.17 | wither | CANVAS_PADDING | Sil -1x+1, near-perfect. |
| 1.90 | glow_squid | TEXTURE_VARIANT | Emissive tint. |
| 1.87 | nautilus | CANVAS_PADDING | Sil +1 width. |
| 1.86 | enderman | TEXTURE_VARIANT | Eye glow emissive. |
| 1.83 | evoker | TEXTURE_VARIANT | Robe / fang texture. |
| 1.81 | vex | TEXTURE_VARIANT | Wing translucency. |
| 1.80 | zombie_nautilus | CANVAS_PADDING | Same as nautilus. |
| 1.74 | parched | TEXTURE_VARIANT | Camel variant texture / sand effect. |
| 1.45 | allay | TEXTURE_VARIANT | Allay item-held / glow. |
| 1.30 | illusioner | TEXTURE_VARIANT | Robe / hat detail. |
| 1.26 | vindicator | TEXTURE_VARIANT | Axe held / arm-pose detail. |
| 1.16 | llama | TEXTURE_VARIANT | Llama variant default. |
| 1.08 | polar_bear | TEXTURE_VARIANT | **Achieved-parity allowlist** - confirmed within visual-rest-pose tolerance, do not regress. |
| 1.08 | goat | TEXTURE_VARIANT | Horn detail / age-state texture. |
| 1.02 | salmon | TEXTURE_VARIANT | Salmon variant scale (small/medium/large). |
| 1.01 | horse | TEXTURE_VARIANT | Default horse coat colour (white by default, vanilla picks one of seven). |

### Cross-cutting patterns (group fixes likely)

**CANVAS_PADDING root cause investigation** (warden / silverfish / creaking
/ tropical_fish / drowned / zombie / pig_warm / etc.): instrument our
`EntityGeometryKitJava.computeScreenBounds` to log per-cube alpha-tight
output and diff against vanilla harness's `contributePolygonExtents` for
the same cube. The bound difference should localise to a single
bilinear-corner contribution. Once found, the fix lands one entry and
moves the entire CANVAS_PADDING family down by 5-10 px per axis.

**MISSING_CONTENT held-item layers** (bogged / stray / skeleton): all
inherit AbstractSkeletonRenderer with `isHoldingBow` set to true by
default. Their bows should render via ItemInHandLayer. Our pipeline
currently suffix-matches `ItemInHandLayer` -> SKIP for bounds + render.
The held-bow item model would need its own renderer support, separate from
the entity model pipeline.

**MISSING_CONTENT clothing/overlay layers** (stray frost cloak, bogged
mushrooms, breeze wind-currents, cow horns, chicken comb): these are
per-entity `LivingEntityEmissiveLayer` / `*ClothingLayer` /
`*ShroomsLayer` instances that bake their own `LayerDefinition`. The
tooling could surface them as bounds-only overlays once we have a
trusted layer-detection path (the broad-detection attempt from earlier
in this session regressed because of ElytraLayer's setupAnim-folded
pose, but per-entity allowlist would be safe).

**TEXTURE_VARIANT polish** (rabbit / villager / cat / horse / etc.): low
priority; deltas under 10 mean the silhouette and pose are right, only
the picked default texture stem differs. Add per-entity texture-default
overrides to match vanilla's `getTextureLocation(state)` for the
zero-state entity. Won't move bucket counts since these already sit in
&lt;5 or &lt;20.

### Previous followups (preserved)

- **Cod's residual 0.55** - chirality / cube-edge tie-break at body cube
  x=133-135 strip (vanilla shows WEST face, java shows EAST same texel
  different face). Low priority since cod is already in <1 bucket.
- **A4 setupRotations overrides** - squid (6.26), pufferfish (57), shulker
  (3.9) per Round 8 audit. Squid and shulker are mostly TEXTURE_VARIANT
  now; pufferfish was upgraded by PufferfishStateMixin (force full puff)
  but the +57 delta remaining is a slight bind-pose H mismatch.
- **A5 MeshTransformer.scaling JSON-bake** - happy_ghast (16), ghast (120),
  elder_guardian (126). Task #27's inline-scaling fix already covers
  cave_spider; need similar audit for these.

3. **Cod's residual 0.55** - chirality / cube-edge tie-break at body cube
   x=133-135 strip (vanilla shows WEST face, java shows EAST same texel
   different face). Low priority since cod is already in <1 bucket. Note:
   may be partly an artifact of the harness bug too; revisit after #1
   regeneration.

4. **A4 setupRotations overrides** - squid, pufferfish, shulker (per Round 8
   followups, scope reduced to these three only).

5. **A5 MeshTransformer.scaling JSON-bake** - happy_ghast (446), ghast (142),
   elder_guardian (185). Bone-scale support per deferred Round 7 plan.

6. **Cluster analysis if you want a fresh starting entity:**
   ```bash
   python scripts/parity_analysis/analyze_diff_panels.py
   cat scripts/parity_analysis/out/clusters.json
   ```

## Task #24 - LANDED 2026-05-15: alpha-tight bounds (canvas-fit)

`EntityGeometryKitJava.computeScreenBounds` now walks per-face polygons and
contributes only the opaque-texel sub-rectangle's bilinear corners (mirroring
`EntityFrameRenderer.contributePolygonExtents` in the harness) instead of the
8-corner cube AABB. Texture parameter plumbed from `EntityRendererJava.render`
through `computeCanvasFit` / `computeCentreAnchor` / `computeUnionScreenBounds`.

**Bucket movement:** <1: 15→19 (+4), <5: 34→41 (+7), <20: 41→47 (+6). Canary
wins: spider 6.58 → 0.57, dolphin 5.67 → 0.46, creeper 18 → 0.92, blaze → 0.91.
Bedrock-parity canaries (cod, polar_bear, horse) unchanged.

**Trade-off:** cutout-no-cull entities (skeleton_horse, cave_spider, zombie
family) regressed because their bounds shrink past vanilla's, which compensates
via layer-model bounds walking we don't yet reproduce (followup #1). User
accepted this regression after weighing the bucket-count net win.

## Task #25 - LANDED then RETIRED 2026-05-15: cutout-aware anchor (band-aid)

Originally added `EntityGeometryKitJava.hasAlphaCutoutCubes` + AABB-anchor
fallback in `EntityRendererJava.computeCentreAnchor` to work around
skeleton_horse drifting left after Task #24. Detector fired on exactly one
entity in the 100-sweep - strong signal of papering over an upstream bug.

**Retired by Task #26**: the actual root cause was vanilla harness's
over-counted bounds + setupAnim leaking through to the deferred render
path. Once those were fixed in the harness, the cutout-anchor gate became
a no-op and was removed (commit `30c7399`).

## Task #26 - LANDED 2026-05-15: vanilla-reference-harness bug fixes

Three bugs in the sibling vanilla-reference-harness made vanilla reference
PNGs structurally biased. Fixed at sibling commit `c49e89a`:

1. `EntityFrameRenderer.isLayerActiveForState` extended with class-suffix
   matching so equipment-driven layers (HumanoidArmorLayer, WingsLayer
   (1.21+ rename of ElytraLayer), CustomHeadLayer, SaddleLayer,
   ItemInHandLayer, CollarLayer, StuckInBodyLayer, TridentLayer, etc.) are
   skipped during the family-fit bounds walk. Previously their unrendered
   models padded the canvas with margin around invisible geometry.
2. `computeScreenBounds` and `walkLayerExtents` no longer call setupAnim
   in headless mode. The bounds walker was mutating bone rotations into
   the setupAnim pose, then the deferred render read the mutated bones.
3. `SkipSetupAnimMixin` extended to also redirect
   `ModelFeatureRenderer.renderModel`'s setupAnim call - the deferred
   render path that actually rasterises queued ModelSubmits. The
   original mixin only caught LER.submit's pre-layer call, letting the
   rasterize-time setupAnim re-mutate bones.

Result: zombie 160→26, husk 146→28, drowned 148→33, skeleton 206→56,
wither_skeleton 246→59, piglin 152→38, piglin_brute 198→68,
zombified_piglin 166→47, zombie_villager 197→80, cat 162→40, giant 171→36.
Wolves / horse / zombie_horse / pig_warm silhouettes 1px-identical to
vanilla.

## Task #27 - LANDED 2026-05-15: tooling fix for inline scaling chains

`JavaEntityLayerDefinitionResolver` now captures the inline
`apply(MeshTransformer.scaling(F))` shape (no intermediate ASTORE/ALOAD).
ModelLayers.CAVE_SPIDER uses this pattern. cave_spider 183→0.59. New
geometry.spider (scale=0.7 cave variant) and geometry.spider_1 (scale=1
spider) emitted correctly. Commit `2db8b04`.

## Task #28 - LANDED 2026-05-15: loadJava hidden_bones support + static-init visibility

`EntityModelLoader.loadJava()` previously skipped entity_models_overrides
entirely. Wired override reads into loadJava; hidden_bones overrides now
take effect on the Java pipeline. Added armor_stand / pillager / vindicator
/ evoker hat-strip overrides (their model constructors set
`this.hat.visible = false` before setupAnim runs; without per-bone
visibility in our pipeline, the hat extent was padding the canvas).
Illusioner deliberately kept (its renderer re-enables hat via
`this.model.getHat().visible = true`).

Result: armor_stand 93→0.52, vindicator 28→1.26, evoker 28→1.83,
illusioner 54→1.30. Commits `14872d5` + `844ea74`.

## Task #31 - DEFERRED: tooling pass over handedits / overrides JSON

Hand-edited entries in `entity_models_overrides.json` and the newer
`entity_geometry_java_handedits.json` (added Task #30) accumulate to
work around tooling gaps:

- `entity_models_overrides.json` carries `hidden_bones`, `bone_overrides`,
  `extra_bones`, `geometry_ref` reroutes, per-entity overlays, and
  `force_opaque` flags that the tooling never derives. Each entry is
  documented inline (the `//<entity>` comment-key adjacent to the
  override block) so a future reader can follow the vanilla source.
- `entity_geometry_java_handedits.json` carries Java geometries the
  bytecode scanner can't reach - `geometry.humanoid_outer` is the only
  current entry (HumanoidModel.createMesh's standalone output used by
  SkeletonClothingLayer-shaped overlays).

These entries are **ground truth** in the parity sense: each one was
empirically tuned until vanilla and Java renders agreed. They are an
excellent training corpus for an assisted tooling pass that could:

1. **Discover overlay layers** by walking each renderer class's
   `addLayer` calls in bytecode, mapping the layer's `Model<?>` field
   to its baked `ModelLayerLocation`, and emitting overlay entries
   automatically. Currently only some `*Layer` classes (emissive eye
   overlays) are auto-detected by `JavaEntityOverlayResolver`; others
   (`SkeletonClothingLayer`, the cow/chicken variant ModelLayers,
   sheep wool, ...) need their own resolvers.
2. **Capture `HumanoidModel.createMesh` and other static-factory
   geometries** the bytecode scanner currently skips because they're
   used via subclass inheritance, not registered as a top-level
   `ModelLayerLocation`. The Java pipeline would benefit from a
   "synthetic geometry" pass that runs `createMesh` (or its analogues)
   in a controlled JVM and writes the resulting LayerDefinition to
   the geometry JSON.
3. **Surface `hidden_bones` from static initialiser visibility
   toggles** by ASM-scanning each model class's `<init>` and
   `<clinit>` for `part.visible = false` patterns. Task #28's
   armor_stand / illager-family hat overrides were derived by hand
   reading the source; the static patterns are mechanical to detect.
4. **Detect cube `force_opaque` / partial-alpha bumps** by sampling
   each texture's UVs and flagging entries whose vanilla pipeline
   uses `entityCutout` or `entityCutoutNoCull` (which discard
   alpha<255 at the rasteriser level - matching our `force_opaque`
   behaviour). The current set was hand-tuned per-entity from visual
   inspection.
5. **Validate variant-model coverage** by cross-checking each
   `ModelType.NORMAL/WARM/COLD` enum used by entities like cow,
   chicken, etc. against the geometries registered for them. Missing
   variants emit a warning + a stub entry the tooling can flesh out.

Output: replace as many hand-edits as possible with tooling output;
keep the remaining minimum as documented edge cases. The hand-edits
themselves remain as regression tests for the tooling's correctness.

Not blocking on this task - parity work continues with hand-edits as
ground truth. Revisit when the parity push hits diminishing returns
and the manual entries become the bottleneck.

## Task #30 - LANDED 2026-05-15: humanoid_outer geometry + handedits merge

The bytecode tooling can't reach `HumanoidModel.createMesh`'s standalone
output (it's only used via `SkeletonClothingLayer` for STRAY / BOGGED
outer layers, not registered as a top-level `ModelLayerLocation`).
Added `entity_geometry_java_handedits.json` as a sibling resource the
loader merges on top of `entity_geometry_java.json` (hand-edits win on
key collision; lines whose key starts with `//` are comments). The
file currently carries a single entry: `geometry.humanoid_outer`
(player-sized 4x12x4 arms/legs at 64x32 texture, matching
`HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F)`).

Repointed stray and bogged overlays from `geometry.skeleton_1` (slim
2x12x2 arms) to `geometry.humanoid_outer` so the inflated outer-layer
silhouette captures the full vanilla extent.

Result: stray 96.22 -> 1.92, bogged 106.03 -> 2.84. Both moved from
>100 bucket into <5. Buckets <1: 39 / <5: 70 (+2) / <20: 76 (+2).
polar_bear achieved-parity preserved. Canaries (cod, polar_bear,
spider, dolphin, horse, blaze, creeper, skeleton_horse, warden,
skeleton) unchanged. Commit `81ebd2a`.

## Task #29 - LANDED 2026-05-15: bound walker uv/3D pairing fix

`EntityGeometryKitJava.computeScreenBounds` was calling `resolveFaceUv` to
get the per-face UV array, but the renderer in `buildTrianglesWithScale`
calls `resolvePolygonUv` - which applies a per-face slot permutation on
top of `resolveFaceUv`. The permutation aligns the kit's corner-order
output of `face.corners()` with vanilla's polygon vertex order. Without it,
the bilinear-interp BL/BR/TR/TL classifier in `contributeFaceAlphaTight`
paired each UV-corner role with the DIAGONALLY OPPOSITE 3D cube corner.

For fully-opaque faces the four contributed positions are still the four
cube corners, just in a different order - bbox of the 4 points is the
same. For sparse-opaque stickers (warden tendrils, silverfish setae,
tropical_fish fins, wither plane cubes), the opaque sub-rect's bilinear
corners interpolate through the wrong cube corners, contributing 3D
positions outside the actual rendered silhouette. Warden tendril's max-Y
bound contribution: 31.48 vanilla vs 38.81 ours - exactly 7.33 entity
units = 117 px of empty canvas-top padding.

Diagnostic: warden left_tendril NORTH polygon. Vanilla pairs UV (uMin,
vMin) ↔ entity (8, -35, 0); our broken pairing was UV (uMin, vMin) ↔
entity (24, -19, 0). Fix: switch `computeScreenBounds` to call
`resolvePolygonUv(face, ...)` instead of
`resolveFaceUv(mirrorFace(face, ...), ...)`. The polygon resolver handles
mirror internally and applies the per-face slot permutation.

Bucket movement: <1: 37→39 (+2), <5: 58→68 (+10), <20: 62→74 (+12). Top
wins: warden 125→0.80, skeleton_horse 133→9.16, silverfish 100→3.70,
creaking 59→1.14, drowned 33→1.76, zombie 26→1.10, husk 28→1.00,
wither_skeleton 59→1.87, pufferfish 57→0.97, pillager 22→1.08. polar_bear
achieved-parity preserved at 1.08. Canary list (cod, spider, dolphin,
horse, blaze, creeper) unchanged. skeleton_horse went from CANVAS_PADDING
top contender to <20 bucket (still has equipment-layer interaction
residual at 9.16).

## Round 8 (landed, 2026-05-14): A1 iso-pose alignment + A3 lighting frame

After three prior reverts (Rounds 2, 3, 5), A1 finally landed with rotation-
order fix and foundation-test guard rail. Net result across 98 entities:
**-13.9 mean delta per entity**. Entities with mean<20 jumped 3 → 13; mean<50
from 15 → 38. polar_bear T3 IMPROVED 36.54 → 30.66.

A3 lighting frame landed alongside, dropping cod 29.71 → 18.48, polar_bear
30.66 → 5.52, salmon 14.08 → 7.49, horse 27.27 → 10.20.

### Changes (5 coupled invariants - mechanism in code javadocs)

1. `EulerRotation.STANDARD_ISO_ENTITY = (210°, 45°, 0°)` - matches harness
   `ISO_ROTATION`. See javadoc for `Quaternionf.rotationXYZ` semantics.
2. `IsometricEngine.entityStandard(context)` factory + `CAMERA_ENTITY`
   matrix - full row-form chain `scale(1,1,-1) × R_Y(45°) × R_X(210°) ×
   scale(1,1,-1) × scale(1,-1,1)`. See `IsometricEngine.CAMERA_ENTITY`
   javadoc for the `FLIP_Y × engine_camera = M_view^T` derivation and the
   trailing Y-invert convention compensation.
3. Natural CCW kit emission `(0,1,2)` and `(0,2,3)` in `EntityGeometryKitJava`.
   Total chain det = -1 (kit-FLIP_Y) × -1 (engine_camera) × -1 (projection);
   model CCW → screen CW → `signedArea < 0` = front-facing per
   `ModelEngine.isBackFacing`.
4. Plane-cube culling disabled in `shouldCullBackFaces` - any size component
   zero returns false so both sides render (vanilla treats these as
   double-sided).
5. `EntityRendererJava.composeIsoTransform/Inverse` rewritten to model the
   exact engine chain so `computeScreenBounds` and `computeCentreAnchor` size
   the canvas correctly.

### A3 L_kit derivation (now in code)

`L_kit = FLIP_Y × M_view^T × L_camera`. Result:
- `L0_kit = (-0.0822, 0.9564, -0.2802)` from `INVENTORY_DIFFUSE_LIGHT_0 = normalize(0.2, -1, 1)`
- `L1_kit = (0.2080, 0.8492, 0.4854)` from `INVENTORY_DIFFUSE_LIGHT_1 = normalize(-0.2, -1, 0)`

Constants live in `RenderEngine.ENTITY_IN_UI_LIGHT_0/1` with full derivation
javadoc. Pre-A3 state diverged 0.04/0.07/0.13/0.17 on ±X/±Z cardinal cases
because Y matched (the bug-masking artifact).

### Foundation test contract

`EntityGeometryKitJavaTest.winding_geometricNormalOpposesStored` (renamed from
`_AgreesWithStored`): under the entity-iso det=-1 chain, emit-order cross
product OPPOSES the stored normal (because `FLIP_NORMAL_Y` flips stored normal
but natural-CCW emission has no compensating flip).

### Round 8 net entity outcomes (98 entities)

**Improvements >50 mean delta:** dolphin (-110), guardian (-100), blaze (-91),
axolotl (-90), camel (-87), goat (-81), creaking (-76), turtle (-74), wolves
(all 9 variants -50 to -65), spider (-59), bee (-59), camel_husk (-57).

**Regressions:** 34 entities, mostly feature-gaps newly exposed by corrected
geometry:
- sheep +62: needs SheepWoolLayer (wool overlay missing)
- chicken_warm +73, chicken_cold +39: needs ChickenVariant model resolution
- pufferfish +93: needs full-puff sub-model selection
- ocelot +58: needs CatRenderer override identification
- allay +35: needs flap animation freeze
- Smaller (cat +24, donkey +30, silverfish +36, armor_stand +23): similar
  feature-gap roots

(Many of these resolved by Tasks #21-23 lighting fixes; cod especially went
44.66 → 0.55 cumulatively.)

### Followups still open

1. Drop unused `FLIP_X/FLIP_Z/FLIP_NORMAL_X/FLIP_NORMAL_Z` toggles from
   `EntityGeometryKitJava` - iteration harness is done. `FLIP_Y` and
   `FLIP_NORMAL_Y` remain load-bearing.
2. A4 (per-renderer setupRotations overrides): real win pool = squid (big)
   + pufferfish (small) + shulker (attachFace default) only. Cat / ocelot
   are A7 variant resolution, not A4.
3. A5 (MeshTransformer.scaling JSON-bake) for happy_ghast (446), ghast (142),
   elder_guardian (185).

## Reference cache rebuilt (2026-05-14)

Re-ran `renderVanillaReferences` end-to-end and patched
`vanilla-reference-harness/EntitySweeper.MISC_ALLOWLIST` to include
`minecraft:villager` and `minecraft:copper_golem` - vanilla 26.1
categorises both as `MobCategory.MISC` despite being `LivingEntity`
subclasses with valid renderers. Harness was skipping them by default
(only `armor_stand` was previously allowlisted). Reference cache now at
104 entities (was 102). New baselines: villager 101.75 (large delta,
likely A7 profession-overlay territory), copper_golem 26.06.

If a future MC version adds another LivingEntity-class entity to MISC,
add its identifier to that MISC_ALLOWLIST and re-run
`./gradlew renderVanillaReferences`.

## Tasks #21 - #23 - LANDED 2026-05-15: cod fin lighting (PER_FACE_LIGHTING + FXAA-off)

Three landings together drove cod 14.20 → **0.55** mean delta. Mechanism details
live in code javadocs; this section preserves the historical canary numbers and
the non-obvious investigation findings.

**Code reference:**
- `EntityGeometryKitJava.computeFaceShading` - the front/back PER_FACE_LIGHTING picker
- `EntityGeometryKitJava.VIEW_DIRECTION_KIT` + `computeKitFrameViewDirection` - V_kit derivation
- `EntityGeometryKitJava.isDegeneratePlaneFace` - degenerate plane-cube face skip
- `RenderEngine.ENTITY_IN_UI_LIGHT_0/1` - L_kit derivation
- `TestEntityParityVanilla.java:158` - `antiAlias(false)`

**Per-task summary:**

| Task | Fix | cod delta |
| --- | --- | --- |
| #21 | max(shade(n), shade(-n)) for plane no-cull cubes | 14.20 → 8.10 |
| #22 | parity test FXAA → off (vanilla harness has no FXAA) | 8.10 → 3.04 |
| #23 | view-direction-aware front/back picker (replaces max-abs) | 3.04 → 0.55 |

**Canary results across all three:**

| Entity | Pre-Task #21 | Post-#21 | Post-#22 | Post-#23 |
| --- | --- | --- | --- | --- |
| cod | 14.20 | 8.10 | 3.04 | **0.55** |
| polar_bear | 2.58 | 2.58 | 1.08 | 1.08 |
| spider | 3.67 | 3.67 | 0.57 | 0.57 |
| dolphin | 3.50 | 3.50 | 0.46 | 0.46 |
| blaze | 4.48 | 4.48 | 0.91 | 0.91 |
| creeper | 4.38 | 4.38 | 0.92 | 0.92 |
| ender_dragon | (n/a) | (n/a) | 0.43 | 0.43 |
| bat | (n/a) | (n/a) | 4.63 | 0.70 |
| tadpole | (n/a) | (n/a) | 1.85 | 0.43 |
| horse | 6.02 | 6.02 | 6.06 | 6.06 |
| skeleton_horse | 59.78 | 59.78 | 60.30 | 60.30 |

Sweep buckets across the three tasks: <1 bucket 0 → 12 → **15** entities;
<5 bucket 16 → 20 → 34 entities.

### Non-obvious investigation findings (preserve, not in code)

**Vanilla pixel forensics on `vanilla.png` cod fin region (y=105-135, x=80-130).**
Histogram of opaque pixels showed BOTH shade-1.0 fin pixels (524 of them) AND
shade-0.45 fin pixels (144 of them) - one fin's DOWN face at shade 1.0 plus the
opposite fin's UP face at shade 0.4 both painting visibly. The 0.45 matched
M_view A's body-WEST prediction precisely, which was the breakthrough that
confirmed lighting math was internally consistent.

**M_view contradiction (algebraic).** Two candidate M_views ruled out as
single-cause explanations:

| M_view | body cube DOWN | right_fin UP |
| --- | --- | --- |
| A: `scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)` (current) | shade=1.000 ✓ | shade=0.400 ✗ |
| B: `R_X(210°) × R_Y(45°)` (JOML rotationXYZ semantics) | shade=0.400 ✗ | shade=1.000 ✓ |

For unit vectors n_body and n_fin = R_Z(-π/4) × n_body, no single rotation
matrix M can yield `shade(M·n_body)` and `shade(M·n_fin)` both = 1.0 because
the required `(L_0+L_1) · M[col 0] > 2.414` exceeds `|L_0+L_1| ≈ 1.97`. This
proved the issue was NOT in M_view choice - led to discovering vanilla's
PER_FACE_LIGHTING shader feature which resolves the apparent contradiction
via screen-winding-determined front/back color picking.

**Sentinel-shade probe technique.** Tagged each cod bone+face with a unique
shade value (e.g. boneId × 0.01 + faceId × 0.001) and decoded rendered pixel
RGB to identify the actual painting bone+face. Useful for distinguishing
"shade math wrong" from "wrong polygon won the depth tie".

**Alpha-zero on transparent UV skips depth-write.** `ModelEngine.rasterizeTile`
checks `if (ColorMath.alpha(sampled) == 0) continue;` BEFORE
`depth[idx] = depthVal`, so a polygon that passes the depth test but samples
a transparent texel does NOT prevent the next polygon from painting at the
same pixel. Significant for plane no-cull cubes where DOWN/UP cover the same
screen region with opposite UV strips - if one strip is transparent, the
other paints unimpeded.

**Vanilla shader files for reference**:
- `cache/dragon-extract/assets/minecraft/shaders/core/entity.vsh` + `entity.fsh`
- `cache/dragon-extract/assets/minecraft/shaders/include/light.glsl`
- `cache/dragon-extract/com/mojang/blaze3d/platform/Lighting.class` - INVENTORY_DIFFUSE_LIGHT_0/1
- `cache/dragon-extract/net/minecraft/client/renderer/RenderPipelines.class` - PER_FACE_LIGHTING #define
- `cache/dragon-extract/net/minecraft/client/model/geom/ModelPart$Cube.class` - polygon UV math
- Harness source: `W:/Workspace/Java/Minecraft-Library/vanilla-reference-harness/src/client/java/lib/minecraft/refharness/`
  (`EntityFrameRenderer.java` has the pose chain; `FlipFaceShadingMixin.java`
  is BLOCK-only, no entity lighting customization)

### Cod's residual 0.55 delta

A narrow column at x=133-135 / y=53-67 still mismatches by ~35 RGB units per
pixel (vanilla=(87,76,60) vs java=(101,88,69)) - a body cube edge case where
WEST and EAST face polygons compete for the same screen column. Likely a
chirality / cube-edge tie-break artifact unrelated to plane-cube logic.

### Skeleton_horse / horse don't improve

Both have canvas dimension mismatch with vanilla (skeleton_horse 510x487 vs
504x498; horse 561x550 vs 555x548). Auto-fit canvas math diverges from the
harness `EntitySweeper.computeFamilyFits`. Separate followup task #12.

## Task #20 - LANDED 2026-05-15: vanilla EntityFace UV layout + mirror handling

Foundational fix at the `EntityFace` enum + downstream mirror handling.
Mechanism details now live in code; this section preserves the historical
findings that aren't re-derivable.

**Code reference:**
- `EntityFace.defaultUv` - per-face atlas layout matching vanilla `ModelPart.Cube`
- `EntityGeometryKitJava.mirrorFace` - EAST/WEST face-lookup swap for mirror=true
- `EntityGeometryKitJava.resolvePolygonUv` - the kit/vanilla slot permutation
- `Vector4f.toUvCorners` - within-strip U-flip for mirror=true

### Why both errors had to be fixed together

The kit's pre-Task-#20 `EntityFace` enum was authored with Bedrock atlas
convention (`[east, north, west, south]` + `[top, bot]`) - **inverted EAST↔WEST
and UP↔DOWN** relative to vanilla's `ModelPart.Cube`. This produced two
opposite-direction errors that ACCIDENTALLY CANCELLED for the kit's existing
iso pipeline:
- Wrong UV coefs put vanilla-WEST texture on cube's EAST face
- Iso entity-camera chain had `det=-1` chirality that X-mirrored the rendering

Net effect: cube +X faces ended up at camera-LEFT but with the WRONG UV strip,
landing texture at the right SCREEN position by coincidence. Fixing one error
in isolation broke both mirror=false (cod regressed 18→48) and mirror=true.

### Mirror handling - harness divergence note (kept for reference)

Earlier 2026-05-14 retests (skeleton_horse 111.38→119.06 with EAST↔WEST swap)
showed that the harness output `vanilla.png` for skeleton_horse legs renders
the leg cubes more transparent than vanilla MC source code would suggest.
Three theories were raised:
1. UV V-axis also needs flipping for mirror=true (180° UV rotation, not just U)
2. effUv permutation tuned for non-mirror cubes interferes with the U-flip
3. **Harness renderer may not use `ModelPart.Cube` mirror semantics directly** -
   Tasks #21-#23 confirmed the harness DOES use standard vanilla rendering
   via `EntityFrameRenderer.java` + `Lighting.Entry.ENTITY_IN_UI`, so theory
   #3 is wrong. Theories 1/2 may still apply if skeleton_horse leg residual
   warrants investigation later.

Parser-side `mirror` capture (73 mirror=true cubes in `entity_geometry_java.json`)
remains correct against vanilla bytecode.

### Empirical post-fix canary results (2026-05-15)

Pre-fix 107.02 baseline (no mirror handling, Bedrock-convention enum) vs
post-fix:

| Entity | Pre-fix | Post-fix | Notes |
|---|---|---|---|
| spider | 8.01 | 6.58 | improved |
| creeper | 57.48 | 18.35 | major improvement |
| skeleton_horse | 107.02 | 125.88 | visually much better; metric higher due to edge AA |
| cod | 18.48 | 44.66 | head/eye correct side |
| polar_bear | 5.52 | 7.04 | similar |
| dolphin | 5.67 | 8.59 | similar |
| horse | 10.20 | 13.68 | similar |
| sheep | 26.98 | 27.77 | similar |
| guardian | regressed | 37.18 | per-entity investigation pending |
| zombie | regressed | 126.72 | pending |
| blaze | regressed | 131.64 | pending |

(Cod and other fish dropped dramatically once Tasks #21-23 also landed - cod
went from 44.66 → 0.55 after the lighting fixes.)

### Visual outcomes preserved

- **skeleton_horse legs**: properly skeletal (transparent with bone outlines)
  instead of "outer face shows inner-face texture"
- **Body rib pattern**: visible through transparent body cube
- **Cod / fish**: head/eye on the correct camera side after FLIP_X compensation

Bedrock parity in `EntityGeometryKit` (legacy) is BROKEN by the enum swap.
Per user direction (2026-05-14): "you no longer need to maintain bedrock parity".

## Task #13 - mirror flag captured in parser (2026-05-14, partial)

User flagged two additional skeleton_horse issues after the cutout-no-cull fix:
1. Bottom ribcage still culled
2. Right front and rear legs reversed / mirrored

Bytecode audit of {@code AbstractEquineModel.createBodyMesh}: vanilla calls
{@code CubeListBuilder.mirror()} (no-arg, = mirror(true)) on the LEFT legs
before their addBox. Right legs have no mirror call. So LEFT legs should have
{@code mirror=true} in JSON; right legs {@code mirror=false}.

**Parser fix**: previously {@code mirror()} no-arg variant wasn't matched (only
{@code mirror(Z)} was handled, and that path popped without propagating);
{@code emitCube} hardcoded {@code mirror=false}. Fixed:
- Added {@code pendingMirror} to {@code ParseState} (resets to false at every
  {@code CubeListBuilder.create()})
- Handle no-arg {@code mirror()} → {@code pendingMirror = true}
- Handle {@code mirror(Z)} → respects the bool
- Per-cube {@code addBox(...,Z,...)} mirror variant saves/restores
  {@code pendingMirror} around the call
- {@code emitCube} writes {@code mirror} to slot 9 of the cube array
- {@code buildBone} writes the JSON {@code mirror} property from slot 9

Result: 73 mirror=true cubes captured across geometries (legs of
abstractequine / abstractequine_1 / humanoid / villager / witch / illager /
guardian / phantom / warden, head of polar_bear, etc).

**Parity slight regression**: applying the mirror flag regressed many entities
by 0.3-1.0 points each (skeleton_horse 107.02 -> 111.38, horse 10.20 -> 11.14,
sheep 26.98 -> 27.73). The kit's {@code rect.toUvCorners(..., mirror)} flips
{@code uMin/uMax} for the rectangle as a whole - that doesn't quite match
vanilla's per-face mirror semantics. Vanilla's {@code ModelPart.Cube} with
{@code mirror=true} REFLECTS the cube vertices through the YZ plane (left/right
X-extents swap) in addition to U-flipping; our kit only U-flips.

Two kit-side attempts tested empirically on skeleton_horse and reverted:
- Invert mirror flag at kit (`!isMirror`): 111.38 -> 123.58, also broke cubes
  with mirror=false (cod 18.48 -> 46.98)
- Add WEST<->EAST UV strip swap on mirror=true: 111.38 -> 119.06

Both worse. The right fix likely needs cube X-vertex reflection
({@code origin.x} negation + extent swap) when mirror=true, OR pre-baking the
reflection at tooling time so the kit stays unchanged. Tracked as Task #20 -
needs investigation on a clean canary (polar_bear T2 5.54 with mirror=true on
head cube, spider 8.29, dolphin 5.67). User-flagged the iconic visible case:
skeleton_horse right legs still render mirrored-wrong after the parser fix.

**Bottom ribcage culled** finding: the body cube IS marked no-cull by my
heuristic (UP/NORTH/EAST UV strips all > 20% transparent: 69.1%/70.0%/36.8%
for skeleton_horse body). Both sides of the body cube render. The user may
be perceiving the body's underside texture appearing on the wrong face due
to the kit's UP↔DOWN UV swap interaction with the mirror flag - same root
cause as the mirror issue, tracked under Task #20.

## Task #13 - skeleton_horse cutout-no-cull detection (2026-05-14)

Skeleton_horse rendered without its iconic ribcage / spine "see-through" pattern
even after A1 / A3 / A8 wins because our kit's {@code shouldCullBackFaces}
heuristic detected the body cube as opaque and culled back faces. The texture
{@code horse_skeleton.png} is 75% fully-transparent ({@code alpha=0}) -
vanilla uses {@code RenderType.entityCutoutNoCull} for skeletal-textured
entities which renders both sides so the back-facing rib outlines are visible
through the front-facing transparent regions.

Added {@code uvTransparencyExceeds(uv, texture, threshold)} helper that counts
fully-transparent texels in the face's UV rectangle. {@code shouldCullBackFaces}
now disables culling when ANY of the three iso-visible faces (UP / NORTH / EAST)
has > 20% alpha-cutout texels. Threshold picked to:
- Trigger on skeletal textures ({@code horse_skeleton.png} 75% transparent,
  {@code skeleton.png}, {@code wither_skeleton.png})
- Skip solid-skinned entities (zombie face cubes have a couple of eye texels
  at alpha=0, well below 20%)

Parity wins:
- skeleton_horse 145.60 -> **107.02** (-38.58), java-cov 33.6% -> 46.0%
  (now matches vanilla-cov 46.2%); ribcage visible
- skeleton 83.60 -> 67.44
- wither_skeleton 48.76 -> 39.51

No regressions on zombie / creeper / spider / blaze / mooshroom / sheep /
horse / polar_bear T2 lock (5.52 held). The heuristic is a per-cube
alpha-proportion check so solid textures stay cull-enabled.

Residual skeleton_horse 107.02: pose / texture alignment, possibly small
lighting nuance on the now-visible back-facing faces. Distinctly smaller delta
than before.

## A8 layer overlays - canvas-fit unions overlay extents (2026-05-14)

Sheep's wool overlay (inflate=1.75 on body cubes, 0.6 on head cubes) was being
emitted by the renderer's overlay loop but **clipped at the canvas edge**: the
canvas was sized to the BASE model's screen bounds (no overlays), so the
~3% larger wool silhouette fell off the canvas on every side.

Vanilla's harness {@code EntityFrameRenderer.walkLayerExtents} walks every
{@code RenderLayer}'s {@code EntityModel}-typed field through the same pose
stack as the primary model and expands the family-fit bounds. Our equivalent:
added {@code computeUnionScreenBounds(base, overlays, transform, modelScale)}
which mirrors the harness behaviour. {@code computeCanvasFit} and
{@code computeCentreAnchor} both consume it.

Sheep 119.30 -> **26.98** (-92.32, ~77% reduction). Canvas changed
{@code 351x386} -> {@code 359x403}, java-cov 66.5% -> 70.9% (now matches
vanilla-cov 70.4%). Other overlay-having entities (slime outer shell, spider
emissive eyes) didn't move because their overlays don't significantly extend
beyond the base silhouette.

Block-model overlays (mooshroom mushrooms, copper-golem flower) are deliberately
NOT included in the union. Tried as Task #18 (2026-05-14): walked a unit cube
{@code [-0.5, 0.5]^3} through each block overlay's pose chain into screen space,
unioned with entity bounds. Mooshroom regressed 85.62 -> 141.19, canvas
expanded 442x482 -> 505x726. Reverted.

**Why**: BOTH the vanilla-reference-harness AND our Task #18 attempt over-pad
the canvas with a wrong assumption about block-overlay extents. Mooshroom's
mushroom-horn block is a **scaled-up** {@code red_mushroom} block. Vanilla's
{@code red_mushroom} model is a cross of two textured planes occupying only a
portion of a {@code [0, 16]^3} cube; the visible silhouette is much smaller than
a full block. Both our Task #18's unit-cube walk and the harness's
{@code walkVisibleExtents} (when it does pick up block-model layers) assume
"block overlay = full unit cube", which over-estimates extent and blows up
canvas dimensions.

The harness sidesteps this by exclusion: {@code walkLayerExtents} matches only
fields whose declared type is {@code Model<?>}, so block-rendering layers
({@code MushroomCowMushroomLayer}, {@code CopperGolemFlowerLayer},
{@code IronGolemFlowerLayer}) drop out - they call {@code BlockRenderDispatcher}
during submit instead of exposing a model field. The mushrooms then render
slightly past the canvas edge if they overhang. Our pipeline matches by
likewise excluding block overlays from family-fit.

**Real fix (deferred, requires harness-side work)**: walk the actual
{@code BakedModel} per-element bounds for each block-overlay block, not a unit
cube. {@code BakedModel.getQuads(null, null, RandomSource.create(0L))} returns
all quads; their vertex positions are in {@code [0, 16]} cube-local frame.
Bake the AABB of those quads, then walk that AABB through the overlay's pose
chain instead of {@code [-0.5, 0.5]^3}. Needs prototyping in the harness first
since the bake math has to match what vanilla's submit produces. Once landed
there, mirror the same AABB-walking on our side.

Mooshroom's residual 85.62 mean delta has other causes (variant texture,
lighting on rotated mushroom blocks). Block-overlay canvas-fit is a small
secondary effect compared to those.

## A4 empirical retest (2026-05-14): reduced scope is also a no-op

Followed the A4 audit's reduced-scope plan: wired a per-entity
{@code SetupRotationsOverride} in {@link EntityRendererJava} that applies the
remaining 3 non-default overrides as model-space pre-transforms ({@code (0,
+11.2, 0)} pre-translate for squid + glow_squid, {@code (0, -1.28, 0)} for
pufferfish, {@code +180°} yaw for shulker). Empirical parity:

| Entity | Pre-override | Post-override |
|---|---|---|
| squid | 6.26 | 145.23 (regressed) |
| glow_squid | 18.40 | 189.63 (regressed) |
| pufferfish | 137.70 | 198.21 (regressed) |
| shulker | 11.50 | 22.97 (regressed) |

Reverted. **Conclusion**: A4 has no exploitable win pool under auto-centred
rendering. Squid + pufferfish's pure translates are absorbed by the kit's
family-fit centring (the {@code modelAnchor = inverse-iso(screen-midpoint)}
re-centres the entity on canvas regardless of any per-entity LER translate).
Vanilla's harness family-fit centring does the same cancellation - the math
matches.

Shulker's 180° yaw effect is a true rotation (not absorbed by centring), but
applying it regressed parity 11.50 -> 22.97. The baseline already reads as
"close to vanilla" because shulker's box geometry is rotationally symmetric
enough that the wrong rotation produces visually-similar pixels.

The wiring (in {@link EntityRendererJava}'s {@code SETUP_ROTATIONS_OVERRIDES}
map) is left in place against future state-dependent overrides where the
override produces a non-translation transform that won't cancel under
auto-centring (e.g. {@code CatRenderer.setupRotations}'s {@code lieDownAmount
> 0} conditional adding a Z-axis rotate, or shulker's non-DOWN attachFace).

## A4 audit (2026-05-14): all 14 overrides under frozen state

Bytecode-walked every `setupRotations` override in client.jar 26.1 against the
harness's pinned static-state preconditions (`bodyRot=0`, `ageInTicks=0`,
`deathTime=0`, `walkAnimationPos/Speed=0`, `isInWater=true` for `AbstractFish`,
`isShaking=false`, no SLEEPING pose, `isAutoSpinAttack=false`, `isUpsideDown=false`,
`lieDownAmount=0`, `rollTime=0`, `swimAmount=0`, `xRot=0`, `xBodyRot=0`, `zBodyRot=0`,
`wiggle=0`, `isPouncing=false`, `isFaceplanted=false`). LER default applied if no
override: `mulPose(YP.rotationDegrees(180 - bodyRot))` = `rotateY(180°)`.

| Renderer | Body collapses to | Differs from default `rotateY(180°)`? |
|---|---|---|
| ArmorStandRenderer | `rotateY(180-bodyRot)`; wiggle conditional skipped | **No** |
| CatRenderer | `super.setupRotations` (LER default); lieDownAmount conditional skipped | **No** |
| CodRenderer | `super`; `sin(0)=0 → rotateY(0)`; isInWater→skip translate+Z90 | **No** |
| DrownedRenderer | `super` (AbstractZombieRenderer→LER); swimAmount=0 → skip | **No** |
| FoxRenderer | `super` (AgeableMobRenderer→LER); `!isPouncing && !isFaceplanted → skip` | **No** |
| IronGolemRenderer | `super`; walkSpeed<0.01 → early return | **No** |
| OcelotRenderer | **does not override setupRotations** | **No** (inherits LER default) |
| PandaRenderer | `super`; rollTime=0 → conditional skip | **No** |
| PhantomRenderer | `super`; rotateX(xRot=0)=identity | **No** |
| SalmonRenderer | `super`; `sin(0)=0`; isInWater→skip translate+Z90 | **No** |
| TropicalFishRenderer | `super`; `sin(0)=0`; isInWater→skip | **No** |
| **PufferfishRenderer** | `translate(0, cos(0)*0.08, 0) = translate(0, 0.08, 0); super` | **Yes** (Y-nudge 0.08) |
| **SquidRenderer** | `translate(0, 0.5, 0); rotateY(180); translate(0, -1.2, 0)` (NO super call) | **Yes** (replaces) |
| **ShulkerRenderer** | `super(180+bodyRot,scale) = rotateY(0); rotateAround(attachFace.opposite.rotation, 0, 0.5, 0)` | **Depends on attachFace default** (likely DOWN → identity, no-op) |

The Round 8 prediction that A4 explains ocelot (+58) / cat (+24) is wrong: ocelot
has no override, cat's body is a no-op for our frozen state. Those regressions
have other roots (likely A7 variant texture resolution since both use
`CatVariant.assetInfo(isBaby).texturePath()` as their texture source).

Pufferfish (+93) is the only top-pool regression A4 actually addresses, and only
weakly (0.08 Y-translate is a small fraction of pufferfish's screen extent). The
big squid case isn't in the top regression pool because squid still renders
roughly-correctly without the translates; the iconic break is canvas overhang on
the tentacles below the harness fit, not a delta-against-reference miss.

Conclusion: A4 is a small win compared to A5 (Bone.scale for happy_ghast +446,
elder_guardian +185, ghast +142) or A7 (cat / ocelot / chicken variants). Defer
A4 unless squid/pufferfish/shulker specifically need fixing.

## Foundation status (Round 6, 2026-05-14)

Two surgical fixes landed and were verified on a single-cube unit-test fixture:

1. **Bone rotation composition order** in `EntityGeometryKitJava.pivotCenteredRotation`:
   swapped from `Z·Y·X` to `X·Y·Z` row-form (matches vanilla `Quaternionf.rotationZYX`
   semantics - extrinsic X-first/Y/Z). Insensitive entities byte-stable
   (magma_cube/cod/salmon/polar_bear T3); sensitive entities improved (parrot -55,
   spider -19, guardian -7).
2. **Foundation invariant lock** in `EntityGeometryKitJavaTest`: asserts FLIP_Y +
   FLIP_NORMAL_Y + UV-swap (UP↔DOWN) + UV-permutation + winding-reversal form a
   self-consistent set on a single textured cube. The kit's coupled-Y-flip system is
   **concretely accurate** - emit-order cross product agrees with stored normal
   (camera-independent winding invariant), UVs land in the expected bedrock-strip
   region, UP-face triangles sample from the DOWN strip slot as expected.

Verified cod head direction = -Z in model space (`head` bone cube spans Z[-3,0],
`tail_fin` at Z[7,11]). Round 5's open question on cod orientation is resolved:
both vanilla and our pipeline render cod head at screen-left; the harness's
`(210°, 45°)` and our `(30°, 225°)` iso poses happen to agree on the X axis for
roughly-axis-aligned bodies like fish, which is why cod doesn't surface the iso
divergence clearly. Bigger iso impact shows on rotated bones (multi-axis entities)
and lighting direction (which face each light strikes after iso).

### Foundation invariants pinned by the unit test

`EntityGeometryKitJavaTest` exercises a single bone, single 2x2x2 textured cube
at origin (no parent, no rotations) and asserts seven invariants. The
load-bearing assertion is **winding-vs-stored-normal agreement**: for every
emitted triangle, `(p1-p0) × (p2-p0) ⋅ stored_normal > 0`. This is camera- and
projection-independent - it locks down the kit's reversed-emission convention
((0,2,1) and (0,3,2) instead of natural CCW (0,1,2) and (0,2,3)) against any
future refactor that touches windings without simultaneously updating the
position Y-flip + normal Y-flip pair.

Other invariants and why they matter:
- 12 triangles emitted (6 faces × 2 triangles) - cube completeness
- Y-flip applied (vertices in auto-fit bounds) - position normalisation
- Normals point outward from cube center - stored-normal direction
- Each cardinal face has exactly 2 triangles - face enumeration completeness
- UVs land in the bedrock-strip atlas region (u[0, 8/64], v[0, 4/64] for the
  2x2x2 cube on 64x64) - UV resolution staying inside the cube's atlas slot
- UP-cube-face triangles sample from the DOWN strip slot - the kit's UP↔DOWN
  swap compensation is wired correctly under FLIP_Y

The test catches:
- Adding/removing the kit's Y-flip without updating the winding-reversal
- Changing the UV-permutation arrays without updating the UP↔DOWN swap
- Breaking the bedrock atlas layout coefficients in `EntityFace.defaultUv`

The test does NOT catch:
- Iso pose constant mismatch (block vs entity) - that's a renderer-side concern
- Lighting frame mismatch (camera vs model) - lighting is baked at kit time but
  the test doesn't check shade values, only normal directions
- Bone hierarchy / chain composition bugs - test uses one bone

### Multi-axis sensitivity (Round 6 rotation-order fix scope)

Entities with at least 2 non-zero axes on at least one bone rotation - these are
the ones where Z·Y·X vs X·Y·Z rotation composition order produces visually
different output. Single-axis rotations (cod's pure-Z 45° fin rotations,
silverfish's pure-Z) are insensitive because the other two factors are identity
matrices that commute trivially.

From `entity_models_java.json` (bone.rotation field) - 12 entities:
hoglin (ears 3-axis), guardian (spikes 90°+45°), parrot (wings pitch+yaw),
breeze (rods 3-axis), phantom (wings 3-axis), witch (hat pitch+roll), spider
(legs yaw+roll), dolphin (fins pitch+roll), guardian_1 (= elder_guardian),
bogged (mushrooms pitch+roll), snow_golem (arm yaw+roll), armadillo (ears 3-axis).

From `entity_bind_poses.json` (bone.bindPoseRotation overlay) - 9 entities:
cave_spider, spider (8 leg bones each), witch (3 hat bones), armadillo (ears),
bogged (mushrooms), breeze (rods), dolphin (fins), parrot (wings), snow_golem
(arm).

Entities NOT in either list have only single-axis or zero rotations everywhere.
The Round 6 rotation-order fix is **provably byte-identical for them** (the
isZero early return + single-axis-commutes-with-identity property). Confirmed
empirically: magma_cube, cod, salmon, polar_bear all delta=0.00 across the
rotation-order swap.

Net conclusion: the Y-flip-based foundation is internally consistent. Remaining
delta is dominated by the iso-pose constant mismatch (Group A item A1) and
camera-frame vs model-frame lighting (A3), not foundation defects in windings or
UV mapping.

### JOML rotation conventions (locked-down reference)

JOML's `Quaternionf` has TWO different "Tait-Bryan" factories with OPPOSITE
application order. Vanilla uses both in different paths:

| Factory | Quaternion product | Visual effect on v_col | Vanilla site |
|---|---|---|---|
| `rotationZYX(z, y, x)` | `q_z * q_y * q_x` | X first, then Y, then Z applied to v | `ModelPart.translateAndRotate` (bone rotations) |
| `rotationXYZ(x, y, z)` | `q_x * q_y * q_z` | Z first, then Y, then X applied to v | GUI `display.*` poses (block icon, harness `ISO_ROTATION`) |

The naming refers to QUATERNION PRODUCT ORDER, which is the OPPOSITE of the
visual application order. JOML applies `q * v * q^-1`, and quaternion products
compose right-to-left when applied to vectors. So `rotationZYX = q_z * q_y * q_x`
applies `q_x` to `v` first.

**Row-vector convention conversion**: our `Matrix4f.createRotationX(θ)` stored
row-major produces the same VISUAL effect on `v_row × M` that col-form
`R_X(θ) × v_col` produces (verified empirically: `(0,1,0) × createRotationX(90°)
= (0,0,1)` matches `R_X(90°) × (0,1,0)^T = (0,0,1)^T`). The Matrix4f data is the
transpose of the standard col-form rotation matrix, but used in row-form
multiplication this self-corrects.

Composition rule:
- For `v_row × A × B × C`, A is applied first to v.
- For `M_col = R_C × R_B × R_A` (col form right-to-left), A is innermost.
- Row-form equivalent: `createA × createB × createC`.

**Bone rotation path** (vanilla `rotationZYX(zRot, yRot, xRot)` applies X-first):
```java
// CORRECT (this codebase, EntityGeometryKitJava.pivotCenteredRotation post-Round 6):
Matrix4f.createRotationX(pitch)
    .multiply(Matrix4f.createRotationY(yaw))
    .multiply(Matrix4f.createRotationZ(roll));
```

**GUI display pose path** (vanilla `rotationXYZ(pitch, yaw, roll)` applies Z-first):
```java
// CORRECT (this codebase, IsometricEngine.buildGuiDisplayTransform):
Matrix4f.createRotationZ(roll)
    .multiply(Matrix4f.createRotationY(yaw))
    .multiply(Matrix4f.createRotationX(pitch));
```

Mixing these up was the Round 5 bone-rotation bug (used `Z·Y·X` for bones when
`X·Y·Z` was correct). The fix landed in Round 6.

## Round 7 (attempted, reverted): JSON-bake MeshTransformer.scaling

Tried to bake `MeshTransformer.scaling(F)` into `entity_geometry_java.json` by
multiplying bone pivots, cube origins, cube sizes, cube inflate, and cube pivots
by F at parse time. Goal: unlock happy_ghast / elder_guardian (entities with
nested bone hierarchies where the renderer-side `RENDERER_SCALE_OVERRIDES`
approach fails).

**Outcome**: polar_bear T3 regressed from mean 36.54 to mean 117.58. Reverted.

**Why it failed**: vanilla `MeshTransformer.scaling(F)` is NOT a uniform scale
around origin. From bytecode decompilation:

```java
public static MeshTransformer scaling(float F) {
    float dy = 24.016f * (1f - F);  // 1.501 blocks * 16 px/block = 24.016 px
    return mesh -> mesh.transformed(pose -> pose.scaled(F).translated(0, dy, 0));
}
```

This expands per-`PartPose` as:
- `pose.scaled(F)`: pivot.xyz *= F, scale.xyz *= F (PartPose carries a scale field)
- `.translated(0, dy, 0)`: pivot.y += 24.016 * (1-F)

Net: scale around `y = 24.016` (the LER chain's translate(0, -1.501, 0) anchor =
entity's feet). NOT around origin. Cube origins/sizes are NOT directly touched -
the PartPose scale field propagates to cube vertices at submit time via
`poseStack.scale(...)`.

The naive "multiply pivot+origin+size by F" bake had two failure modes:
1. Scale anchor wrong (origin vs y=24.016) - drifts pivots away from where vanilla puts them
2. UV double-application: cube UV width comes from `cube.size`, so scaling size
   in JSON also scales the UV region. Result: cube faces sample a region 1.2x as
   wide as they should, including transparent/wrong-cube pixels.

**Correct bake needs**: bone-scale support in `EntityModelData` + the kit. The
PartPose scale field would propagate to cube vertices at the kit's chain-resolve
stage (`composeCubeTransform`) without affecting cube.size or UV mapping. The Y
translation `24.016*(1-F)` would be added to the bone's pivot.y. Tracked for a
future session.

The current renderer-side `RENDERER_SCALE_OVERRIDES` is "close enough" for flat-
hierarchy entities (polar_bear, ghast) because it scales around the screen-
midpoint anchor (computed via inverse-iso projection of the silhouette midpoint),
which for axially-symmetric quadrupeds is near the geometric center. For nested
hierarchies (elder_guardian's spike loop, happy_ghast's leash anchor) this
approximation fails because the bone pivot doesn't scale with the cube vertices,
leaving cubes drifting from where their pivots expect them.

### Bytecode reference (verified 2026-05-14)

`MeshTransformer.scaling(float)` decompile:
```
0: ldc           #3   // float 24.016f
2: fconst_1                            // stack: 24.016, 1
3: fload_0                             // stack: 24.016, 1, F
4: fsub                                // stack: 24.016, 1-F
5: fmul                                // stack: 24.016*(1-F)
6: fstore_1                            // dy = 24.016*(1-F)
7: fload_0                             // stack: F
8: fload_1                             // stack: F, dy
9: invokedynamic ... MeshTransformer
14: areturn
```
Lambda body (`lambda$scaling$1`) on each PartPose:
```
pose.scaled(F).translated(0, dy, 0)
```

`PartPose.scaled(float, float, float)` field assignments (in constructor call order):
- `x_new = x * sx`, `y_new = y * sy`, `z_new = z * sz`
- `xRot, yRot, zRot`: copied unchanged
- `xScale_new = xScale * sx`, `yScale_new = yScale * sy`, `zScale_new = zScale * sz`

`PartPose.translated(float dx, float dy, float dz)`:
- `x_new = x + dx`, `y_new = y + dy`, `z_new = z + dz`
- rotations + scales copied unchanged

So `pose.scaled(F).translated(0, dy, 0)` on a PartPose with original (x, y, z,
xRot, yRot, zRot, xScale=1, yScale=1, zScale=1) yields:
- pivot: `(F*x, F*y + 24.016*(1-F), F*z)`
- rotation: unchanged
- scale: `(F, F, F)` (assuming original was 1)

The PartPose `xScale/yScale/zScale` fields are consumed in `ModelPart.render`
which translates by pivot, applies rotation, then calls `poseStack.scale(sx, sy,
sz)` before rendering the cube list. So the scale is applied AFTER the bone is
positioned at its pivot - the scale factor scales the cubes (their local-to-bone
geometric extents) but not the pivot position itself.

The magic constant `24.016` = `1.501 * 16`. The 1.501 is the LER chain's
`translate(0, -1.501, 0)` (in blocks) and 16 is pixels-per-block in the entity
model frame. Geometrically: scaling is anchored at the world-space y=0 of a
default entity render, which is the entity's feet after the LER translate. The
24.016 expresses that anchor in pixel coordinates so PartPose values (also in
pixels) line up.

### Inline vs static-field detection patterns

Two distinct bytecode patterns in the wild:

**Inline** (PolarBearModel, HappyGhastModel, GhastModel) - call appears in
`createBodyLayer`:
```
311: invokestatic LayerDefinition.create(...)
314: ldc 1.2f
316: invokestatic MeshTransformer.scaling(F)
319: invokevirtual LayerDefinition.apply(MeshTransformer)
```
The parser's current INVOKESTATIC handler at `ToolingBlockEntities.java:~1251`
would see the `scaling(F)` call directly. Capture-and-bake works for this case
(once the bake math is correct).

**Static field** (GuardianModel's `ELDER_GUARDIAN_SCALE`) - call appears in
`<clinit>`, referenced via getstatic:
```
// in createElderGuardianLayer():
0: invokestatic createBodyLayer:()LayerDefinition
3: getstatic ELDER_GUARDIAN_SCALE:LMeshTransformer    <-- not seen by parser
6: invokevirtual LayerDefinition.apply(MeshTransformer)
9: areturn

// in <clinit>:
0: ldc 2.35f
2: invokestatic MeshTransformer.scaling(F)            <-- captured if <clinit> walked
5: putstatic ELDER_GUARDIAN_SCALE
```
Unlocking the static-field pattern requires either:
1. Walking `<clinit>` of the model class and tracking putstatic to MeshTransformer
   fields, OR
2. Resolving `getstatic` of a MeshTransformer field by lazily walking `<clinit>`
   on demand.

**Catalog of MeshTransformer.scaling call sites** (verified by decompile,
2026-05-14):

| Model | F | Pattern | Notes |
|---|---|---|---|
| PolarBearModel | 1.2 | inline | flat hierarchy, locked T3 via `RENDERER_SCALE_OVERRIDES` |
| HappyGhastModel | 4.0 | inline | nested hierarchy (tentacles parented to body); `RENDERER_SCALE_OVERRIDES` regresses |
| GhastModel | 4.5 | inline | flat-ish (9 tentacles parented to root); workable via override |
| GuardianModel (ELDER_GUARDIAN_SCALE) | 2.35 | static field | not currently captured |
| EquineModel donkey | unknown | TBD | research-doc reference, verify |
| EquineModel mule | unknown | TBD | research-doc reference, verify |

### Correct bake plan (in-flight)

Status as of 2026-05-14:

**Stage 1 [LANDED]: Schema field on `EntityModelData.Bone`**

Added scalar `float scale = 1f` (not `Vector3f` - the existing JSON shape already
emits scalar from `PartPose.scaled(F)` captures, and `MeshTransformer.scaling(F)`
is also a scalar). Equality / hashCode updated. Convenience constructors preserved
to keep all 6 existing call sites compile-stable; the 4 `EntityModelLoader` clone
sites + 1 `ToolingEntityModels` site updated to pass `bone.getScale()` through
clones so the field survives overlay / inflate / bind-pose processing.

**Stage 2 [LANDED]: Parser captures `MeshTransformer.scaling(F)` into bone.scale**

`ToolingBlockEntities.parseLayerMethod` now captures the F from
`MeshTransformer.scaling(F)` into `ParseState.meshTransformerScale` (multiplies on
repeat calls), then re-walks the emitted bone tree in `applyMeshTransformerScaling`
and multiplies F into each bone's `scale` field. The `pivot` adjustment
(`pivot.y = F * y + 24.016*(1-F)`, `pivot.xz *= F`) is deliberately NOT applied at
this stage - baking it without a corresponding kit-side scale consumer would put
cubes 3.6+ units off (the regression Round 7 hit, re-verified during Stage 2
prototyping when both pivot + scale were baked simultaneously). Stage 3 will land
the pivot bake together with the kit reader and `RENDERER_SCALE_OVERRIDES` removal
in one atomic change. Captures landed: polar_bear (1.2), ghast (4.5), happy_ghast
(4.0). Donkey / mule MeshTransformer.scaling(F) sites read F from `fload_0`
(method param) which our synthetic `Source` doesn't populate - the capture sees
`f = 0` and now skips with diagnostic.

Verified byte-stable: polar_bear holds at mean 30.66 (Round 8 lock), ghast at
142.68, happy_ghast at 446.52, all foundation tests pass.

**Stage 3 [LANDED]: Kit consumes bone.scale + pivot bake + drop renderer overrides**

Atomic change across kit + tooling + renderer:
- `EntityGeometryKitJava` (3 sites: `buildTrianglesWithScale`, `computeScreenBounds`,
  `computeBounds`): multiply local cube `origin`, `size`, and `inflate` by
  `bone.getScale()` before the bone-pivot translate. Mathematically equivalent to
  vanilla's `pivot + R * (s * v_local) = pivot + s * R * v_local` for any
  uniform-scale-commuting rotation R.
- `applyMeshTransformerScaling` now also bakes pivot: `pivot.x *= F`,
  `pivot.y = F * y + 24.016*(1-F)`, `pivot.z *= F`.
- `RENDERER_SCALE_OVERRIDES` reduced from 4 → 2 entries; polar_bear (1.2) and
  ghast (4.5) dropped. Kept wither (renderer-side scale) and giant (state.scale).

Numbers vs prior round:
- polar_bear: 30.66 → **30.66** (T3 lock held exactly)
- happy_ghast: 446.52 → **87.52** (-359 mean delta, ~80% reduction)
- ghast: 142.68 → 148.29 (+5.6 - noise from missing tentacles / lighting, not A5)
- cod 29.71, magma_cube 12.87, salmon 14.08, wither 12.67: all byte-stable
  (no MeshTransformer.scaling captures → no JSON change)
- elder_guardian: 185.39 unchanged (still waiting on Stage 4 static-field walker)
- giant: 123.66 unchanged (state.scale path, not MeshTransformer)

**Stage 4 [LANDED]: Static-field MeshTransformer walker (elder_guardian)**

`GuardianModel.createElderGuardianLayer` reads `ELDER_GUARDIAN_SCALE = 2.35` via
`getstatic` on a `MeshTransformer` field initialised in `<clinit>` as
`MeshTransformer.scaling(2.35f)`. `ToolingBlockEntities.resolveStaticMeshTransformer`
lazily walks the field owner's `<clinit>` looking for the canonical
`ldc F; invokestatic MeshTransformer.scaling; putstatic <field>` triplet and folds
F into `state.meshTransformerScale`. Cached per-field across the parse. Elder_guardian
185.39 -> 162.58 (-22.8). Guardian (base, no scale) unchanged at 52.71.

**Stage 5 [LANDED]: Donkey / mule synthetic-Source `paramFloatValues`**

Their `createBodyLayer(float F)` reads the body scale from `fload_0`.
`DonkeyModel.DONKEY_SCALE = 0.87f` / `MULE_SCALE = 0.92f` (both `ConstantValue`
attributes on `static final float` fields) are pushed at the `LayerDefinitions.createRoots`
call site. `JavaEntityLayerDefinitionResolver` captures the `pendingFloat` at
each `(F)LayerDefinition` invokestatic site into `Resolution.defaultFloatParam`;
`ToolingJavaEntityModels` reads it into `paramFloats[0]` on the synthetic Source.
Dedup key extended with `#fparam=X` so donkey (0.87) and mule (0.92) get separate
geometries (`geometry.donkey` + `geometry.donkey_1`).

**Stage 6 [LANDED]: LayerDefinitions-level `.apply(MeshTransformer)` chains**

A third pattern surfaced during the broader audit: `LayerDefinitions.createRoots`
applies a MeshTransformer to the LayerDefinition AFTER the factory returns, via
either:
- `.apply(getstatic <Y>_TRANSFORMER)` - cat's `AdultCatModel.CAT_TRANSFORMER`
  (0.8f, static field), llama family, etc.
- `.apply(aload <slot>)` from a slot populated by a local
  `ldc F; invokestatic MeshTransformer.scaling; astore N` - horse / giant / husk
  / wither_skeleton / villager / witch / illager family.

`JavaEntityLayerDefinitionResolver` now tracks both: a `meshTransformerSlots`
map keyed by JVM local for the second pattern, and a `<clinit>` walker (same
shape as Stage 4's, kept private to the resolver to avoid reaching into
block-entity internals) for the first. Composed F multiplies into
`Resolution.appliedMeshTransformerScale`. The parser pre-seeds
`state.meshTransformerScale` from `source.appliedMeshTransformerScale()` at the
start of `parseLayerMethod` so the existing `applyMeshTransformerScaling` post-walk
folds it through identically. Dedup key extended with `#appliedMT=X`.

Captured (full list as of 2026-05-14, in addition to Stages 2 + 4 + 5):
- `geometry.adultfeline` 0.8 (cat + 11 variants)
- `geometry.abstractequine` 1.1 (horse)
- `geometry.humanoid` 6.0 (giant - removed from `RENDERER_SCALE_OVERRIDES`)
- `geometry.humanoid_1` 1.0625 (husk)
- `geometry.illager` 0.9375 (evoker, illusioner, pillager, vindicator)
- `geometry.skeleton` 1.2 (wither_skeleton)
- `geometry.villager` 0.9375 (villager, wandering_trader)
- `geometry.witch` 0.9375 (witch)

Parity wins (notable): horse 94.12 -> 27.27 (-66.85), zombie_horse 14.89 (new),
mule 27.53 (Stage 5 + 6 combined), evoker 46.28, illusioner 37.90, vindicator
47.69, witch 68.30. Polar_bear T3 holds at 30.66.

Cat 90.35 -> 98.16 is NOT a Stage 6 regression: the harness rendered the "tamed"
cat texture variant while our pipeline picked "black". A7 (variant resolution)
territory, not scaling. Villager FAILED is a missing reference-cache directory
(separate from any A5 work), not a regression.

Skeleton_horse 149.27 was previously sharing `geometry.horse` and "incidentally"
benefiting from horse's wrong scale; Stage 6's split (skeleton_horse has no
.apply chain) exposes its true delta. Real fix lies elsewhere (probably texture
or LER chain).

**Stage 7 [DEFERRED, separate concern]: equipment-variant render layers**

`DonkeyModel.DONKEY_TRANSFORMER` (built via `invokedynamic` in `<clinit>`) and
similar static-field MeshTransformers on equine models do NOT scale the base
body - they're applied to the chest / saddle / saddle+chest equipment-variant
layers (and the horse-family equivalents: saddle / armor / saddle+armor). Vanilla
only renders these when the entity carries the matching equipment, which the
static renderer's transient zero-state entity never does. Out of scope for A5;
tackle as its own task alongside other equipment-variant rendering (sheep wool
shears state, mooshroom variant mushrooms, llama decor, etc).

---

## Win conditions

Three tiers. Each entity's required tier is set in the
[Per-entity targets](#per-entity-targets) table below.

| Tier | Threshold | Measurement | Notes |
|---|---|---|---|
| **T1 - bit-identical** | `mean_abs == 0` AND `iou == 1.0` AND `cov_imbalance == 0` | Pixel-by-pixel byte equality (post-canvas-pad) | Reserved for the simplest entities where the harness's authored pose maps directly onto our static geometry; expect at most ~5 entities (cod, salmon, silverfish-class). |
| **T2 - sub-1% delta** | `mean_abs < 10.0` AND `iou >= 0.98` AND `cov_imbalance < 0.02` AND every quadrant `|q| < 4.0` | Mean ARGB delta per pixel, where 1020 is "every channel saturated" - 10.0 ~= 1%. | Default target for the mid-complexity entities (most cubic mobs, fish, mooshroom-class). |
| **T3 - sub-5% delta** | `mean_abs < 50.0` AND `iou >= 0.92` AND `cov_imbalance < 0.05` | Same metric, looser cap. | Reserved for entities whose vanilla render involves features asset-renderer doesn't yet implement (held items, armor, animation poses). Locking T3 says "geometry + lighting + scale are right, the remaining delta is the unimplemented overlay". |

Measurement command (one-shot, one entity):
```bash
./gradlew :asset-renderer:entityParityVanilla -PentityId=minecraft:cod
python scripts/parity_analysis/analyze_diff_panels.py --out /tmp/check
grep ^minecraft_cod /tmp/check/diff_ranking.tsv
```

Win-criterion check on the TSV:

```
entity                primary               mean_abs  iou     cov_imb  diag_split  ...
minecraft_cod         matches_or_minor      < 10.0    >= 0.98 < 0.02   < 4.0       ...
```

T1 requires the additional manual `cmp -l vanilla.png java.png` byte-comparison;
T2 / T3 just need the TSV row to fit.

Quadrant signed-luma values (`q_tl`, `q_tr`, `q_bl`, `q_br`) flip to negative when
java is brighter than vanilla in that quadrant. T2 requires absolute values
< 4.0 in all four; that's the empirical floor below which the per-quadrant signal
is just sample noise from texture quantization, not a lighting issue worth chasing.

---

## Harness ground-truth recipe (what we're matching)

Located at `vanilla-reference-harness/src/client/java/lib/minecraft/refharness/`.
This is exactly what produces the reference PNGs.

### Render loop summary

```
RefHarnessClient (Fabric mod entry, gated on -Drefharness.headless)
 -> WorldBootstrap creates flat world
 -> EntitySweeper.build() lists every BuiltInRegistries.ENTITY_TYPE
    (excluding MobCategory.MISC except ArmorStand)
 -> Pre-pass: measureBounds() per (entity, variant) -> family-fit
    bounds = union of per-cube vertex extents through the iso transform,
    skipping alpha=0 polygons
 -> For each entity:
       entity = EntityType.create(level, EntitySpawnReason.LOAD)  // never ticked
       zeroRotations(entity)                                       // yBodyRot/yHeadRot/xRotO/yRotO = 0
       NBT-load variant if applicable (cow / pig / chicken / frog / wolf)
       state = renderer.createRenderState(entity, partialTick=0.0f)
       state.shadowPieces.clear()
       state.outlineColor = 0
       state.lightCoords = 15728880     // FULL_BRIGHT_LIGHT (sky 15 << 20 | block 15 << 4)
       (mixins fire here: setupAnim skipped, transient state pinned)
       renderInternal(client, entity, ISO_ROTATION, canvasW, canvasH, familyFit, ...)
```

### EntityFrameRenderer.renderInternal pose stack

For a `LivingEntityRenderer` target (most entities):

```
PoseStack ps = new PoseStack()
ps.translate(translateX, translateY, 0)                  // family-fit centering
ps.scale(scale, scale, scale)                            // pixels per block from family-fit
ps.scale(1, 1, -1)                                       // <<< CHIRALITY COMPENSATION
ps.mulPose(ISO_ROTATION)                                 // rotationXYZ(210°, 45°, 0°)
lighting.setupFor(Lighting.Entry.ENTITY_IN_UI)
dispatcher.submit(state, cameraRenderState, 0,0,0, ps, storage)
  -> EntityRenderer.submit applies its own internal chain:
       ps.scale(state.scale, state.scale, state.scale)   // per-instance scale (Giant=6, etc)
       renderer.setupRotations(state, ps, bodyRot=0, scale)   // VIRTUAL
       ps.scale(-1, -1, 1)                                // LER's own chirality flip
       renderer.scale(state, ps)                          // virtual; Wither overrides with scale(2,2,2)
       ps.translate(0, -1.501, 0)                         // model offset
       model.setupAnim(state)                             // SKIPPED via SkipSetupAnimMixin
       model.root().render(...)                           // walks parts, applies translateAndRotate
```

For `EnderDragonRenderer` (extends `EntityRenderer`, not `LivingEntityRenderer`):

```
ps.translate(0, 0, 1)
ps.scale(-1, -1, 1)
ps.translate(0, -1.501, 0)
// no per-state scale, no setupRotations override
```

For all other non-LER targets: the renderer-class instance gets `ISO_ROTATION` rotated
by 180° around Y before applying.

### `ISO_ROTATION = rotationXYZ(210°, 45°, 0°)`

JOML's `rotationXYZ` is `R_x(210) * R_y(45) * R_z(0)`. Empirically locked via a 24-step
yaw + 576-frame pitch×roll sweep against a cow. This is **NOT** the same rotation as
`EulerRotation.STANDARD_ISO_BLOCK = (30°, 225°, 0°)` used elsewhere in asset-renderer.
The `(30°, 225°)` pose is the inventory **block-icon** pose; the `(210°, 45°)` pose is
what vanilla uses for the inventory **entity preview** pipeline. They differ by 90°
yaw + sign flips. Our Java pipeline currently uses `(30°, 225°)` - this is the root
cause of every `iso_pose_diag_split` cluster failure.

### Lighting (`Lighting.Entry.ENTITY_IN_UI`)

Vanilla uses two directional lights expressed in **camera frame** (i.e. post-iso):

```
INVENTORY_DIFFUSE_LIGHT_0 ~= normalize(0.2, -1, 1)    // Y-down convention
INVENTORY_DIFFUSE_LIGHT_1 ~= normalize(-0.2, -1, 0)   // Y-down convention
shade = min(1, (max(0, dot(L0, n)) + max(0, dot(L1, n))) * 0.6 + 0.4)
```

Our `RenderEngine.ENTITY_IN_UI_LIGHT_0/1` Y-flips both lights AND the normal, which
is identity. The flip-both convention is fine; what's wrong is that we dot against the
**model-frame** normal, while vanilla dots against the **camera-frame** normal. Iso
pose mismatch + camera-frame lighting = the diag-split signature.

### Mixins (state-pinning contract)

Every behaviour the harness depends on; our Java pipeline must reproduce these
statically:

| Mixin | What it pins | Why our pipeline cares |
|---|---|---|
| `SkipSetupAnimMixin` | Redirects `EntityModel.setupAnim` to no-op for every LivingEntityRenderer | All entities render in `createBodyLayer`'s authored bind pose - matches our static-mesh approach |
| `FreezeAnimationStateMixin` | `ageInTicks`, `walkAnimationPos/Speed`, `deathTime`, `ticksSinceKineticHitFeedback`, `wornHeadAnimationPos` = 0; `isInWater = true` for `AbstractFish` | Eliminates partial-tick drift. The `AbstractFish` flag forces upright fish pose (cod/salmon/tropical_fish/pufferfish) - without it our java renderer would need to apply the +90° Z rotation those renderers add when not in water |
| `BeeStateMixin` | `state.isOnGround = true` | Skips wing-flap math + body-bob; produces flat-wing rest pose |
| `GuardianStateMixin` | `spikesAnimation = 1.0`, `tailAnimation = 0.0`, `lookAtPosition = null`, `lookDirection = null` | Spikes extended (iconic), tail straight back, no eye-tracking |
| `PhantomStateMixin` | `flapTime = 0` | Wings at rest (sin(0)=0 per segment) |
| `PufferfishStateMixin` | `puffState = STATE_FULL` | Routes through full-puff sub-model (3 sub-models in PufferfishRenderer) |
| `EnderDragonModelMixin` | Cancels setupAnim | Rest pose (no jaw-open, no neck/tail historical-pos bend) |
| `WitherBossModelMixin` | Cancels setupAnim (now redundant - SkipSetupAnimMixin already covers it) | Documentation for ribcage/tail base offset reasoning |
| `FlipFaceShadingMixin` | Returns `(down=0.5, up=1.0, N=S=0.6, W=E=0.8)` from `ClientLevel.cardinalLighting()` | Flips N/S vs E/W shading so block-style overlays (mooshroom mushroom) match inventory-look; only affects block rendering paths |
| `HideSkyMixin`, `HideCloudsMixin`, `HideHandMixin`, `HeadlessWindowMixin` | Cosmetic - transparent background, no window | Not relevant to our pipeline |

Our pipeline implications:

1. **Fish must render upright** (no +90° Z rotation). Currently we don't apply
   `setupRotations` at all - this is incidentally correct for fish but only by luck.
2. **Guardian must render with spikes extended.** The bytecode literal-walker for
   `createBodyLayer` reads bind-pose pivots; we need to also include the spike
   extension effect that `setupAnim` applies for `spikesAnimation=1`. **OR** we
   author the spike-extended pose as the bind pose for the parser.
3. **Pufferfish needs the full-puff sub-model.** Our parser may currently produce
   the default model.
4. **Phantom / Bee / Guardian** at frame-0 of setupAnim already produce a useful
   silhouette as long as the source-pinned animation state values (above) are baked
   in or skipped.

---

## Current Java pipeline (asset-renderer side)

### Files

| File | Role |
|---|---|
| `EntityRendererJava.java` | Top-level renderer. Loads `entity_models_java.json`, computes canvas + anchor, calls kit, rasterizes. |
| `kit/EntityGeometryKitJava.java` | Walks bones + cubes, applies bone chain + cube/bind-pose rotation, emits triangles in Y-flipped screen frame. |
| `geometry/EntityFace.java` | Per-cube-face corner + normal + UV mapping. |
| `tooling/ToolingJavaEntityModels.java` | Tooling entry point: runs vanilla `createBodyLayer` reflectively (or via ASM bytecode), emits the Java JSON. |
| `tooling/entity/JavaEntityProceduralLoops.java` | ASM bytecode parser for `createBodyLayer` (the "Java parser"). |
| `tooling/entity/JavaEntityTextureResolver.java` | Maps entity ids to texture refs. |
| `tooling/entity/JavaEntityVariantResolver.java` | Variant texture / model handling. |
| `tooling/entity/JavaEntityLayerScanner.java` | Layer (overlay) detection from `addLayer` bytecode. |
| `tooling/entity/JavaEntityLayerDefinitionResolver.java`, `JavaEntityOverlayResolver.java`, `JavaEntityBlockOverlayResolver.java` | Overlay-specific resolution. |
| `tooling/entity/EntityRendererDiscovery.java` | Maps each entity type to its renderer class. |

### Critical divergences from harness pipeline

| # | Divergence | Cluster impact |
|---|---|---|
| 1 | **Iso pose**: ours `R_Z(0) * R_Y(225) * R_X(30)`. Harness `R_X(210) * R_Y(45) * R_Z(0)`. **Not equivalent**, differ by mirror + transpose-like permutation. | `iso_pose_diag_split` (13), `lighting_lr_axis` (22), `lighting_tb_axis` (6) - **41 entities** depend on this fix one way or another |
| 2 | **Vanilla LER submit chain missing**: no `scale(state.scale)`, no virtual `setupRotations`, no `scale(-1,-1,1)`, no per-renderer `scale(state, ps)`, no `translate(0, -1.501, 0)`. We only apply iso rotation + scale + centering. | `silhouette_partial` (39), `silhouette_severe` (6) - **45 entities** rendering at wrong pose / scale / chirality |
| 3 | **No per-renderer setupRotations override**: 14 renderers (ArmorStand, Cat, Cod, Drowned, Fox, IronGolem, Panda, Phantom, Pufferfish, Salmon, Shulker, Squid, TropicalFish) override `setupRotations`. We don't reproduce any. | Several `silhouette_partial` entries; the iconic case is squid's `translate(0, -1.2, 0)` that drops tentacles below the harness canvas |
| 4 | **state.scale field unimplemented**: vanilla's `Giant` carries `state.scale=6`; LER applies `scale(state.scale)`. We hardcode `wither=2` and `giant=6` in a map. | `silhouette_severe` for ghast (4.5x), happy_ghast, polar_bear (1.2x) - **`MeshTransformer.scaling(F)` parser fix needed** |
| 5 | **Lighting in model frame, not camera frame**: vanilla dots lights against post-iso normals; we dot against pre-iso normals. Combined with divergence #1, the per-quadrant signed-luma signature does not converge. | All lighting clusters - **30 entities** |
| 6 | **Layer (feature renderer) overlays missing**: `ItemInHandLayer`, `HumanoidArmorLayer`, `SheepWoolLayer`, `LlamaDecorLayer`, `MushroomCowMushroomLayer`, `SaddleLayer`, `LivingEntityEmissiveLayer`, `CreeperPowerLayer`. | `silhouette_partial` for sheep (wool layer absent), mooshroom (mushroom blocks missing), saddled mules/horses, etc. |
| 7 | **Variant-specific model selection missing for runtime model swap**: Cow / Pig / Chicken / Frog / Wolf renderers swap their model based on `state.variant.modelAndTexture().model()`. We may load only one. | `chicken_warm` vs `chicken_cold` (crest model differs), `cow_warm` vs `cow_cold` (different model variant), etc. |
| 8 | **Tropical fish variant patterns**: 22 patterns driven by `tropicalFishVariant` data component. | `tropical_fish` (iou 0.47 - severe) |
| 9 | **Translucent slime outer-shell**: vanilla uses `RenderTypes.entityTranslucent` (180/255 alpha); we render opaque. | `slime` partial (iou 0.756); known acceptable per current notes |
| 10 | **Family-fit pre-pass missing**: harness measures all variants in a family and unions bounds to size one canvas. Our pipeline sizes each variant's canvas independently. | Cross-variant canvas / scale mismatches |

---

## Two problem groups

Per the user prompt, two **separate** lists. Soft cross-references in brackets.

### Group A: reference-detected (from source comparison)

Found by reading the harness + Java pipeline source. Each one needs a code change
regardless of how diff_panel images look.

| # | Problem | Files to touch | Soft assoc. |
|---|---|---|---|
| A1 | Iso pose mismatch (`(30°, 225°)` vs `(210°, 45°)`) | `EntityRendererJava.composeIsoTransform`, `composeIsoInverse`; add entity-specific iso constants, do not change block path | B-isodiag |
| A2 | Missing vanilla LER submit chain | `EntityRendererJava.render`, `EntityGeometryKitJava` (accept post-iso transform instead of iso-only) | B-coverage, B-isodiag |
| A3 | Lighting computed in wrong frame | `RenderEngine.ENTITY_IN_UI_LIGHT_0/1` + `EntityGeometryKitJava.buildTrianglesWithScale` (compute shade after iso/LER) | B-lighting |
| A4 | Per-renderer setupRotations overrides not replicated (14 renderers) | New: `EntityRendererJava` table + reflective dispatch | B-partial |
| A5 | `MeshTransformer.scaling(F)` not applied in parser | `JavaEntityProceduralLoops.java` (ASM op handler) | B-ghast, B-happy_ghast, B-polar_bear |
| A6 | Hardcoded `RENDERER_SCALE_OVERRIDES` map | Replace with ASM extraction of `scale(state, ps)` from renderer bytecode | B-ghast, B-polar_bear |
| A7 | Variant model selection at submit-time not replicated | `EntityRendererJava` + `EntityModelLoader` (load variant-specific model map) | B-cow, B-chicken, B-wolf |
| A8 | Layer (feature renderer) overlays unimplemented | New: layer system + per-layer impl | B-sheep, B-mooshroom, B-saddle |
| A9 | Family-fit pre-pass missing | New: pre-pass over all variants, share scale + canvas | B-cow_warm vs cow_cold |
| A10 | Fish state-pinning baked into geometry (`isInWater = true` equivalent) | Verify parser walks the `if(isInWater)` branch correctly | B-fish |
| A11 | Guardian spike-extended pose baked into geometry | Either bake the `spikesAnimation = 1` pose statically OR pin in renderer | B-guardian |
| A12 | Pufferfish full-puff sub-model selection | Renderer should resolve `PufferfishRenderer.models[STATE_FULL]` | B-pufferfish |
| A13 | Phantom rest pose (`flapTime = 0`) baked into geometry | Should already be the bind pose - verify | B-phantom |

### Group B: estimated problems (from diff_panel.png analysis)

Found by running `analyze_diff_panels.py` over every entity. Each row of
`scripts/parity_analysis/out/diff_ranking.tsv` is one of these.

Top-level cluster summary (current branch, master ⇄ this branch baseline):

| Cluster | Count | Recommended approach | Likely root cause |
|---|---|---|---|
| `silhouette_partial` | 39 | Inspect diff_panel cell `coverage`. If magenta dominates: missing geometry (layer? variant model?). If cyan dominates: extra geometry. | A2 / A4 / A8 / A7 |
| `lighting_lr_axis` | 22 | Lighting asymmetry on horizontal axis - confirm with `q_tl` vs `q_tr` sign | A1 / A3 |
| `iso_pose_diag_split` | 13 | Canonical iso-pose mismatch - TL+BR vs TR+BL diverge | A1 (master fix) |
| `silhouette_severe` | 6 | Big geometry / scale failure | A5 (ghast/happy_ghast), A8 (cave_spider?), A7 (tropical_fish) |
| `lighting_tb_axis` | 6 | Lighting asymmetry vertical | A1 / A3 |
| `matches_or_minor` | 6 | candidates for ACHIEVED_PARITY | ender_dragon (borderline T3); salmon, tadpole, endermite, magma_cube, mule (need visual check) |
| `high_delta_uncategorized` | 4 | Likely texture mismatch (variant default, biome tint) | A7 / A10 |
| `lighting_global_bias` | 2 | Uniform brightness offset (turtle, strider) | A3 |

The biggest wins:

1. **Fix A5 (`MeshTransformer.scaling`)** - unlocks ghast (4.5×), happy_ghast,
   polar_bear (1.2×). 3 of the top 5 most-broken entities.
2. **Fix A1 + A3 together** (iso pose + lighting frame) - unlocks all 41 lighting
   cluster entries.
3. **Fix A2 (LER chain)** - unlocks geometry-correct pose, prerequisite for proper
   layer rendering A8.
4. **Fix A4 (setupRotations override) for squid + fish first** - smallest entities,
   isolates the override mechanism.

Bottom of the list (not fixable from our side):

- Slime (translucent shell) - acceptable known divergence
- Ender_dragon (animation-driven neck/tail) - deferred per prompt

---

## Fix dispatch (where to start)

| Phase | Fix | Validates against |
|---|---|---|
| 0 | Add per-entity entry point that takes `-PentityIds=a,b,c` and outputs JSON to stdout for automation | All future fixes |
| 1 | Apply A1 (entity-specific iso pose `(210°, 45°)`) + A3 (lighting in camera frame) together. **Cod is the canonical T2 target.** | Cod: iou >= 0.98, all quadrants \|q\| < 4.0 |
| 2 | Apply A5 (`MeshTransformer.scaling`) | Ghast, happy_ghast, polar_bear, strider lose silhouette_severe / size_drift |
| 3 | Apply A2 (LER chain) - rotateY(180), scale(-1,-1,1), translate(0,-1.501,0). Coverage clusters drop. | Zombie, skeleton (silhouette_partial -> T2) |
| 4 | Apply A4 for squid + fish (setupRotations overrides) | Squid `tentacle` extents at canvas bottom |
| 5 | Apply A7 (variant model selection) | chicken_warm vs chicken_cold differ in crest |
| 6 | Apply A8 (layers) - start with `SheepWoolLayer` (simplest) | Sheep grows ~3% silhouette to match harness |
| 7 | Apply A11 / A12 / A13 (state-pinning baked into geometry / model selection) | Guardian, pufferfish, phantom |
| 8 | Apply A9 (family-fit pre-pass) | Cross-variant canvas equality |
| Last | Ender_dragon manual iteration | T3 acceptance |

---

## Per-entity targets

Excerpt - update as fixes land. Full table in
`scripts/parity_analysis/out/diff_ranking.tsv`.

| Entity | Tier | Current cluster | Notes |
|---|---|---|---|
| `minecraft:cod` | T2 | lighting_lr_axis | Canonical lighting / iso-pose test. Simple geometry, single texture. |
| `minecraft:salmon` | T2 | matches_or_minor | Should reach T2 with iso fix alone. |
| `minecraft:tadpole` | T2 | matches_or_minor | Already mean 7.98 - validate it's T2-clean post-iso. |
| `minecraft:magma_cube` | T2 | matches_or_minor | mean 6.08 - T2 candidate. |
| `minecraft:endermite` | T2 | matches_or_minor | mean 20.18 but iou 0.96 - lighting fix should bring it under 10. |
| `minecraft:zombie` | T2 | silhouette_partial | Canonical "LER chain missing" test - exercises base humanoid + every fix. |
| `minecraft:silverfish` | T2 | silhouette_partial | Small cubic body - good chirality test. |
| `minecraft:cow_cold` / `cow_warm` | T2 | lighting_lr_axis | Variant model selection test. |
| `minecraft:chicken_cold` / `chicken_warm` | T2 | lighting_lr_axis / iso_pose_diag_split | Distinct model variant. |
| `minecraft:sheep` | T3 | lighting_lr_axis | Wool layer expected to shift coverage by ~3%. |
| `minecraft:ghast` | T3 | silhouette_severe | Scale 4.5× test for parser fix A5. |
| `minecraft:happy_ghast` | T3 | silhouette_severe | Same as ghast. |
| `minecraft:wither` | T3 | lighting_lr_axis | scale 2x, well-cubed body - lighting test. |
| `minecraft:giant` | T3 | silhouette_partial | scale 6x. |
| `minecraft:warden` | T3 | silhouette_partial | Sparse-opacity plane cubes (tendrils) - exercises bounds walker correctness. |
| `minecraft:guardian` / `:elder_guardian` | T3 | silhouette_partial / silhouette_severe | Spikes-extended baking test (A11). |
| `minecraft:pufferfish` | T3 | iso_pose_diag_split | Sub-model selection test (A12). |
| `minecraft:tropical_fish` | T3 | silhouette_severe | Variant pattern test (A8?). |
| `minecraft:mooshroom` | T3 | lighting_tb_axis | Mushroom block-overlay test (A8). |
| `minecraft:wolf_*` (9) | T2 | mixed | All share one model - one fix unlocks all. |
| `minecraft:slime` | T3 (acceptable divergence) | silhouette_partial | Translucent shell unimplemented; known accepted. |
| `minecraft:ender_dragon` | T3 | matches_or_minor | DEFERRED - animation-driven geometry; iterate manually at end. |

---

## Automation harness

### Per-entity Gradle task (multi-entity capable)

`entityParityVanilla` already accepts `-PentityId=...`. We need to extend the
underlying test main to accept comma-separated `-PentityIds=a,b,c`.

Looking at `TestEntityParityVanilla.main`:

```java
List<String> entityIdFilter = args.length > 0
    ? List.of(args[0].split(","))
    : List.of();
```

It already splits on `,`. The gradle wrapper only forwards a single property. To
batch, pass directly: `-PentityId=minecraft:cod,minecraft:salmon`. **No code change
needed.**

### Faster batch tooling

We're going to add:

1. `entityParityVanillaQuick` - same logic but: skips `diff_panel.png` (cheap), skips
   TSV/JSON aggregation when only one entity is requested, writes
   `cache/visual/entity-parity-vanilla/<entity>/result.json` per run with the row data.
   Goal: 3-5x faster per-iteration on a single entity.
2. Python wrapper `scripts/parity_analysis/iterate_entity.py` - runs the gradle task,
   parses result.json, applies tier thresholds, returns 0/1 exit code suitable for
   shell loops.

Both deferred until we have the first surface-level fix in - we don't want to optimize
the wrong thing.

### Iteration cycle

```bash
# Single entity, all panels:
./gradlew :asset-renderer:entityParityVanilla -PentityId=minecraft:cod

# Batch (rebuild Java pipeline once, run multiple):
./gradlew :asset-renderer:entityParityVanilla -PentityId=minecraft:cod,minecraft:salmon,minecraft:tadpole

# Re-analyse every entity afterwards:
python scripts/parity_analysis/analyze_diff_panels.py

# Check a specific cluster:
grep ^minecraft_cod scripts/parity_analysis/out/diff_ranking.tsv
```

After every successful T2/T3 lock:

1. Add `minecraft:<entity>` to `TestEntityParityVanilla.ACHIEVED_PARITY` set.
2. Commit on the research branch with message `parity(<entity>): <tier> achieved -
   <one-line summary of fix>`.
3. Move on to next entity.

If a fix improves the JSON files (`entity_geometry_java.json`,
`entity_models_java.json`) at the cost of byte-stability vs current contents,
**ship the JSON change**. The byte-stability invariant in the old
`java-pipeline.md` applied to the bedrock pipeline; this work intentionally
breaks Java-side JSON stability if it brings the renders closer to vanilla.

---

## Methodology checklist

For every entity:

1. **Render and analyse** - run `entityParityVanilla -PentityId=<x>`, open
   `diff_panel.png`, run the python analyser, find the row.
2. **Locate the failure** - cluster -> Group A reference fix -> source.
3. **Hypothesize** - what specifically should this change look like?
4. **Test surgically** - one Java change at a time, re-render the target entity
   and 3-4 already-locked entities to check for regression.
5. **Lock in** - update `ACHIEVED_PARITY`, commit, advance.

**Never** apply two fixes in one commit. If a fix happens to clean up 8 entities,
commit once with all 8 in a single ACHIEVED_PARITY update; the *fix* is one change.

---

## Round 5 (attempted, reverted): kit refactor + new entity engine matrix

User authorised the full kit refactor: drop `FLIP_X/Y/Z`, UV face swap, UV permutation,
revert to vanilla CCW winding, use vanilla light constants. The refactor was implemented
across `EntityGeometryKitJava`, `IsometricEngine`, `EntityRendererJava`, `RenderEngine`,
`EulerRotation`. Multiple iterations on winding, chirality, and rotation order.

**Outcome**: every probed entity got significantly worse (cod 41 → 281, salmon 13 → 235,
polar_bear locked T3 broke 36 → 380). Reverted entirely.

### Empirical findings to record

These findings are the high-value output of Round 5 - capture them so the next attempt
doesn't have to relearn:

1. **The kit's `FLIP_Y` is structurally coupled with FIVE other invariants** that all
   need to flip together:
   - The engine's camera matrix (block iso vs entity iso)
   - The UV `case UP -> EntityFace.DOWN` swap
   - The UV `[3,2,1,0]` (UP/DOWN) and `[1,0,3,2]` (sides) permutations
   - The triangle winding `(0, 2, 1)` and `(0, 3, 2)` (CW in model space)
   - The lighting constant frame (post-Y-flip frame)

2. **Bone rotation composition order**: the kit's `pivotCenteredRotation` builds
   `Rz.multiply(Ry).multiply(Rx)` which in our row-vector convention applies the row
   composition `Rz × Ry × Rx`. The cumulative effect on v_row is: apply Z first, then Y,
   then X. This is OPPOSITE to vanilla's `ModelPart.translateAndRotate` which calls
   `mulPose(Z); mulPose(Y); mulPose(X)` in that order - vanilla's column-form composite
   is `T × R_Z × R_Y × R_X` with R_X being applied FIRST to v_col (innermost). The
   correct row-form equivalent is `createRotationX.multiply(createRotationY).multiply(createRotationZ)`
   (X first to v_row). The existing kit got this "right" only because the Y-flip
   reversed the visual outcome and made the wrong order look correct. Removing
   the Y-flip exposes the bug.

3. **Chirality math** (CRITICAL, was tripped up multiple times):
   - PIP's outer `scale(s, s, -s)` is a chirality flip (Z negation), det -1 for orientation
   - The harness's `scale(1, 1, -1)` chirality compensation is another det -1
   - These TWO det-{@code -1} flips cancel: net for orientation = +1 in the harness
   - `scale(-1, -1, 1)` is NOT a reflection - it equals R_Z(180°), det +1 (pure rotation)
   - Combined with `rotateY(180°)` (also det +1), the LER chain composite
     `R_Y(180°) × scale(-1,-1,1) = diag(-1,1,-1) × diag(-1,-1,1) = diag(1,-1,-1) = R_X(180°)`,
     also det +1
   - So harness chain net det = (-1)(-1)(+1)(+1) = +1, chirality preserved
   - Vanilla's normal rendering chain (no PIP, no compensation) is just LER + iso = det +1
   - Our current pipeline (Y-flip kit + block iso engine): det = -1 (kit Y-flip) × +1 (block iso)
     = -1. Combined with projection Y-negation (-1): net = +1. Chirality preserved.

4. **Numerical M_harness_orient_col** (verified, useful reference):
   ```
   scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°) =
   | 0.707  0      -0.707 |
   | -0.354 0.866  -0.354 |
   | -0.612 -0.5   -0.612 |    (det = -1, improper - includes the scale(1,1,-1) flip)
   ```
   Without the scale(1,1,-1) factor:
   ```
   R_X(210°) × R_Y(45°) × R_X(180°) =
   | 0.707  0      -0.707 |
   | -0.354 0.866  -0.354 |
   | 0.612  0.5    0.612  |    (det = +1, pure rotation - matches vanilla natural)
   ```

5. **EntityFace.corners winding**: TL→BL→BR→TR walked in standard math 2D (X right, Y up)
   gives positive signed area, but visually traces CW (top-left → down → right → up).
   Both statements are simultaneously true. The signed area positive means it's CCW per
   the math convention. The visual CW intuition is wrong because angles in standard math
   increase CCW; the corners 135°, 225°, 315° walk in increasing-angle direction = CCW.
   This trapped multiple analysis passes.

6. **For (0, 0, -1) cod head direction in Y-down model**, harness output is at
   screen-x ≈ +0.707 (right side). But vanilla.png has cod HEAD at LEFT (x=0). So
   either cod's head isn't at -Z in Y-down (it might be at +X or +Z), or the harness's
   actual chain produces a different output than my matrix derivation. Resolving this
   requires reading the vanilla `CodModel.createBodyLayer` bytecode to confirm head
   direction; until then, treat the matrix derivation as suspect.

### Recommendations for next attempt

Don't do a big-bang refactor. Instead, validate each change incrementally:

1. **Build a single-cube test fixture** (no bone hierarchy, single textured cube at
   origin). Render through asset-renderer and through harness. Diff pixel-by-pixel. Each
   kit change should drive the diff down, not up.

2. **Read vanilla's `CodModel.createBodyLayer` to know cod's actual orientation in model
   space.** This grounds the matrix verification in real geometry.

3. **Verify each matrix derivation with a 3-point projection test** (origin, +X unit,
   +Y unit, +Z unit) BEFORE wiring it into the engine. Compare to harness's expected
   output for those 4 vertices.

4. **The bone rotation order fix** (item 2 above) should be done independently of the
   iso/kit refactor and tested on an entity WITH bone rotations (e.g. cod's head/tail
   bones if non-zero in bind pose).

## Round 4: foundation refactor - align kit with vanilla source

User authorized changing the Java ASM/tooling/kit to match vanilla source exactly,
even if it breaks the bedrock-paired conventions. Goal: zero UV permutations, zero
UV swapping, no Y-flip reflection in the kit, vanilla CCW triangle winding,
vanilla light constants applied to post-engine-transform normals.

### Verified math (re-derived from scratch this round)

Harness's full screen transformation on a Y-down model vertex (orientation only, after
dropping uniform scale and translates that affect position not orientation):

```
M_harness_col = PIP_outer × chirality × iso × LER
             = diag(1,1,-1) × diag(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)
             = R_X(210°) × R_Y(45°) × R_X(180°)        (the two Z-flips cancel)
```

`R_X(180°)` is the composite of `LivingEntityRenderer.submit`'s `rotateY(180°)` ×
`scale(-1,-1,1)` (both rotations, det+1; product = R_X(180°), det+1). The PIP outer
`scale(s,s,-s)` and harness chirality `scale(1,1,-1)` each contribute a Z-flip;
they cancel. **Net orientation = pure rotation, det = +1**.

Computed numerically:

```
M_harness_col = | 0.707  0      -0.707 |
                | -0.354 0.866  -0.354 |
                | 0.612  0.5     0.612 |
```

Verified per-axis: `(1,0,0)` → `(0.707, -0.354, 0.612)`, `(0,1,0)` → `(0, 0.866, 0.5)`,
`(0,0,1)` → `(-0.707, -0.354, 0.612)`.

### Kit refactor (the next-action plan)

For our pipeline to match the harness output **without** the kit's Y-flip:

1. `EntityGeometryKitJava` - drop `FLIP_X`/`FLIP_Y`/`FLIP_Z`/`FLIP_NORMAL_*`/`TRANSLATE_BY_PIVOT`
   system properties. Hardcode: no axis flip in either positions or normals; bone-pivot
   translation always on.
2. Drop the `case UP -> EntityFace.DOWN` swap. Use `face` directly.
3. Drop the `effUv` permutations `[3,2,1,0]` for UP/DOWN and `[1,0,3,2]` for sides.
   Use `uv` directly.
4. Restore vanilla CCW triangle winding: `(0, 1, 2)` and `(0, 2, 3)` instead of the
   current `(0, 2, 1)` and `(0, 3, 2)`.
5. Simplify `shouldCullBackFaces` to always return `true` (vanilla's natural CCW front
   culling under a pure-rotation transform). Edge cases for two-sided overlays handled
   entity-by-entity.
6. Lighting: dot vanilla raw `INVENTORY_DIFFUSE_LIGHT_0 = normalize(0.2, -1, 1)` and
   `INVENTORY_DIFFUSE_LIGHT_1 = normalize(-0.2, -1, 0)` against post-transform normals.
   Implementation: pre-rotate the lights by `M_harness_col^T` so the kit's pre-transform
   normal dot-product gives the same shade as a post-transform dot would. Computed
   numerically: `L0_kit ≈ (0.775, -0.256, 0.577)`, `L1_kit ≈ (0.208, -0.850, 0.486)`.
   These replace the existing `RenderEngine.ENTITY_IN_UI_LIGHT_0/1` (Y-flipped from
   vanilla, dotted against pre-iso normals) - the bedrock kit's shading will change
   but per user "we don't care about bedrock json/tooling pairing".

### Engine refactor

7. `EulerRotation` - add `STANDARD_ISO_ENTITY = (210°, 45°, 0°)` matching the harness
   raw value.
8. `IsometricEngine` - add `entityStandard(context)` factory using a camera matrix
   built as `createRotationX(180°).multiply(createRotationY(45°)).multiply(createRotationX(210°))`
   in our row-vector convention. Storage in row-major equals `M_harness_col^T`:

   ```
   | 0.707,  -0.354, 0.612 |
   | 0,       0.866, 0.5   |
   | -0.707, -0.354, 0.612 |
   ```

   Verified: `(0,1,0) × M = (0, 0.866, 0.5)` matches harness column 1.
9. `EntityRendererJava` - use `IsometricEngine.entityStandard`, update
   `composeIsoTransform` / `composeIsoInverse` to use the new chain.

### Total pipeline det check

Post-refactor: kit has no Y-flip (det +1), engine has rotation-only matrix (det +1).
Total pipeline det = +1. Vanilla CCW triangle winding → CCW screen → rasterizer's
default front-face culling works correctly.

### Verification per round

After each change category, test on:

- `minecraft:cod` - simplest entity, single-bone effectively, full-body texture; T2 target
- `minecraft:salmon` - similar to cod, validates fish pose lock
- `minecraft:polar_bear` - currently locked T3; ensure no regression
- `minecraft:magma_cube` - simple cubes with multiple segments; tests bone hierarchy
- `minecraft:silverfish` - small cubic body; validates chirality without obvious tells

Lock in `ACHIEVED_PARITY` set as each passes its tier. Move on to feature-renderer
work (layers, variants) only after the foundation is solid.

## Iteration log

### Round 3 (no commit): vanilla-source-identical pose attempt - blocked on kit Y-flip coupling

After confirming via vanilla bytecode that:

- `Lighting$Entry` enum lists 5 lighting setups (`LEVEL`, `ITEMS_FLAT`, `ITEMS_3D`,
  `ENTITY_IN_UI`, `PLAYER_SKIN`), each with distinct light directions
- `GuiEntityRenderer.renderToTexture` uses `Lighting.Entry.ENTITY_IN_UI` + caller-supplied
  rotation via `GuiEntityRenderState.rotation()`
- `InventoryScreen` builds the entity rotation as `rotateZ(π) × rotateX(pitch_mouse)` -
  dynamic per mouse position, NOT a static iso
- The vanilla-reference-harness picks `rotationXYZ(210°, 45°, 0°)` as a deliberate static
  iso for ground-truth reproducibility; vanilla itself has no canonical entity-iso constant

User authorised making asset-renderer "vanilla source code identical" - both pipelines
should operate from the same math regardless of how the harness was constructed.

**Attempt**: derived the correct row-vector matrix that, combined with our kit's Y-flip,
produces the same screen orientation as the harness chain
`scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)` on a Y-down model vertex. The
mathematically-correct Euler decomposition in our convention's `R_X(pitch) × R_Y(yaw) × R_Z(roll)`
column-form is **`(pitch=150°, yaw=-45°, roll=0°)`** - verified per-axis: maps (1,0,0)
to (0.707, -0.354, -0.612), (0,1,0) to (0, 0.866, -0.5), (0,0,1) to (-0.707, -0.354, -0.612)
exactly matching the harness's column-form M.

**Result**: every probed entity (cod, salmon, polar_bear, magma_cube, tadpole, endermite)
worsened by 5-7x mean delta. Polar_bear T3 broke. Reverted.

**Root cause** (the deep finding): the kit has multiple Y-flip-paired assumptions that
are NOT separable from the iso pose choice:

1. **UV mapping for UP/DOWN faces**: kit swaps `case UP -> EntityFace.DOWN` because
   Y-flip puts the maxY-vertex at screen-bottom. With a different rotation that doesn't
   swap up/down on the cube, this swap inverts texture mapping.
2. **UV permutation `[3,2,1,0]` for UP/DOWN, `[1,0,3,2]` for SIDE faces**: kit reverses
   vertex order to counteract the Y-flip's effect on `(NW,SW,SE,NE)`→`(SE,SW,NW,NE)`
   walk order. Changing the rotation breaks this assumption.
3. **Back-face culling assumption**: `shouldCullBackFaces` checks `UP/N/E` are
   "visible-by-default" and `DOWN/S/W` are "hidden-by-default". Under block iso these
   align with the actual visible faces. Under entity iso they may not.
4. **Triangle winding `(0, 2, 1)` / `(0, 3, 2)`**: kit reverses CCW order to compensate
   for the Y-flip's chirality change. Different rotation may not need this reversal.

**Conclusion**: changing only the iso pose constant is NOT enough. To make asset-renderer
operate vanilla-source-identical, the entire kit needs to align with vanilla's convention:
emit Y-down vertices (no Y-flip), use vanilla's UV mapping directly, use vanilla's
triangle winding, then the LER chain composes cleanly into the rasterizer transform with
lighting computed against post-rotation normals. This is a kit refactor, not a
configuration tweak.

**Why polar_bear's MeshTransformer.scaling fix still works**: it's a uniform scale that
commutes with the Y-flip and doesn't interact with UV / winding / culling assumptions.

**Open path forward** (deferred):

1. **Refactor `EntityGeometryKitJava`** to emit Y-down vertices like vanilla. Remove
   `FLIP_Y` (or make it always-false). Reverse the `[3,2,1,0]` and `[1,0,3,2]` UV permutations
   so they walk in vanilla's CCW order. Reverse the `(0,2,1)` triangle winding back to
   `(0,1,2)`. Reverse `shouldCullBackFaces`'s visible-set to vanilla's actual visible-set
   under entity iso.
2. **Add `IsometricEngine.entityStandard`** using `STANDARD_ISO_ENTITY = (210°, 45°, 0°)`
   directly (matching the harness raw value, no Y-flip compensation needed).
3. **Bake the LER chain composite** into the engine's camera matrix - either via direct
   matrix construction or by extending the engine with a separate `ENTITY_CAMERA` constant.
4. **Move lighting computation to post-iso normals**: either compute shade in the
   rasterizer (against the post-transform normal) or pre-rotate the light constants by
   the inverse iso.

This refactor likely needs to be tested entity-by-entity from scratch since the visual
output will change for every entity. The Y-flip removal is the load-bearing decision;
once that's in, the rest should fall into place.

### Round 2: failed A1+A3 attempt (reverted, no commit)

**Hypothesis tested**: replace `IsometricEngine.standard()` with a new
`IsometricEngine.entityStandard()` whose camera matrix is the full harness pose chain
`scale(1,1,-1) × R_X(210°) × R_Y(45°) × R_X(180°)`. Update `composeIsoTransform` to
match for canvas / anchor calculations.

**Result**: catastrophic regression. Every probed entity (cod, salmon, polar_bear,
zombie, wither, magma_cube) got worse, often by 5-10x mean delta. Tried both
composition orders (outer-to-inner and inner-to-outer for row-vector form); both wrong.
Polar_bear T3 broke. Reverted.

**Root-cause hypotheses (untested)** :

1. Our kit applies Y-flip BEFORE the engine's iso rotation, so the engine's iso sees
   already-Y-flipped vertices. The harness has no Y-flip in the kit equivalent - its
   pose chain operates directly on the Y-down model. Substituting the harness chain
   into our engine slot double-mixes with the kit's Y-flip.
2. Lighting is BAKED into triangle.shading at kit-build time. The bake uses
   pre-iso normals. After the rotation change, the lights are still expressed in the
   old pre-iso frame so even if vertex positions were right, the per-face shade is
   wrong.
3. User rotation composition order may also be wrong in the new chain. Original chain
   was `modelRotation × camera` (block iso); I kept that order but with the new
   camera matrix. For row-vector convention with the harness chain, user rotation
   might need to go INSIDE the LER chain (post-`R_X(180)` but pre-iso) rather than
   outside everything.

**Next attempt approach** (deferred to follow-up session):

1. Build a small isolated test fixture - render a single bone with one cube, no
   texture, just colour-by-face - through both pipelines. Compare the rendered pixels
   directly. Iterate on the matrix until cube faces match face-for-face. ONLY THEN
   port the change to the full entity pipeline.
2. Specifically, validate each step of the row-vector matrix decomposition. Our
   `Matrix4f.createRotationX(θ)` is the TRANSPOSE of the standard column-form R_X(θ),
   and `A.multiply(B)` = A×B algebraically, so the FORWARD chain in row form composes
   factors in the order they apply to v_row. The harness column-form chain
   `M_col × v_col = scale × R_X(210) × R_Y(45) × R_X(180) × v_col` is
   equivalent to v_row × (M_col)^T - i.e., M_row = (scale × R_X(210) × R_Y(45) × R_X(180))^T
   = R_X(180)^T × R_Y(45)^T × R_X(210)^T × scale^T. Each rotation transpose negates the
   angle; scale is symmetric. So `M_row = R_X(-180) × R_Y(-45) × R_X(-210) × scale =
   R_X(180) × R_Y(-45) × R_X(-210) × scale` (R_X(180) is self-inverse). **My failed
   attempt did NOT negate the angles.** That's the most likely fix.
3. Lighting needs to be migrated to post-rotation. Simplest approach: keep the kit
   baking shade against pre-iso normals, but rotate the light constants by the
   inverse of the iso transform so the dot product still produces the correct value.

### Round 1 (commit `de26dc9`): MeshTransformer.scaling override

**Fix**: Added entries to `EntityRendererJava.RENDERER_SCALE_OVERRIDES` for entities whose
vanilla model factory wraps its `LayerDefinition` with `MeshTransformer.scaling(F)`. Our
parser strips this wrap (legacy auto-fit assumption); the renderer now applies it as a
per-entity scale multiplier.

**Diff**:

| Entity | Pre-fix mean | Post-fix mean | Tier | Result |
|---|---|---|---|---|
| `minecraft:polar_bear` | 171.86 (silhouette_partial, iou 0.69) | 36.54 (lighting_lr_axis, iou 0.98) | T3 | **locked** in `ACHIEVED_PARITY` |
| `minecraft:ghast` | 431.81 (silhouette_severe, iou 0.22) | 139.89 (iso_pose_diag_split, iou 0.80) | T3 (still failing) | improved 3.1x, blocked on iso pose fix |
| `minecraft:happy_ghast` | tried 4.5f, regressed (iou 0.18) | reverted | T3 (still failing) | needs JSON-baked scaling for entities with non-trivial bone hierarchies |
| `minecraft:elder_guardian` | tried 2.35f, regressed | reverted | T3 (still failing) | same reason as happy_ghast |

**Lesson**: `RENDERER_SCALE_OVERRIDES` multiplies the kit's final vertex output, which
matches `MeshTransformer.scaling(F)` semantics for entities whose model has a flat bone
hierarchy (cubes hang directly off a few siblings with axis-aligned pivots). For models
with nested bones or non-trivial pivots, the post-bone-chain vertex scale leaves the
bone pivots un-scaled, so cubes drift relative to their pivots. Those entities (happy_ghast,
elder_guardian, anything-with-MeshTransformer-and-bones) need the scaling baked into JSON
at parse time, NOT in the renderer.

**Next action**: when iterating on entities still in the parser, scale every bone pivot
+ cube origin + cube size by the layer-definition's `MeshTransformer.scaling` value at
parse time. The current `JavaEntityProceduralLoops.applyGhastTentacles` doc note ("we
emit unscaled values because the renderer's auto-fit-to-bounds normalisation handles
the size at render time") is now stale and should be removed in that fix.

### Cluster trend

| Cluster | Round 0 (master) | Round 1 (after polar_bear fix) | Change |
|---|---|---|---|
| silhouette_partial | 39 | 38 | -1 (polar_bear out) |
| lighting_lr_axis | 22 | 23 | +1 (polar_bear in this cluster, now passes T3 within it) |
| iso_pose_diag_split | 13 | 14 | +1 (ghast moved here from silhouette_severe) |
| silhouette_severe | 6 | 5 | -1 (ghast moved out) |
| lighting_tb_axis | 6 | 6 | 0 |
| matches_or_minor | 6 | 6 | 0 |
| high_delta_uncategorized | 4 | 4 | 0 |
| lighting_global_bias | 2 | 2 | 0 |

---

## Next iteration plan

The largest remaining unlock is **A1+A3 (iso pose + lighting frame)** because it cascades
across 41 entities (`iso_pose_diag_split` + `lighting_lr_axis` + `lighting_tb_axis`).

### What the fix looks like in code

1. `EntityRendererJava` should NOT call `IsometricEngine.standard(context)` for entities;
   instead use a plain `ModelEngine(context, Matrix4f.IDENTITY)` (so the engine's camera
   is identity) and pass the full harness model transform via the matrix overload
   `engine.rasterize(triangles, buffer, perspective, modelTransform)`.

2. The harness model transform on a Y-down model with no user rotation is:
   ```
   M_iso_entity = scale(1, 1, -1) * R_X(210°) * R_Y(45°) * R_X(180°)
   ```
   where `R_X(180°)` is the composite `rotateY(180°) * scale(-1, -1, 1)` from the LER
   submit chain. **Note: `R_X(180°)` is a rotation, but our kit's Y-flip is a reflection;
   they're not interchangeable.**

3. The lights need to be expressed in the kit's pre-engine-transform frame. Given vanilla's
   raw constants in **camera frame** (Y-down):
   ```
   L0_v = normalize(0.2, -1, 1)
   L1_v = normalize(-0.2, -1, 0)
   ```
   the kit-frame versions are:
   ```
   L_kit = Y_flip * M_iso_entity^-1 * L_v
   ```
   Numerically (computed in research session):
   ```
   ENTITY_IN_UI_LIGHT_0_kit ≈ normalize(-0.082, 0.956, -0.280)
   ENTITY_IN_UI_LIGHT_1_kit ≈ (recompute analogously)
   ```

4. **CAREFUL**: this requires removing the Y-flip from the kit (or accounting for it
   explicitly in both the model transform and the light orientation). Test on cod
   first - cod has a flat bone hierarchy + simple texture, so any pose/lighting
   mismatch shows immediately.

### Smaller surgical fixes (parallel to A1/A3)

- **JSON-baked MeshTransformer.scaling** for happy_ghast, elder_guardian, ghast tentacles,
  and any other model that uses `MeshTransformer.scaling(F)`. Walk the LayerDefinition
  bytecode for the `.transform(MeshTransformer.scaling(F))` call site, then apply F to
  every bone pivot + cube origin + cube size in the emitted JSON. Removes the
  `RENDERER_SCALE_OVERRIDES` workaround entirely.
- **Wolf model** (9 entities currently): all 9 wolf_* variants share the same model so
  one fix (iso pose) cascades to all of them. Wolves are currently at iou=0.840 across
  the board.

---

## Harness mixin re-evaluation (user instruction)

User permission: mixins that alter math / lighting / geometry in the harness should be
re-evaluated. Pose & animation halting mixins stay (we don't implement animation yet).

Audit of every `vanilla-reference-harness/src/client/java/lib/minecraft/refharness/mixin/`:

| Mixin | Category | User-allowed? | Recommendation |
|---|---|---|---|
| `SkipSetupAnimMixin` | animation halt | **KEEP** (per user) | Stays - we don't animate yet |
| `FreezeAnimationStateMixin` | animation halt + fish upright | **KEEP** (per user) | Stays - same reason. Note: `isInWater = true` for `AbstractFish` flips the fish pose. This is technically a "math" mixin (changes `setupRotations`'s behaviour) but the user grouped it with pose halting; flag for later review. |
| `BeeStateMixin` | pose (bee at-rest) | **KEEP** (per user) | Stays - pose halt |
| `GuardianStateMixin` | pose (`spikesAnimation=1`, `tailAnimation=0`, `lookAt=null`) | partial | `spikesAnimation=1` is a CHOICE not halt - flag for re-eval; the other two are halts |
| `PhantomStateMixin` | pose (`flapTime=0`) | **KEEP** | Pose halt |
| `PufferfishStateMixin` | model-selection (`puffState=STATE_FULL`) | partial | Forces a different sub-model - flag, this is a render-choice not a halt |
| `EnderDragonModelMixin` | animation halt | **KEEP** | Pose halt |
| `WitherBossModelMixin` | animation halt (redundant) | **KEEP** | Pose halt |
| `FlipFaceShadingMixin` | **LIGHTING** alteration | **RE-EVAL** | Likely obsolete - see below |
| `HideSkyMixin` | cosmetic (transparent bg) | **KEEP** | Cosmetic, not math |
| `HideCloudsMixin` | cosmetic | **KEEP** | Cosmetic |
| `HideHandMixin` | cosmetic | **KEEP** | Cosmetic |
| `HeadlessWindowMixin` | runtime (GLFW visibility) | **KEEP** | Runtime, not math |

### FlipFaceShadingMixin: candidate for removal

The mixin swaps N/S vs E/W in `ClientLevel.cardinalLighting()` to match asset-renderer's
flipped `BlockFace.lighting()` table. The mixin docstring says:

> Vanilla 26.1's `CardinalLighting.DEFAULT` for the overworld is `(down=0.5, up=1.0,
> N=S=0.8, W=E=0.6)` - that's the world-rendering shade where N/S faces are brighter
> than E/W faces. Vanilla's inventory pipeline (`Lighting.ITEMS_3D`) produces the
> opposite axis brightness because it uses two directional lights offset in X, which
> after the standard `[30, 225, 0]` GUI rotation makes E/W (the model's left/right)
> brighter than N/S (the model's front/back). asset-renderer's `BlockFace` reproduces
> that inventory output: `N=S=0.6, W=E=0.8`. Swapping the level's `cardinalLighting()`
> return value gives the harness output the same axis brightness as asset-renderer.

But the harness's current block path goes through `ItemFrameRenderer` (PIP) which calls
`lighting.setupFor(Lighting.Entry.ITEMS_3D)` directly. That setup doesn't read from
`level.cardinalLighting()` - it uses the transformed `DIFFUSE_LIGHT_0/1` constants
computed inside `Lighting.<init>` (verified via bytecode: the constructor computes the
matrix `scaling(1,-1,1) × rotateYXZ(1.0821, 3.2376, 0) × rotateYXZ(-0.3927, 2.3562, 0)`
and transforms the DIFFUSE lights through it, storing the result keyed on `ITEMS_3D`).

The mixin affected `BlockModelLighter`, `FluidRenderer`, `PistonHeadRenderer`,
`RenderSectionRegion` - paths that the harness's NEW PIP-based BlockSweeper does not
hit. **The mixin is therefore likely dead in the new harness architecture.**

**Action**: confirm by removing the mixin from `vanilla-reference-harness/.../mixins.json`
(or commenting out the `FlipFaceShadingMixin` line), re-running `renderVanillaReferences`,
and diffing the output PNGs against the pre-remove version. If pixel-identical (or
nearly so), delete the mixin entirely. Tracked as task #10.

### Asset-renderer ITEMS_3D math: confirmed correct

After the FlipFaceShadingMixin removal, the harness output is what vanilla-inventory
produces. Asset-renderer's `BlockFace.lighting()` table `(down=0.5, up=1.0, N=S=0.6,
W=E=0.8)` was designed to match that vanilla-inventory output. The bucketed approach
is an approximation (vanilla uses continuous dual-light Lambertian), but for cube-aligned
faces the result equals the continuous formula exactly. Non-axis-aligned faces in
blocks (stair bevels, anvil corners) will see small divergences - tracked as a long-tail
follow-up, not in scope for entity parity.

### Asset-renderer ENTITY_IN_UI math: needs the kit refactor

Vanilla's `ENTITY_IN_UI` uses `INVENTORY_DIFFUSE_LIGHT_0 ≈ normalize(0.2, -1, 1)` and
`INVENTORY_DIFFUSE_LIGHT_1 ≈ normalize(-0.2, -1, 0)` (Y-down camera frame) untransformed,
dotted against post-everything-transform normals using formula
`shade = min(1, (max(0, dot(L0,n)) + max(0, dot(L1,n))) * 0.6 + 0.4)`.

Our current `RenderEngine.computeEntityInUiLighting` uses Y-flipped lights `(0.2, 1, 1)`
and `(-0.2, 1, 0)` dotted against pre-iso normals (the kit's Y-flipped frame). For
matching vanilla, the lights need to be expressed in our kit's post-Y-flip-pre-rotation
frame. The "math change" tied to the kit-refactor above (round 3 finding) needs to land
together with this; once the kit no longer Y-flips, the lights should use vanilla's
exact values (no flip).

## Open questions (answer as we go)

- Q1: Does the iso pose `(210°, 45°)` need to be applied differently for block
  overlays (mooshroom mushrooms) vs entity geometry? The harness uses one pose for
  everything inside the EntityFrameRenderer; check whether block-overlay
  rasterisation through our pipeline shares the same iso transform.
- Q2: For the LER chain `translate(0, -1.501, 0)` - the magic constant is half the
  default entity height (-3 / 2). Some entities may not want this (squid? shulker?).
  Verify it's safe to apply unconditionally before `scale(-1,-1,1)`.
- Q3: Family-fit pre-pass requires loading ALL variants of an entity before any
  render. Easy if we do it once at pipeline boot; harder if we render per-entity
  on-demand. Check current pipeline lifecycle.
