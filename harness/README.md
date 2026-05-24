# vanilla-reference-harness

Headless Fabric mod for **Minecraft 26.1.2** that drives the actual vanilla client to render every block (1142) and every living entity (102 including variants) into transparent-background reference PNGs at a locked iso pose. The output PNGs are the byte-stable ground truth that sibling [asset-renderer]'s parity tests diff its Java entity-rendering pipeline against.

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
# Full sweep (~1m 25s warm)
./gradlew :asset-renderer:renderVanillaReferences

# Filter to a subset for iteration
./gradlew :asset-renderer:renderVanillaReferences \
  -PrefharnessTargets=minecraft:cow,minecraft:zombie,minecraft:diamond_block
```

Or directly from the harness:

```bash
./gradlew runRenderReferences
./gradlew runRenderReferences -PrefharnessTargets=minecraft:cow
```

Either path launches MC 26.1.2 with a hidden GLFW window, programmatically creates a flat normal-difficulty world, runs a family-fit pre-pass to size each entity-family canvas, renders 1142 blocks + 102 entity variants into transparent PNGs through vanilla's GUI inventory pipeline, then exits. The entity bounds pre-pass takes ~250 ms.

---

## Output

PNGs are RGBA with the subject opaque on a fully transparent (`α = 0`) background.

| Source                                       | Path                                                                                       |
| -------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `runRenderReferences` direct                 | `vanilla-reference-harness/build/refharness-output/{blocks,entities}/<ns>__<id>.png`       |
| `:asset-renderer:renderVanillaReferences`    | `asset-renderer/cache/asset-renderer/vanilla/26.1/references/{blocks,entities}/<ns>__<id>.png` |

### Block canvas

Square `IMAGE_SIZE × IMAGE_SIZE` (default 512). Every block renders to the same size since all blocks are 1m³.

### Entity canvas (family-locked)

Every entity in a family (cow + cow_cold + cow_warm + cow_temperate + mooshroom; chicken + chicken_cold + chicken_warm + chicken_temperate; etc.) shares one canvas size + scale + anchor, computed in a pre-pass from the union of all family members' screen bounds × `PIXELS_PER_BLOCK` (default 256).

> [!NOTE]
> Shared geometry is byte-identical across variants. The cow body region in `cow_cold.png` is the same pixels as the cow body region in `mooshroom.png`. Cross-family canvas sizes vary - cow's family canvas is bigger than chicken's, which is bigger than silverfish's.

A hard cap (`MAX_CANVAS_SIZE`, default 1024) shrinks oversized canvases (ender_dragon, full-scale wither, giant×6) by uniformly scaling down both canvas dimensions + scale.

---

## Iso pose

Quaternion locked at `rotationXYZ(210°, 45°, 0°)` in `EntityFrameRenderer.ISO_ROTATION`. Empirically derived via a 24-step yaw sweep + 576-frame pitch×roll sweep over a cow with chirality fix.

> [!NOTE]
> The 210/45 angles compensate for two transforms baked downstream: `LivingEntityRenderer.setupRotations`'s built-in `Y(180 - bodyRot)`, plus an `Rx(180)`-equivalent factor introduced by the chirality `scale(1, 1, -1)` fix.

Equivalent to a camera positioned SE of the subject looking NW with `yaw = 135°, pitch = 30°` - matching asset-renderer's `IsometricEngine.STANDARD_ISO_BLOCK` JOML quaternion.

---

## Render pipeline

Both phases use vanilla's GUI inventory render paths (no in-world capture). Geometry is submitted to an offscreen `RGBA8 + DEPTH32` texture pair, then read back via `copyTextureToBuffer` → `NativeImage` → PNG.

### Block phase

`BlockSweeper` + `ItemFrameRenderer`:

1. Iterate `BuiltInRegistries.BLOCK`, skip blocks without items (`block.asItem() == Items.AIR`).
2. Build `ItemStack`, resolve via `ItemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, level, null, 0)`.
3. Submit through `EntityRenderDispatcher` + `FeatureRenderDispatcher`.
4. Lighting: `Lighting.Entry.ITEMS_3D`. Vanilla applies the model's `display.gui` transform `[30, 225, 0]` internally - the same transform inventory slots use.

### Entity phase

`EntitySweeper` + `EntityFrameRenderer`:

1. **Pre-pass** measures every (entity, variant) target's screen bounds, groups by family root via `FAMILY_OVERRIDES` (currently just `MOOSHROOM → COW`), and computes one `FamilyFit(canvasW, canvasH, scale, anchorX, anchorY)` per family.
2. **Render pass** iterates `BuiltInRegistries.ENTITY_TYPE`, filters `MobCategory != MISC` with `MISC_ALLOWLIST` exception (currently `armor_stand`), builds variants via `EntityType.loadEntityRecursive(type, nbt={"variant":"<ns>:<id>"}, level, LOAD, EntityProcessor.NOP)`, extracts render state via `EntityRenderer.createRenderState`, submits through the same offscreen-texture path as blocks.
3. Lighting: `Lighting.Entry.ENTITY_IN_UI` (dual-light Lambertian). The entity is GC-eligible after submit; no world spawn / freeze / camera setup needed.

### Chirality fix

> [!WARNING]
> Without this, models render with back-faces visible (lights inside, textures wound CW).

`poseStack.scale(1, 1, -1)` immediately before `mulPose(rotation)`. The transform chain has an odd number of reflections by default (PIP `scale(s, s, -s)` + vanilla `scale(-1, -1, 1)` in `setupRotations`); the explicit Z-negate flips the cumulative determinant back to positive.

### Variant rendering

`VARIANT_REGISTRIES` maps `EntityType.{COW,PIG,CHICKEN,FROG,WOLF}` to `Registries.X_VARIANT`. For those types the sweeper iterates the registry and renders one PNG per variant. Filenames: `<ns>__<entity>_<variant>.png` (e.g. `minecraft__cow_warm.png`, `minecraft__wolf_ashen.png`).

> [!IMPORTANT]
> `CowRenderer`, `PigRenderer`, `ChickenRenderer` keep a `Map<ModelType, AdultAndBabyModelPair<Model>>` and mutate `this.model` in `submit()` to the variant-specific instance. The bounds walker resolves the variant model up front via `tryResolveVariantModel` so bounds reflect the actual rendered geometry. Without this, cow_warm's bounds would be measured against the default `CowModel` (no horns) while render uses `WarmCowModel` (with horns).

### Bounds calculation

`walkVisibleExtents` walks the entity model's cube hierarchy through the same transform chain as render, contributing per-opaque-texel positions:

1. Sample every texel inside each polygon's UV box.
2. Compute the tight opaque-pixel bbox.
3. Bilinearly interpolate the bbox's 4 corners through the polygon's vertex positions to get 3D positions.
4. Feed those 3D positions to the bounds accumulator.

`walkLayerExtents` reflectively walks every `RenderLayer`'s `EntityModel` fields (sheep wool, glow eyes, armor) and skips `EnergySwirlLayer` subclasses (`CreeperPowerLayer`, etc.) when their `isPowered(state)` returns false.

---

## Configuration

### Project properties

Use `-PrefharnessXxx` on the Gradle command line.

| Property                  | Default                  | Purpose                                                                                            |
| ------------------------- | ------------------------ | -------------------------------------------------------------------------------------------------- |
| `refharnessTargets`       | _(empty)_                | Comma-separated `<ns>:<id>` filter; empty means all                                                |
| `refharnessOutputDir`     | `build/refharness-output` | Output root; `blocks/` + `entities/` are created under it                                          |
| `refharnessPitchRollSweep`| `false`                  | Diagnostic: render the first filtered target 576× as `pNNN_rNNN.png` over a pitch×roll grid       |

### System properties

Set automatically by the Loom run config; override with `-Drefharness.xxx=` for one-off tweaks.

| Property                     | Default | Purpose                                                                                                              |
| ---------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------- |
| `refharness.headless`        | `true`  | Hides the GLFW window via mixin; gates every refharness mixin so non-harness consumers of the jar get vanilla behaviour |
| `refharness.size`            | `512`   | Block canvas square edge (pixels)                                                                                    |
| `refharness.pixelsPerBlock`  | `256`   | Entity texel resolution; family canvases sized to `bound × this`                                                     |
| `refharness.maxCanvasSize`   | `1024`  | Cap on entity canvas longer side; entities exceeding shrink uniformly                                                |

---

## Versions

| Component        | Version                                |
| ---------------- | -------------------------------------- |
| Minecraft        | 26.1.2 (Mojang official mappings)      |
| Fabric Loader    | 0.19.2                                 |
| Fabric Loom      | 1.16-SNAPSHOT                          |
| Fabric API       | 0.147.0+26.1.2                         |
| Java             | 21+ (Loom requirement)                 |
| Gradle           | 9.4.1                                  |

---

## Architecture

```
src/
├── main/resources/
│   ├── fabric.mod.json
│   └── seed-world/.gitkeep
└── client/
    ├── java/lib/minecraft/refharness/
    │   ├── HarnessConfig.java          # System-property config
    │   ├── RefHarnessClient.java       # ClientModInitializer; tick lifecycle, warmup, stop
    │   ├── WorldBootstrap.java         # TitleScreen → WorldOpenFlows.createFreshLevel(...)
    │   ├── RefHarnessRenderer.java     # Lifecycle only: builds + drives the sweepers
    │   ├── BlockSweeper.java           # Iterates BLOCK registry; renders via ItemFrameRenderer
    │   ├── ItemFrameRenderer.java      # PIP item render → offscreen texture → PNG
    │   ├── EntitySweeper.java          # Variant + family-fit pre-pass + family-locked render pass
    │   ├── EntityFrameRenderer.java    # PIP entity render with bounds walker + chirality + family fit
    │   ├── IsoRenderer.java            # Legacy main-framebuffer reader (retained for diagnostics)
    │   └── mixin/
    │       ├── HeadlessWindowMixin.java         # GLFW_VISIBLE=false
    │       ├── HideHandMixin.java               # ItemInHandRenderer cancel
    │       ├── HideSkyMixin.java                # All SkyRenderer draw passes cancel
    │       ├── HideCloudsMixin.java             # CloudRenderer.render cancel
    │       ├── FlipFaceShadingMixin.java        # ClientLevel.cardinalLighting() N/S ↔ W/E swap
    │       ├── FreezeAnimationStateMixin.java   # Zero per-tick state on every LivingEntityRenderState
    │       ├── SuppressShakingMixin.java        # Cancel isShaking bodyRot wobble in setupRotations
    │       ├── SkipSetupAnimMixin.java          # Generic setupAnim bypass; bind pose for all models
    │       ├── BeeStateMixin.java               # Force isOnGround=true (rest pose, no wing flap)
    │       ├── EnderDragonModelMixin.java       # Cancel setupAnim → authored rest pose (redundant under SkipSetupAnimMixin)
    │       ├── WitherBossModelMixin.java        # Cancel setupAnim → no chest-bob (redundant under SkipSetupAnimMixin)
    │       ├── GuardianStateMixin.java          # Pin spikesAnimation, tailAnimation, lookAt
    │       ├── PhantomStateMixin.java           # Pin flapTime=0
    │       ├── PufferfishStateMixin.java        # Pin puffState=STATE_FULL
    │       ├── ZombieVillagerStateMixin.java    # Pin villagerData to PLAINS/NONE/1 (default)
    │       ├── DonkeyModelMixin.java            # Hide left_chest/right_chest bones (equipment-driven)
    │       └── LlamaModelMixin.java             # Hide left_chest/right_chest bones (equipment-driven)
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
| `SkipSetupAnimMixin`           | every `EntityModel.setupAnim` callsite from `LivingEntityRenderer.submit` | Skips `setupAnim` so the produced PNG uses the authored `createBodyLayer` bind pose. Most `setupAnim` implementations rewrite pivots / rotations even at `ageInTicks = 0`; bind pose is the only fair comparison target until asset-renderer animates. |

