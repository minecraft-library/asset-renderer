# vanilla-reference-harness

Headless Fabric mod for **Minecraft 26.1.2** that drives the actual vanilla client to render every block, every living entity (including variants and, in a boot of its own, every entity posed at each tick of one schedule), every non-block item, and the animated enchantment glint into transparent-background reference PNGs at a locked iso pose. This is a directory of [asset-renderer], with its own Gradle build and its own wrapper; the output PNGs are the byte-stable ground truth that repository's parity tests diff its Java rendering pipeline against. Blocks render as true 3D geometry through vanilla's block-model and block-entity pipelines (never as flat inventory icons); non-block items render as GUI inventory icons.

> [!IMPORTANT]
> This is a single-purpose dev tool. The renders it produces are checked in to asset-renderer's cache as the parity baseline. **Do not delete or modify those PNGs by hand** - re-run the harness instead.

---

## Table of contents

- [Quick start](#quick-start)
- [Output](#output)
- [Iso pose](#iso-pose)
- [Render pipeline](#render-pipeline)
- [Configuration](#configuration)
- [Versions](#versions)
- [Architecture](#architecture)
- [Mixins](#mixins)
- [Family map](#family-map)
- [Pipeline gotchas](#pipeline-gotchas)
- [Diagnostics](#diagnostics)

---

## Quick start

> [!TIP]
> Prefer running through asset-renderer - the output lands directly in asset-renderer's cache where the parity tests look for it.

```bash
# Full sweep: blocks + items + entities (~5 min warm), run from the asset-renderer root
./gradlew renderVanillaReferences

# Filter to a subset for iteration
./gradlew renderVanillaReferences \
  -PrefharnessTargets=minecraft:cow,minecraft:zombie,minecraft:diamond_block

# Animated-glint references only (fast, decoupled from the full sweep)
./gradlew renderVanillaGlintReferences
```

Or directly from the harness:

```bash
./gradlew runRenderReferences
./gradlew runRenderReferences -PrefharnessTargets=minecraft:cow
./gradlew runRenderReferences -PrefharnessGlintOnly=true
```

Either path launches MC 26.1.2 with a hidden GLFW window, programmatically creates a flat normal-difficulty world, pins noon + freezes the daylight cycle, runs a family-fit pre-pass to size each entity-family canvas, then renders every block (as true 3D geometry), every non-block item (as a GUI inventory icon), and every entity variant into transparent PNGs before exiting. The entity bounds pre-pass takes ~250 ms. The glint sweep is a separate decoupled run (`GLINT_ONLY`), never part of the full sweep.

---

## Output

PNGs are RGBA with the subject opaque on a fully transparent (`α = 0`) background.

| Source                                       | Path                                                                                       |
| -------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `runRenderReferences` direct                 | `vanilla-reference-harness/build/refharness-output/{blocks,entities,items}/<ns>__<id>.png`  |
| `renderVanillaReferences` from asset-renderer | `asset-renderer/cache/asset-renderer/vanilla/26.1/references/{blocks,entities,items}/<ns>__<id>.png` |
| Glint (per-frame, decoupled run)             | `.../references/glint/<ns>__<id>/frame_NNN.png` + `glint/atlas_uv.json`                     |
| Animated entities (per-frame, own boot)      | `.../references/idle/<ns>__<id>/frame_NNN.png`                                                |

### Animated entity canvas

One strip per entity, `FRAME_COUNT` frames `TICKS_PER_FRAME` ticks apart, at the appearance the still sweep names that entity by. The canvas is family-locked exactly as the still one is and is measured over one union more: every adult coat of the type, at every tick of the schedule. Wider than what is drawn, deliberately - one coat is rendered because a pose is the same under every skin, while a coat is a different set of opaque texels and the fit is alpha-tight, so measuring only the drawn coat frames a cow 386 px wide where asset-renderer frames it at 442 and the difference is reported as pixels.

> [!NOTE]
> No bone is pinned here. `EntityRoster.bonePins` forces the flags vanilla writes from `setupAnim` precisely because the still sweep does not run it; this run does, so the real writes land and a pin would be a second authority for the same bone.

### Block canvas

Square `IMAGE_SIZE × IMAGE_SIZE` (default 512). Every block renders to the same size since all blocks are 1m³.

### Entity canvas (family-locked)

Every entity in a family (cow + cow_cold + cow_warm + cow_temperate + mooshroom; chicken + chicken_cold + chicken_warm + chicken_temperate; etc.) shares one canvas size + scale + anchor, computed in a pre-pass from the union of all family members' screen bounds × `PIXELS_PER_BLOCK` (default 256).

> [!NOTE]
> Shared geometry is byte-identical across variants. The cow body region in `cow_cold.png` is the same pixels as the cow body region in `mooshroom.png`. Cross-family canvas sizes vary - cow's family canvas is bigger than chicken's, which is bigger than silverfish's.

A hard cap (`MAX_CANVAS_SIZE`, default 1024) shrinks oversized canvases (ender_dragon, full-scale wither, giant×6) by uniformly scaling down both canvas dimensions + scale.

### Item canvas

Same square `IMAGE_SIZE × IMAGE_SIZE` (default 512) as blocks. Non-block items render as flat GUI inventory icons; the larger-than-16×16 canvas lets the inventory display transform breathe without clipping.

### Glint output

Each glint subject is a directory of `FRAME_COUNT` (30) per-frame PNGs (`frame_000.png` … `frame_029.png`) stepping the glint phase through the schedule shared with asset-renderer's `TestGlintParityVanilla`. `glint/atlas_uv.json` records each foil item's items-atlas sprite-UV rect so the asset side samples the glint through vanilla's exact `UV0`.

---

## Iso pose

Quaternion locked at `rotationXYZ(210°, 45°, 0°)` in `api.HarnessPose.ISO`. Empirically derived via a 24-step yaw sweep + 576-frame pitch×roll sweep over a cow with chirality fix.

> [!NOTE]
> The 210/45 angles compensate for two transforms baked downstream: `LivingEntityRenderer.setupRotations`'s built-in `Y(180 - bodyRot)`, plus an `Rx(180)`-equivalent factor introduced by the chirality `scale(1, 1, -1)` fix.

Equivalent to a camera positioned SE of the subject looking NW with `yaw = 135°, pitch = 30°` - matching asset-renderer's `IsometricEngine.STANDARD_ISO_BLOCK` JOML quaternion.

---

## Render pipeline

Every sweep renders through PIP (picture-in-picture): geometry is submitted to an offscreen `RGBA8 + DEPTH32` texture pair, then read back via `copyTextureToBuffer` → `NativeImage` → PNG. No in-world capture, camera, or player placement is involved - the world only has to exist so `EntityType.create` and the block-entity dispatcher have a `Level`. Readback is async, so each sweep renders exactly one subject per client tick - entity variants included.

### Block phase

`BlockSweep` renders every block as **true 3D geometry at the iso `display.gui` pose** - never a flat inventory icon. It iterates `BuiltInRegistries.BLOCK`, skips technical blocks with no item (`block.asItem() == Items.AIR`), and routes each remaining block by type:

- **Plain blocks** → `BlockFrameRenderer`. The block's `BlockStateModel` is submitted directly via `SubmitNodeStorage.submitBlockModel` at pose `R_XYZ(30°, 225°, 0°)` + scale `0.625` (vanilla's `block/block.json` `display.gui`) under `Lighting.Entry.ITEMS_3D`. This bypasses the item-model dispatch, so blocks whose item model parents `item/generated` (rails, vines, ladders, lily_pad, seagrass, sculk_vein, doors, hanging signs) render as actual 3D geometry instead of the 2D billboard the inventory icon would show.
- **`EntityBlock` blocks with a registered `BlockEntityRenderer`** (chest, shulker_box, banner, sign, bed, skull, bell, beacon, decorated_pot, copper_golem_statue, ...) → `BlockEntityFrameRenderer`. A transient, never-ticked `BlockEntity` is constructed via `EntityBlock.newBlockEntity`, wired to `client.level`, and dispatched through the vanilla BE renderer for its real in-world geometry. See [Block-entity icon composition](#block-entity-icon-composition).
- **`EntityBlock` blocks without a renderer** (barrel, hopper, brewing_stand, furnace, chiseled_bookshelf, calibrated_sculk_sensor, ...) → fall back to the plain `BlockFrameRenderer` path, **not** the item model (whose icon is a flat `item/generated` sprite or a divergent inventory model).

Determinism + in-world-appearance fixes on this path: `FirstVariantRandomSource` (pins weighted variant lists to `variants[0]`, matching asset-renderer's `BlockStateLoader.parseVariants`), a noon-pinned + frozen daylight cycle (stable BE lightmap), translucent-vs-cutout sheet selection (`FLAG_TRANSLUCENT` → `translucentBlockSheet`), inventory-tint resolution to vanilla's no-world colormap default (with the `sugar_cane` grass-tint exception), and the `tripwire_hook` cardinal-snap shading fix. The texture-animation and `shade:false` fixes are in the [Mixins](#mixins) block-render family.

#### Block-entity icon composition

Most block-entities render raw - skull, chest, shulker_box, conduit, decorated_pot and beacon already sit on the unit block, so the block-centred iso pose reproduces asset-renderer's icon directly. Five families instead compose an inventory icon (canonical facing + recenter-and-fit on a vanilla-extent-walker bbox, max extent `1.4` block units):

| Family               | Composition                                                                                        |
| -------------------- | -------------------------------------------------------------------------------------------------- |
| bed                  | Merge both halves (default state is the foot only) at canonical NORTH facing, 90° icon rotation.   |
| banner / wall_banner | Replace per-facing yaw with a canonical 180°; flat flag (`BannerFlagModelMixin`).                  |
| skull                | Wall heads re-pointed to the ground transform (rotation 0); ender-dragon head recenter/fit + jaw closed. |
| sign                 | 180° yaw about block-centre so the face turns toward the camera (standing / wall / 3 hanging forms). |
| copper_golem_statue  | Entity-convention (y-down/mirrored) model flipped 180° about Z to stand upright, then fit.         |

### Item phase

`ItemSweep` walks `BuiltInRegistries.ITEM`, **skips `BlockItem`s** (already covered by the block phase), and renders each remaining item through `ItemFrameRenderer` - vanilla's GUI inventory-icon pipeline (`ItemDisplayContext.GUI` + `ITEMS_FLAT` / `ITEMS_3D` lighting + `FeatureRenderDispatcher` to an offscreen `RGBA8` texture) - to `items/<ns>__<id>.png`.

### Entity phase

`EntitySweep` + `EntityFrameRenderer`:

1. **Pre-pass** measures every (entity, variant) target's screen bounds, groups by family root via `FAMILY_OVERRIDES` (currently just `MOOSHROOM → COW`), and computes one `FamilyFit(canvasW, canvasH, scale, anchorX, anchorY)` per family.
2. **Render pass** iterates `BuiltInRegistries.ENTITY_TYPE`, filters `MobCategory != MISC` with `MISC_ALLOWLIST` exception (currently `armor_stand`), builds variants via `EntityType.loadEntityRecursive(type, nbt={"variant":"<ns>:<id>"}, level, LOAD, EntityProcessor.NOP)`, extracts render state via `EntityRenderer.createRenderState`, submits through the same offscreen-texture path as blocks.
3. Lighting: `Lighting.Entry.ENTITY_IN_UI` (dual-light Lambertian). The entity is GC-eligible after submit; no world spawn / freeze / camera setup needed.

### Chirality fix

> [!WARNING]
> Without this, models render with back-faces visible (lights inside, textures wound CW).

`poseStack.scale(1, 1, -1)` immediately before `mulPose(rotation)`. The transform chain has an odd number of reflections by default (PIP `scale(s, s, -s)` + vanilla `scale(-1, -1, 1)` in `setupRotations`); the explicit Z-negate flips the cumulative determinant back to positive.

### Variant rendering

`EntityRoster.VARIANT_REGISTRIES` maps `EntityType.{COW,PIG,CHICKEN,FROG,WOLF,CAT,ZOMBIE_NAUTILUS}` to `Registries.X_VARIANT`. For those types the sweep iterates the registry and renders one PNG per variant. Filenames: `<ns>__<entity>_<variant>.png` (e.g. `minecraft__cow_warm.png`, `minecraft__wolf_ashen.png`).

> [!IMPORTANT]
> `CowRenderer`, `PigRenderer`, `ChickenRenderer` keep a `Map<ModelType, AdultAndBabyModelPair<Model>>` and mutate `this.model` in `submit()` to the variant-specific instance. The bounds walker resolves the variant model up front via `tryResolveVariantModel` so bounds reflect the actual rendered geometry. Without this, cow_warm's bounds would be measured against the default `CowModel` (no horns) while render uses `WarmCowModel` (with horns).

### Bounds calculation

`walkVisibleExtents` walks the entity model's cube hierarchy through the same transform chain as render, contributing per-opaque-texel positions:

1. Sample every texel inside each polygon's UV box.
2. Compute the tight opaque-pixel bbox.
3. Bilinearly interpolate the bbox's 4 corners through the polygon's vertex positions to get 3D positions.
4. Feed those 3D positions to the bounds accumulator.

`walkLayerExtents` reflectively walks every `RenderLayer`'s `EntityModel` fields (sheep wool, glow eyes, armor) and skips `EnergySwirlLayer` subclasses (`CreeperPowerLayer`, etc.) when their `isPowered(state)` returns false.

### Glint phase

`GlintSweep` renders the animated enchantment glint as a deterministic frame sequence. It steps `GlintClock.overrideT` through a fixed schedule (`t_N = N × 1000 ms`, 30 frames spanning the V-loop exactly once); `GlintTexturingMixin` substitutes that time for vanilla's wall-clock glint derivation, so every frame's phase matches asset-renderer's `GlintKit.applyGlintAtTimes`. Two subject kinds:

- **7 always-foil GUI items** (`enchanted_book`, `written_book`, `enchanted_golden_apple`, `experience_bottle`, `nether_star`, `debug_stick`, `end_crystal`) - the item glint, via `ItemFrameRenderer`.
- **4 worn leather-armor diagnostics** - the distinct armor glint, an `armor_stand` wearing one glint-forced leather piece rendered through `EntityFrameRenderer`. Byte-parity is out of scope (different pose/model); this exists so the armor-glint animation can be eyeballed side-by-side.

The sweep also dumps `glint/atlas_uv.json` (each foil item's items-atlas sprite-UV rect). It runs **only** under `GLINT_ONLY` (`-PrefharnessGlintOnly=true` / `renderVanillaGlintReferences`) and is never part of the full block/item/entity sweep.

> [!IMPORTANT]
> `GlintSweep.FRAME_COUNT` (30) and `STEP_MILLIS` (1000) **must match asset-renderer's `TestGlintParityVanilla`** or the frames misalign.

---

## Configuration

### Project properties

Use `-PrefharnessXxx` on the Gradle command line.

| Property                  | Default                  | Purpose                                                                                            |
| ------------------------- | ------------------------ | -------------------------------------------------------------------------------------------------- |
| `refharnessTargets`       | _(empty)_                | Comma-separated `<ns>:<id>` filter; empty means all                                                |
| `refharnessOutputDir`     | `build/refharness-output` | Output root; `blocks/`, `entities/`, `items/`, `glint/` are created under it                       |
| `refharnessGlintOnly`     | `false`                  | Render only the animated-glint references (the 7 foil items + 4 armor diagnostics); skip the full sweep |
| `refharnessAnimated`      | `false`                  | Render only `idle/` - one strip per entity with vanilla's own `setupAnim` running. Turns both freezes off for the whole boot, so no other sweep can share it |
| `refharnessPitchRollSweep`| `false`                  | Diagnostic: render the first filtered target 576× as `pNNN_rNNN.png` over a pitch×roll grid       |

### System properties

Set automatically by the Loom run config; override with `-Drefharness.xxx=` for one-off tweaks.

| Property                     | Default | Purpose                                                                                                              |
| ---------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------- |
| `refharness.headless`        | `true`  | Hides the GLFW window via mixin; gates every refharness mixin so non-harness consumers of the jar get vanilla behaviour |
| `refharness.size`            | `512`   | Block canvas square edge (pixels)                                                                                    |
| `refharness.glintOnly`       | `false` | Run only `GlintSweep` (animated-glint references), skipping the block / item / entity sweeps                       |
| `refharness.animated`        | `false` | Run only `EntityAnimationSweep`, and let `setupAnim` through while `ageInTicks` follows the frame's tick             |

**Entity texel resolution and the canvas cap are frozen constants, not properties.** `HarnessConfig.PIXELS_PER_BLOCK` (`256`) and `HarnessConfig.MAX_CANVAS_SIZE` (`1024`) used to read `-Drefharness.pixelsPerBlock` / `-Drefharness.maxCanvasSize`, and asset-renderer's `EntityOptions` read the same two property names on its own JVM. No task on either side forwarded either, so the documented override was inert - and had it worked on one side only, every entity reference would have been measured at one scale and compared against renders taken at another, which reports framing rather than the render. A literal on each side makes that unrepresentable. Changing either means editing both constants in one commit.

---

## Versions

| Component        | Version                                |
| ---------------- | -------------------------------------- |
| Minecraft        | 26.1.2 (Mojang official mappings)      |
| Fabric Loader    | 0.19.2                                 |
| Fabric Loom      | 1.16-SNAPSHOT                          |
| Fabric API       | 0.147.0+26.1.2                         |
| Java             | 25 (Loom toolchain; `JAVA_25` mixins)  |
| Gradle           | 9.4.1                                  |

---

## Architecture

```
src/
├── main/resources/
│   └── fabric.mod.json
└── client/
    ├── java/lib/minecraft/refharness/
    │   ├── HarnessConfig.java              # System-property config
    │   ├── HarnessMode.java                # FULL / GLINT / PLAYERS / ARMOR / PITCH_ROLL -> its sweeps
    │   ├── RefHarnessClient.java           # ClientModInitializer; tick lifecycle, mute, warmup, stop
    │   ├── WorldBootstrap.java             # TitleScreen → WorldOpenFlows.createFreshLevel(...)
    │   ├── RefHarnessRenderer.java         # Lifecycle: builds + drives the sweep runners; noon-pin
    │   ├── GlintClock.java                 # Harness-controlled deterministic glint time
    │   ├── api/                            # The contracts and the shared value types
    │   │   ├── Sweep.java                  # What a sweep is: enumerate / key / canvas / render + hooks
    │   │   ├── SweepRunner.java            # Work index, tally, latch, one-subject-per-tick pacing
    │   │   ├── SweepContext.java           # Per-tick render handles + output root + target filter
    │   │   ├── FrameRenderer.java          # What a sweep calls to draw one subject
    │   │   ├── RefKey.java                 # A reference's output name, as data
    │   │   ├── Canvas.java                 # Canvas dimensions + the optional caller-measured fit
    │   │   ├── Bounds.java                 # Screen-space extents; union is monotone
    │   │   ├── TargetFilter.java           # The parsed -PrefharnessTargets allowlist
    │   │   └── HarnessPose.java            # The shared iso rotation
    │   ├── pip/                            # The offscreen draw envelope, written once
    │   │   ├── PipTarget.java              # Textures, clear, ortho, submit, async read-back → PNG
    │   │   ├── PipScope.java               # Per-frame handles + FULL_BRIGHT_LIGHT + the GUI pose
    │   │   └── PipDraw.java                # The subject-specific pose-and-submit callback
    │   ├── frame/                          # The five frame renderers and their helpers
    │   │   ├── BlockFrameRenderer.java     # PIP 3D block-model render (submitBlockModel) → PNG
    │   │   ├── BlockEntityFrameRenderer.java # PIP BE-dispatch render + inventory-icon composition
    │   │   ├── ItemFrameRenderer.java      # PIP GUI item-icon render → PNG
    │   │   ├── EntityFrameRenderer.java    # PIP entity render: fit, chirality, pose, submit
    │   │   ├── EntityBoundsWalker.java     # The reflective silhouette walk that sizes a subject
    │   │   ├── AnimatedEntityFrameRenderer.java # Arms the tick, unions bounds across the schedule
    │   │   ├── PlayerFrameRenderer.java    # PIP player-model render (no Entity; bakes the layer)
    │   │   ├── BlockGuiTransform.java      # Resolves a block's authored display.gui transform
    │   │   └── FirstVariantRandomSource.java # nextInt→0: pin weighted block variants to variants[0]
    │   ├── sweep/                          # One file per subject kind
    │   │   ├── BlockSweep.java             # BLOCK registry → BE renderer, falling back to plain 3D
    │   │   ├── ItemSweep.java              # ITEM registry (non-BlockItem) → GUI icon
    │   │   ├── EntitySweep.java            # Variant expansion + family canvas pre-pass
    │   │   ├── EntityAnimationSweep.java   # One strip per entity, posed at each tick of one schedule
    │   │   ├── PlayerSweep.java            # FULL + SKULL scopes
    │   │   ├── GlintSweep.java             # Animated-glint frame sequences + atlas-UV dump
    │   │   ├── ArmorSweep.java             # Armored adult/baby diagnostics
    │   │   ├── PitchRollSweep.java         # Diagnostic pitch × roll pose sweep (outside the tree)
    │   │   ├── EntityRoster.java           # MISC allowlist, variant registries, family overrides
    │   │   └── EntitySubjects.java         # zeroRotations, shared by the entity-bearing sweeps
    │   └── mixin/
    │       ├── HeadlessWindowMixin.java         # GLFW_VISIBLE=false
    │       ├── HideHandMixin.java               # ItemInHandRenderer cancel
    │       ├── HideSkyMixin.java                # All SkyRenderer draw passes cancel
    │       ├── HideCloudsMixin.java             # CloudRenderer.render cancel
    │       ├── FlipFaceShadingMixin.java        # ClientLevel.cardinalLighting() N/S ↔ W/E swap
    │       ├── FreezeAnimationStateMixin.java   # Zero per-tick state on every LivingEntityRenderState
    │       ├── SuppressShakingMixin.java        # Cancel isShaking bodyRot wobble in setupRotations
    │       ├── SkipSetupAnimMixin.java          # Generic setupAnim bypass; bind pose for all models
    │       ├── EnderDragonModelMixin.java       # Cancel setupAnim → authored rest pose (redundant under SkipSetupAnimMixin)
    │       ├── WitherBossModelMixin.java        # Cancel setupAnim → no chest-bob (redundant under SkipSetupAnimMixin)
    │       ├── GuardianStateMixin.java          # Pin tailAnimation, lookAt
    │       ├── WitchNoseMixin.java               # Pin entityId=0 (nose-bob frequency is per-instance)
    │       ├── PhantomStateMixin.java           # Pin flapTime=0
    │       ├── PufferfishStateMixin.java        # Pin puffState=STATE_FULL
    │       ├── ZombieVillagerStateMixin.java    # Pin villagerData to PLAINS/NONE/1 (default)
    │       ├── DonkeyModelMixin.java            # Hide left_chest/right_chest bones (equipment-driven)
    │       ├── LlamaModelMixin.java             # Hide left_chest/right_chest bones (equipment-driven)
    │       ├── CamelSaddleReinsMixin.java       # Hide the camel saddle's reins (isRidden-driven)
    │       ├── EquineSaddleLinesMixin.java      # Hide the equine saddle's lines (isRidden-driven)
    │       ├── FreezeSpriteAnimationMixin.java  # Pin animated texture sprites to frame 0
    │       ├── ShadeFalseFullBrightMixin.java   # shade:false faces render full-bright
    │       ├── BannerFlagModelMixin.java        # Flatten banner cloth (cancel wave)
    │       └── GlintTexturingMixin.java         # Deterministic glint time from GlintClock
    └── resources/refharness.client.mixins.json
```

---

## Mixins

> [!NOTE]
> All mixins are gated on `Boolean.getBoolean("refharness.headless")` so non-harness consumers of the jar get vanilla behaviour. The Loom run config sets the property to `true`.

### Suppression mixins

| Mixin                  | Target                                                                                                            | Effect                                                            |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `HeadlessWindowMixin`  | `Window.<init>`                                                                                                   | Sets `GLFW_VISIBLE = false` so no window appears                  |
| `HideHandMixin`        | `ItemInHandRenderer.renderHandsWithItems`                                                                          | Cancels hand/item rendering                                       |
| `HideSkyMixin`         | `SkyRenderer.{renderSkyDisc, renderDarkDisc, renderSunMoonAndStars, renderSunriseAndSunset, renderEndSky, renderEndFlash}` | Cancels each sky pass                                       |
| `HideCloudsMixin`      | `CloudRenderer.render`                                                                                            | Cancels cloud quads                                               |

### Lighting alignment

| Mixin                    | Target                              | Effect                                                                                                                                        |
| ------------------------ | ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `FlipFaceShadingMixin`   | `ClientLevel.cardinalLighting()`    | Returns `(0.5, 1.0, 0.6, 0.6, 0.8, 0.8)` instead of vanilla's `(0.5, 1.0, 0.8, 0.8, 0.6, 0.6)` - matches asset-renderer's `BlockFace.lighting()` |

### Animation freeze

| Mixin                          | Target                                       | Effect                                                                                                                                                                                                                          |
| ------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FreezeAnimationStateMixin`    | `LivingEntityRenderer.extractRenderState`    | Zeroes `ageInTicks`, `walkAnimationPos`, `walkAnimationSpeed`, `deathTime`, `ticksSinceKineticHitFeedback`, `wornHeadAnimationPos`. Forces `state.isInWater = true` for `AbstractFish` so salmon/cod/tropical_fish render upright. |
| `SuppressShakingMixin`         | `LivingEntityRenderer.setupRotations`        | Cancels the `isShaking(state)` bodyRot wobble (`cos(floor(ageInTicks)*3.25) * π * 0.4` degrees), landing on the bind pose instead of an animation yaw offset.                                                                  |
| `SkipSetupAnimMixin`           | every `EntityModel.setupAnim` callsite from `LivingEntityRenderer.submit` | Skips `setupAnim` so the produced PNG uses the authored `createBodyLayer` bind pose. Most `setupAnim` implementations rewrite pivots / rotations even at `ageInTicks = 0`, and the bind pose is what the asset-renderer's own default draws. Lifted for the whole run by `refharness.animated`, which is what makes `idle/` a second reference set rather than a replacement for `entities/`. |
| `WitchNoseMixin`               | `WitchRenderer.extractRenderState`    | Pins `entityId = 0`. Vanilla bobs the nose at `0.01 * (entityId % 10)` so a crowd does not move in lockstep, and an id counts every entity the client has built - so the frequency is a function of run history. Inert while `setupAnim` is frozen; without it `idle/` does not reproduce past frame 0 |

### Per-renderer state pins

> [!IMPORTANT]
> Subclass `extractRenderState` typically calls `super.extractRenderState(...)` first, then writes subclass-specific fields. Mixins on the base `LivingEntityRenderer` fire BEFORE the subclass writes those fields, so values set there get overwritten. All subclass-specific overrides need their own mixin classes targeting the subclass renderer.

| Mixin                       | Target                                | Effect                                                                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `EnderDragonModelMixin`     | `EnderDragonModel.setupAnim`          | Cancels `setupAnim` entirely - leaves every part at its authored `PartPose` (flat wings, straight neck/tail, closed jaw). Now redundant under `SkipSetupAnimMixin`; idempotent and kept as model-specific documentation                                         |
| `WitherBossModelMixin`      | `WitherBossModel.setupAnim`           | Cancels at-rest chest-bob. Now redundant under `SkipSetupAnimMixin`; kept as model-specific documentation                                                                                                                                                       |
| `GuardianStateMixin`        | `GuardianRenderer.extractRenderState` | Pins `tailAnimation = 0` (defeats per-instance `random.nextFloat()` constructor seed) and `lookAtPosition = lookDirection = null` (skips the eye-direction block that tracks the player camera). Leaves `spikesAnimation` at the `0` a never-ticked guardian reports, which is what the asset side rests it at |
| `PhantomStateMixin`         | `PhantomRenderer.extractRenderState`  | Pins `flapTime = 0` - vanilla seeds `flapTime = entity.getUniqueFlapTickOffset() + ageInTicks`, where the offset is per-instance pseudo-random. Pinning to 0 gives the canonical flat-wing glide pose                                                          |
| `PufferfishStateMixin`      | `PufferfishRenderer.extractRenderState` | Pins `state.puffState = Pufferfish.STATE_FULL` (= 2) - iconic adult silhouette. Vanilla's transient pufferfish defaults to `STATE_SMALL = 0` (deflated)                                                                                                          |
| `ZombieVillagerStateMixin`  | `ZombieVillagerRenderer.extractRenderState` | Pins `state.villagerData` to `Villager.createDefaultVillagerData()` (type `PLAINS`, profession `NONE`, level 1). Vanilla `ZombieVillager.initializeVillagerData` randomly assigns profession via the registry + spawn-biome-driven type                  |

### Equipment-driven bone visibility

| Mixin                       | Target                                | Effect                                                                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DonkeyModelMixin`          | `DonkeyModel` ctor + `createBodyLayer` | Hides `left_chest` / `right_chest` bones (8×8×3 cubes that hang off the body at ±6 X). `DonkeyModel.setupAnim` writes `visible = state.hasChest`; for a freshly-loaded harness donkey/mule this is always false, so the bones are vestigial and inflate bounds without contributing pixels |
| `LlamaModelMixin`           | `LlamaModel` ctor + `createBodyLayer`  | Same as donkey - hides `right_chest` / `left_chest` for every harness-baked llama (covers llama + trader_llama which share the model)                                                                                                                            |
| `CamelSaddleReinsMixin`     | `CamelSaddleModel` ctor                | Hides `reins`, the strap a rider holds. `CamelSaddleModel.setupAnim` writes `reins.visible = state.isRidden` and nothing rides a transient camel, so a normally-rendered saddle draws its bridle and no reins                                                    |
| `EquineSaddleLinesMixin`    | `EquineSaddleModel` ctor               | Same for `left_saddle_line` / `right_saddle_line`, which `setupAnim` writes from `isRidden` over the `ridingParts` array holding both. One class serves five subjects - donkey and mule bake their saddle through `DonkeyModel.createSaddleLayer` and are still handed this class to pose it |

### Block & texture render fixes

These make the block / item / glint sweeps match the in-world appearance and run deterministically. Unlike the entity freezes they target the block-model, sprite-animation, and glint paths.

| Mixin                          | Target                                      | Effect                                                                                                                                                                                                 |
| ------------------------------ | ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FreezeSpriteAnimationMixin`   | `SpriteContents$AnimationState.tick`        | Pins `frame = subFrame = 0` so animated faces (magma, sea_lantern, prismarine, campfire, sculk, lanterns) render frame 0 with no interpolation bleed - the texture-animation analog of `SkipSetupAnimMixin`. asset-renderer samples frame 0 statically. |
| `ShadeFalseFullBrightMixin`    | `VertexConsumer.putBakedQuad`               | Redirects `BakedQuad.direction()` → `Direction.UP` for `shade:false` quads so they saturate the `ITEMS_3D` diffuse to `1.0`. Matches the in-world `getShade(dir, false) = 1.0` for ladders, cobweb, and all cross/crop/vine planes (otherwise darkened to the `0.40` ambient floor). |
| `BannerFlagModelMixin`         | `BannerFlagModel.setupAnim`                 | Cancels the cloth wave (`flag.xRot = (-0.0125 + 0.01·cos(2π·phase))·π`, never zero + game-time-seeded) so the flag is flat (`xRot = 0`), matching asset-renderer's flat flag box. Delete if asset-renderer ever models the wave. |
| `GlintTexturingMixin`          | `TextureTransform.setupGlintTexturing`      | Substitutes `GlintClock.overrideT` for vanilla's wall-clock glint time (`Util.getMillis()·glintSpeed·8`) when `overrideT ≥ 0`, rebuilding the exact scroll matrix so each captured frame lands at a deterministic, asset-aligned glint phase. |

> [!CAUTION]
> **The `setupAnim` freezes are gated rather than deleted.** `SkipSetupAnimMixin` is the broadest one, and `entities/` is ground truth for exactly what it produces - the authored bind pose, which is what asset-renderer's own default draws; removing it moves 88 of the 90 modelled entities. So it and the two per-model cancels beside it (`EnderDragonModelMixin`, whose renderer is not a `LivingEntityRenderer` and which is therefore load-bearing, and `WitherBossModelMixin`) read `refharness.animated`, and the posed reference set lives beside the frozen one under `idle/`. The state pins (`GuardianStateMixin`, `PhantomStateMixin`, `PufferfishStateMixin`, `ZombieVillagerStateMixin`, `WitchNoseMixin`) are not freezes and stay: each takes an input that varies with the client's own history out of the render, and the animated set needs them more, because most of them are read through no code at all while the freeze holds. **A pin that is not one of those does not survive that reading**: a field only `setupAnim` reads is inert on all seven frozen sub-trees, so a value chosen there for how it looks is a state the asset side has no way to know about and reads as its own defect on the eighth. Two were - the guardian's extended spikes and the bee's landed wings - and both now stand where vanilla's own never-ticked entity puts them. On the block side, `FreezeSpriteAnimationMixin` (texture animation) and `BannerFlagModelMixin` (cloth wave) delete when asset-renderer animates those; `GlintTexturingMixin` and `ShadeFalseFullBrightMixin` are permanent (they enforce determinism / in-world parity, not a freeze).

---

## Family map

Cross-`EntityType` family overrides via `EntityRoster.FAMILY_OVERRIDES`. Variants of the same `EntityType` automatically join one family.

| Family root | Members                                                                                                                          |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `cow`       | cow_cold, cow_temperate, cow_warm, **mooshroom** (via FAMILY_OVERRIDES)                                                          |
| `pig`       | pig_cold, pig_temperate, pig_warm                                                                                                |
| `chicken`   | chicken_cold, chicken_temperate, chicken_warm                                                                                    |
| `frog`      | frog_cold, frog_temperate, frog_warm                                                                                             |
| `wolf`      | wolf_ashen, wolf_black, wolf_chestnut, wolf_pale, wolf_rusty, wolf_snowy, wolf_spotted, wolf_striped, wolf_woods                 |

Every other entity is a family of 1 (its own canvas dimensions).

---

## Pipeline gotchas

> [!IMPORTANT]
> These are non-obvious vanilla behaviours that the bounds walker has to compensate for. If a future MC version bump breaks rendering, start here.

### `setupRotations` is virtual

14 entity renderers override it (Squid, Shulker, Pufferfish, TropicalFish, Cat, Fox, Cod, Drowned, Salmon, Panda, Phantom, IronGolem, ArmorStand, ...). Squid's adds `translate(0, -1.2, 0)` after the standard scale flip; without invoking the override during bounds walking, the bounds walker silently uses the base `mulPose(Y, 180-bodyRot)` and the rendered squid drops 1.2m below the canvas.

The bounds walker dispatches via reflection to the most-derived `setupRotations` to capture all per-renderer pose translations.

### `EnderDragonRenderer` is non-`LivingEntityRenderer`

It extends `EntityRenderer` directly. Its `submit()` adds its own pre-model transforms - `translate(0, 0, 1)` + `scale(-1, -1, 1)` chirality + `translate(0, -1.501, 0)` - that the standard `LivingEntityRenderer` chain doesn't cover. The bounds walker has a dedicated `else if` branch for it.

### `EnergySwirlLayer` conditional rendering

`CreeperPowerLayer` (and other `EnergySwirlLayer` subclasses) gate on `isPowered(state)` - the `submit()` early-returns when the swirl shouldn't render. The bounds walker skips these layers via reflective `isPowered` invocation, otherwise the charge-effect mesh (which is visibly larger than the body) inflates bounds on unpowered creepers.

### Variant-specific model selection

`CowRenderer` / `PigRenderer` / `ChickenRenderer` keep `Map<ModelType, AdultAndBabyModelPair<Model>>` and mutate `this.model` in `submit()` based on `state.variant.modelAndTexture().model()`. The bounds walker runs *before* any submit, so `getModel()` returns the constructor's default - which for a variant entity has different geometry (cow_warm uses `WarmCowModel` with smaller horns; the default `CowModel` has none). The bounds walker's `tryResolveVariantModel` replicates the submit-time selection logic reflectively.

### Plane-cube degenerate polygons

A 16×16×0 "plane cube" (warden tendrils, etc.) has 2 visible faces and 4 zero-area edge polygons whose UVs collapse to a line. The edges render no pixels but their 4 vertex positions span the full cube extent - if treated as regular polygons they balloon bounds. The bounds walker skips polygons with `uMin == uMax || vMin == vMax`.

---

## Diagnostics

### Pitch-roll sweep

```bash
./gradlew renderVanillaReferences \
  -PrefharnessTargets=minecraft:cow \
  -PrefharnessPitchRollSweep=true
```

Renders the first filtered target 24×24 = 576 times - every combination of pitch (0°-345° in 15° steps) and roll (0°-345° in 15° steps), holding yaw at the iso-locked value. Output: `entities-pitch-roll-sweep/<safeName>_pNNN_rNNN.png`. Used to find the right pitch+roll combination when neither axis alone gives the desired screen orientation (Euler-angle gimbal interaction).

### Glint-only run

```bash
./gradlew renderVanillaGlintReferences \
  -PrefharnessTargets=minecraft:nether_star
```

Runs only `GlintSweep`, skipping the full block/item/entity sweep, so the animated-glint references iterate fast and decoupled. Each subject writes `glint/<ns>__<id>/frame_NNN.png` (30 frames) plus a one-shot `glint/atlas_uv.json`. The `-PrefharnessTargets` allowlist scopes to a single foil subject.

### Reset world

```bash
./gradlew resetRefharnessWorld
```

Run from this directory. Removes asset-renderer's `cache/run/saves/refharness_world/` - Loom is pointed at that run directory rather than at `harness/run/` - so the next render starts from a fresh world. Useful if save-corruption from a hard JVM exit causes startup issues.

---

## License

Apache-2.0, the same as [asset-renderer]. This tree is a directory of that repository rather than a
vendored third party, so the repository's own `LICENSE.md` governs it and there is no second licence
file here. The one thing it carries that is not ours is the Minecraft client it drives, which Loom
resolves at build time and which nothing here redistributes; `COPYRIGHT.md` at the repository root
records that carve-out and the Fabric dependencies this build carries.

[asset-renderer]: ..