### Per-renderer state pins

> [!IMPORTANT]
> Subclass `extractRenderState` typically calls `super.extractRenderState(...)` first, then writes subclass-specific fields. Mixins on the base `LivingEntityRenderer` fire BEFORE the subclass writes those fields, so values set there get overwritten. All subclass-specific overrides need their own mixin classes targeting the subclass renderer.

| Mixin                       | Target                                | Effect                                                                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BeeStateMixin`             | `BeeRenderer.extractRenderState`      | Forces `state.isOnGround = true` - skips wing flap math + `bobUpAndDown`'s `0.179 rad` forward-tilt, giving the at-rest pose with flat wings + level body                                                                                                       |
| `EnderDragonModelMixin`     | `EnderDragonModel.setupAnim`          | Cancels `setupAnim` entirely - leaves every part at its authored `PartPose` (flat wings, straight neck/tail, closed jaw). Now redundant under `SkipSetupAnimMixin`; idempotent and kept as model-specific documentation                                         |
| `WitherBossModelMixin`      | `WitherBossModel.setupAnim`           | Cancels at-rest chest-bob. Now redundant under `SkipSetupAnimMixin`; kept as model-specific documentation                                                                                                                                                       |
| `GuardianStateMixin`        | `GuardianRenderer.extractRenderState` | Pins `spikesAnimation = 1.0` (extended-spikes silhouette), `tailAnimation = 0` (defeats per-instance `random.nextFloat()` constructor seed), `lookAtPosition = lookDirection = null` (skips eye-direction block that tracks the player camera)                  |
| `PhantomStateMixin`         | `PhantomRenderer.extractRenderState`  | Pins `flapTime = 0` - vanilla seeds `flapTime = entity.getUniqueFlapTickOffset() + ageInTicks`, where the offset is per-instance pseudo-random. Pinning to 0 gives the canonical flat-wing glide pose                                                          |
| `PufferfishStateMixin`      | `PufferfishRenderer.extractRenderState` | Pins `state.puffState = Pufferfish.STATE_FULL` (= 2) - iconic adult silhouette. Vanilla's transient pufferfish defaults to `STATE_SMALL = 0` (deflated)                                                                                                          |
| `ZombieVillagerStateMixin`  | `ZombieVillagerRenderer.extractRenderState` | Pins `state.villagerData` to `Villager.createDefaultVillagerData()` (type `PLAINS`, profession `NONE`, level 1). Vanilla `ZombieVillager.initializeVillagerData` randomly assigns profession via the registry + spawn-biome-driven type                  |

### Equipment-driven bone visibility

| Mixin                       | Target                                | Effect                                                                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DonkeyModelMixin`          | `DonkeyModel` ctor + `createBodyLayer` | Hides `left_chest` / `right_chest` bones (8×8×3 cubes that hang off the body at ±6 X). `DonkeyModel.setupAnim` writes `visible = state.hasChest`; for a freshly-loaded harness donkey/mule this is always false, so the bones are vestigial and inflate bounds without contributing pixels |
| `LlamaModelMixin`           | `LlamaModel` ctor + `createBodyLayer`  | Same as donkey - hides `right_chest` / `left_chest` for every harness-baked llama (covers llama + trader_llama which share the model)                                                                                                                            |

> [!CAUTION]
> **Delete the freeze + skip mixins once asset-renderer adds animation support.** `SkipSetupAnimMixin` is the broadest one - removing it reverts every entity to vanilla's frame-0 animation pose. `EnderDragonModelMixin` and `WitherBossModelMixin` become deletable at the same time (they're already redundant). The state pins (`BeeStateMixin`, `GuardianStateMixin`, `PhantomStateMixin`, `PufferfishStateMixin`, `ZombieVillagerStateMixin`) stay until asset-renderer can reproduce per-renderer state randomization.

---

## Family map

Cross-`EntityType` family overrides via `EntitySweeper.FAMILY_OVERRIDES`. Variants of the same `EntityType` automatically join one family.

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
./gradlew :asset-renderer:renderVanillaReferences \
  -PrefharnessTargets=minecraft:cow \
  -PrefharnessPitchRollSweep=true
```

Renders the first filtered target 24×24 = 576 times - every combination of pitch (0°-345° in 15° steps) and roll (0°-345° in 15° steps), holding yaw at the iso-locked value. Output: `entities-pitch-roll-sweep/<safeName>_pNNN_rNNN.png`. Used to find the right pitch+roll combination when neither axis alone gives the desired screen orientation (Euler-angle gimbal interaction).

### Reset world

```bash
./gradlew :vanilla-reference-harness:resetRefharnessWorld
```

Removes `run/saves/refharness_world/` so the next render starts from a fresh world. Useful if save-corruption from a hard JVM exit causes startup issues.

---

## License

All-Rights-Reserved (sibling of [asset-renderer]; not for redistribution).

[asset-renderer]: https://github.com/minecraft-library/asset-renderer
