# vanilla-reference-harness

Single-purpose headless Fabric mod that drives the real MC client to produce the byte-stable ground-truth PNGs sibling [asset-renderer]'s parity tests diff against. Four sweeps: **blocks** (true 3D, not item icons), **entities**, **non-block items** (GUI icons), and **animated glint**. **README is the user-facing reference** (architecture, mixin catalog, family map, configuration); this file is the session-refresh / contributor's quick reference.

## Build / run
- JDK 25 (Loom toolchain; `JAVA_25` mixin compat), Gradle 9.4.1, Fabric Loom 1.16-SNAPSHOT, Fabric Loader 0.19.2, Fabric API 0.147.0+26.1.2, MC 26.1.2.
- Full sweep (blocks + items + entities, ~5 min warm): `./gradlew runRenderReferences [-PrefharnessTargets=ns:id,...]` from this dir, or `./gradlew :asset-renderer:renderVanillaReferences` from asset-renderer (output → asset-renderer's cache).
- Glint-only (fast, decoupled): `./gradlew runRenderReferences -PrefharnessGlintOnly=true`, or `./gradlew :asset-renderer:renderVanillaGlintReferences`.
- Output dirs under the output root: `blocks/`, `entities/`, `items/`, `glint/` (+ `glint/atlas_uv.json`).
- World corruption from a hard JVM exit: `./gradlew resetRefharnessWorld`.

## Sweep architecture

`RefHarnessRenderer` drives one sweeper step per client tick after a 60-tick warmup. Four sweepers: `BlockSweeper`, `ItemSweeper`, `EntitySweeper`, and `GlintSweeper` (the last runs alone in `GLINT_ONLY` mode and skips the other three; the full sweep never includes glint). Every sweeper renders through PIP (offscreen `RGBA8 + DEPTH32` texture → `copyTextureToBuffer` → `NativeImage` → PNG); readback is async, so each sweeper renders exactly one subject per tick and deliberately leaks its GPU textures at end-of-sweep rather than racing an in-flight callback.

## Mixin convention

Every mixin gates on `Boolean.getBoolean("refharness.headless")`. Loom run config sets the property to `true`; non-harness consumers of the jar get vanilla behaviour when it's unset.

Pattern:
```java
@Inject(method = "...", at = @At("HEAD"), cancellable = true)
private void onX(..., CallbackInfo ci) {
    if (!Boolean.getBoolean("refharness.headless")) return;
    // ... headless-only effect ...
    ci.cancel();
}
```

Mixin classes live in `src/client/java/lib/minecraft/refharness/mixin/`, registered in `src/client/resources/refharness.client.mixins.json`. Four families:

1. **Suppression** — hide window / sky / clouds / hand (`HeadlessWindowMixin`, `HideSkyMixin`, `HideCloudsMixin`, `HideHandMixin`).
2. **Lighting alignment** — flip vanilla's `cardinalLighting()` N/S↔W/E swap to match asset-renderer's `BlockFace.lighting()` (`FlipFaceShadingMixin`).
3. **Entity state / pose freezes** — pin transient entity state so renders are reproducible (see catalog below).
4. **Block-render fixes** — make block / glint renders match the in-world appearance + run deterministically (`FreezeSpriteAnimationMixin`, `ShadeFalseFullBrightMixin`, `BannerFlagModelMixin`, `GlintTexturingMixin`).

## Block / item / glint pipeline

**Blocks are a 3D block-parity sweep, never an item-icon sweep.** `BlockSweeper` routes each block by type:

- **Plain blocks** → `BlockFrameRenderer`: submits the `BlockStateModel` directly via `SubmitNodeStorage.submitBlockModel` at the vanilla `display.gui` pose (`R_XYZ(30°,225°,0°)` + scale `0.625`) under `ITEMS_3D` lighting. Bypasses item-model dispatch, so `item/generated` blocks (rails, vines, ladders, lily_pad, seagrass, sculk_vein, doors, hanging signs) render as real 3D geometry, not the flat 2D billboard the inventory icon shows.
- **`EntityBlock` + registered BE renderer** → `BlockEntityFrameRenderer`: dispatches the vanilla `BlockEntityRenderer` (signs/beds/banners/heads/shulker_boxes/bells/decorated_pots/...) against a transient, never-ticked `BlockEntity` wired to `client.level`, capturing the real in-world geometry.
- **`EntityBlock` without a renderer** (barrel, hopper, brewing_stand, furnace, chiseled_bookshelf, ...) → falls back to the plain `BlockFrameRenderer` path, **not** the item model.

`ItemSweeper` renders only non-`BlockItem`s (BlockItems are already covered by `BlockSweeper`) through `ItemFrameRenderer` — the vanilla GUI inventory-icon path — to `items/`.

### Block determinism + in-world-appearance fixes
- **`FirstVariantRandomSource`** (`nextInt → 0`) pins weighted variant lists (bedrock/stone/netherrack rotations, rotated-cube tiles) to `variants[0]`, matching asset-renderer's `BlockStateLoader.parseVariants`. A live `RandomSource` baked a random rotation into asymmetric-texture references.
- **noon lightmap pin** (`RefHarnessRenderer.pinNoonLighting`): freeze `ADVANCE_TIME` + `/time set noon` during warmup so the in-world lightmap the BE path samples is stable across the sweep. Plain blocks don't sample the lightmap; only the BE path needs it.
- **Translucent sheet selection** (`BlockFrameRenderer`): `translucentBlockSheet` when any part flags `FLAG_TRANSLUCENT` (stained_glass, ice, slime/honey_block, tinted_glass), else `cutoutBlockSheet`. Matches asset-renderer's source-over alpha blend.
- **Inventory tints** (`resolveInventoryTints`): biome/constant tints resolved to vanilla's no-world `color(state)` (colormap default = the value baked into a block-item icon). `sugar_cane` exception — held-item colour is white (`-1`) but the in-world block is grass-tinted, so substitute `GrassColor.getDefaultColor()`.
- **tripwire_hook cardinal snap** (`CardinalSnapPart`): re-snaps the hook's ±45° faces from vanilla's sub-ULP-tie cardinal (NORTH → 0.40, too dark) to the asset-renderer / in-world cardinal (UP → full-bright).

### Block-entity icon composition (`BlockEntityFrameRenderer`)
Most BEs render raw — skull/chest/shulker_box/conduit/decorated_pot/beacon already sit on the unit block and equal their icon. Five families instead **compose an inventory icon** (canonical facing + recenter-and-fit on a vanilla-extent-walker bbox, `ICON_FIT_EXTENT = 1.4`):
- **bed** — merge both halves (default state is only the foot) at canonical NORTH facing, `iconRotation = 90°`.
- **banner / wall_banner** — replace per-facing yaw with a canonical 180°; flat flag (`BannerFlagModelMixin`).
- **skull** — wall heads re-pointed to the ground transform (rotation 0); dragon head recenter/fit + jaw closed (`animationProgress = -2.5`).
- **sign** — 180° yaw about block-centre so the face turns toward the camera (standing / wall / 3 hanging forms).
- **copper_golem_statue** — entity-convention model (y-down/mirrored), 180° Z flip to stand upright, then fit.

### Glint
`GlintSweeper` renders each foil subject as `FRAME_COUNT = 30` frames, stepping `GlintClock.overrideT` by `STEP_MILLIS = 1000` (both **must match asset-renderer `TestGlintParityVanilla`**). `GlintTexturingMixin` substitutes `overrideT` for vanilla's wall-clock glint time, rebuilding the exact scroll matrix. Subjects: 7 always-foil GUI items (item glint, via `ItemFrameRenderer`) + 4 worn leather-armor diagnostics (armor glint, via an `armor_stand` through `EntityFrameRenderer`). Also dumps `glint/atlas_uv.json` (each item's items-atlas sprite-UV rect) so the asset side samples the glint through vanilla's exact `UV0`.

### Block-render mixins
| Mixin | Target | Effect |
|---|---|---|
| `FreezeSpriteAnimationMixin` | `SpriteContents$AnimationState.tick` | Pin `frame = subFrame = 0` (magma/sea_lantern/prismarine/campfire/sculk/...); frame 0 + blend 0 = asset-renderer's static sampling. Texture-animation analog of `SkipSetupAnimMixin`. |
| `ShadeFalseFullBrightMixin` | `VertexConsumer.putBakedQuad` | Redirect `BakedQuad.direction() → UP` for `shade:false` quads so they saturate the ITEMS_3D diffuse to 1.0 (ladders/cobweb/cross/crop/vine planes), matching in-world `getShade(dir,false) = 1.0`. |
| `GlintTexturingMixin` | `TextureTransform.setupGlintTexturing` | Substitute `GlintClock.overrideT` for wall-clock glint time when `overrideT ≥ 0`. |
| `BannerFlagModelMixin` | `BannerFlagModel.setupAnim` | Cancel the cloth wave → flat flag (`xRot = 0`), matching asset-renderer. Delete if asset-renderer models the wave. |

## Adding a new entity pin mixin

> **Vanilla state-extraction call chain.** Read this before writing a new state-pin mixin. Every entity render goes through:
> ```
> EntityType.create(level, LOAD)                        → fresh, never-ticked entity
> renderer.extractRenderState(entity, state, partialTick)  ← snapshot fields
> dispatcher.submit(state, ...)
>   └─ renderer.submit(state, poseStack, ...)
>        ├─ scale(state.scale, ...)                      (LivingEntityRenderer only)
>        ├─ setupRotations(state, ps, bodyRot, scale)    ← VIRTUAL (14 overrides in 26.1)
>        ├─ scale(-1, -1, 1)                             (chirality)
>        ├─ scale(state, ps)                             (per-renderer scale override)
>        ├─ translate(0, -1.501, 0)                      (model offset)
>        └─ submitModel(model, state, ps, ...)
>             └─ model.setupAnim(state)                  ← bypassed by SkipSetupAnimMixin
>             └─ model.root.submit(...)
> ```

**Subclass ordering caveat.** Subclass `extractRenderState` calls `super.extractRenderState(...)` FIRST, then writes subclass-specific fields. A mixin on the BASE `LivingEntityRenderer` fires before the subclass writes and gets overwritten. **Pin subclass-specific fields in dedicated subclass-renderer mixins** (`BeeStateMixin` targets `BeeRenderer`, not `LivingEntityRenderer`).

### Entity state / pose freezes (pin set)

Full catalog + formulas in README's Mixins section. Quick reference:

- `FreezeAnimationStateMixin` (`LivingEntityRenderer.extractRenderState`) — zero per-tick anim fields (`ageInTicks`, `walkAnimationPos/Speed`, `deathTime`, ...); force `isInWater` on `AbstractFish` (salmon/cod/tropical_fish upright).
- `SuppressShakingMixin` (`LivingEntityRenderer.setupRotations`) — cancel the `isShaking` bodyRot wobble.
- `SkipSetupAnimMixin` (every `setupAnim` callsite) — authored bind pose, not frame-0 animated pose. **Broadest freeze.**
- `BeeStateMixin` (`BeeRenderer`) — `isOnGround = true` (flat wings, level body).
- `EnderDragonModelMixin` / `WitherBossModelMixin` — cancel `setupAnim`; now redundant under `SkipSetupAnimMixin`, kept as model-specific docs.
- `GuardianStateMixin` — `spikesAnimation = 1`, `tailAnimation = 0`, `lookAt = null`.
- `PhantomStateMixin` — `flapTime = 0`.
- `PufferfishStateMixin` — `puffState = STATE_FULL`.
- `ZombieVillagerStateMixin` — `villagerData = default` (PLAINS/NONE/1).
- `DonkeyModelMixin` / `LlamaModelMixin` — hide equipment-driven `left_chest`/`right_chest` bones.
- `ArmorStandBasePlateMixin` (`ArmorStandModel.<init>`) — force `basePlate.visible = false` (asset-renderer toggles the base-plate bone off). Pinned at model construction, not via `showBasePlate`, because `SkipSetupAnimMixin` cancels the `setupAnim` that would otherwise apply the flag.

### Randomization to pin (common sources)

When a new transient entity renders inconsistently across runs, check its constructor for `random.nextX()` and these methods:

| Entity | Source | Symptom | Pin |
|---|---|---|---|
| Guardian | `clientSideTailAnimation = random.nextFloat()` | tail snaps to different shape | `state.tailAnimation=0` |
| Phantom | `getUniqueFlapTickOffset()` (per-instance) | wings at different cycle phase | `state.flapTime=0` |
| (any) | `getEntityToLookAt(...)` → falls back to `Minecraft.getCameraEntity()` | lookAt drifts with player camera | `state.lookAtPosition=null` |
| ZombieVillager | `BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random)` | profession overlay flips between runs | pin `villagerData` to default |
| Bat | `random.nextFloat()` sleeping flutter | TBD | TBD |

## Bounds walker

`EntityFrameRenderer.walkVisibleExtents` measures each (entity, variant) target's screen bounds by walking the model cube hierarchy through the render transform chain, contributing per-opaque-texel positions. Pitfalls (full detail in README's Pipeline gotchas):

- **Plane-cube degenerate polygons** — a 16×16×0 "plane cube" (warden tendrils, wither ribcage) has 4 zero-area edge polygons whose corners span the full extent; skip via the `uMin==uMax || vMin==vMax` UV-collapse check.
- **Sparse-opacity polygons** — contribute only opaque-texel positions (per-texel walk + bilinear interp of the opaque bbox corners), not the polygon's 4 corners.
- **`setupRotations` is virtual** — 14 overrides; e.g. `SquidRenderer` adds `translate(0,-1.2,0)`. Dispatch reflectively to the most-derived override or the squid drops below the canvas.
- **Variant-specific model selection** — `Cow/Pig/Chicken` renderers mutate `this.model` in `submit()`; the walker runs first, so `tryResolveVariantModel` replicates the selection reflectively.
- **`EnergySwirlLayer` conditional rendering** — `CreeperPowerLayer` etc. early-return when `!isPowered(state)`; skip via reflective `isPowered` or the charge mesh inflates unpowered-creeper bounds.
- **`EnderDragonRenderer` is non-`LivingEntityRenderer`** — own `submit()` chain (`translate(0,0,1)` + chirality + `translate(0,-1.501,0)`); dedicated `else if` branch.

## Family-locked sizing

Pre-pass measures every (entity, variant) pair, groups by family root via `EntitySweeper.FAMILY_OVERRIDES`, takes the union of bounds. Each family member uses the union's canvas + scale + anchor so shared geometry is byte-identical across variants. Variants of one `EntityType` (cow_cold, cow_warm, ...) auto-share a family; the override map is for cross-`EntityType` siblings (currently just `stray→skeleton`). Data-driven variants (`cow`, `pig`, `wolf`, `cat`, ...) enumerate via `VARIANT_REGISTRIES`; enum variants without a registry get their own loop (`renderAllHorseCoats` for the equine coat enum, `renderAllMooshroomVariants` for `MushroomCow.Variant` red/brown). Mooshroom is deliberately NOT overridden into cow: the asset-renderer id-encodes it as `mooshroom_red`/`mooshroom_brown` that no longer roll into cow's family-union, so cow canvas-fits to its own body (overriding pushed cow down by the mushroom height). Hard cap `MAX_CANVAS_SIZE` (default 1024) shrinks oversized canvases (ender_dragon, full-scale wither, giant×6) by uniformly scaling down both canvas dimensions + scale.

## Chirality fix

`poseStack.scale(1, 1, -1)` immediately before `mulPose(rotation)`. The transform chain has an odd number of reflections by default (PIP `scale(s, s, -s)` + vanilla `scale(-1, -1, 1)` in `setupRotations`); the explicit Z-negate flips the cumulative determinant back to positive. Without this, models render with back-faces visible (lights inside, textures wound CW).

## When to delete a freeze mixin

> **Delete `EnderDragonModelMixin` once asset-renderer adds animation support.** Same for any per-renderer freeze: once asset-renderer reproduces a feature, removing the freeze restores vanilla behaviour as the new ground truth. `SkipSetupAnimMixin` is the broadest one - removing it should be the last step of asset-renderer animation work. On the block side, `FreezeSpriteAnimationMixin` (texture animation) and `BannerFlagModelMixin` (cloth wave) delete when asset-renderer animates those; `GlintTexturingMixin` and `ShadeFalseFullBrightMixin` are permanent (they enforce determinism / in-world parity, not a freeze).

## Session-refresh checklist

1. Confirm baseline exists: `cd ../asset-renderer && ls cache/asset-renderer/vanilla/26.1/references/{blocks,entities,items,glint} | head`.
2. Re-render one target to check for regressions: `./gradlew :asset-renderer:renderVanillaReferences -PrefharnessTargets=minecraft:cow` (entity) or `minecraft:chest` (BE block).
3. Glint iteration: `./gradlew :asset-renderer:renderVanillaGlintReferences [-PrefharnessTargets=minecraft:nether_star]`.
4. Pose / chirality questions: the empirical answer is the pitch-roll sweep `-PrefharnessPitchRollSweep=true` (first filtered target rendered 576× over a 15° pitch × roll grid).
5. Asset-renderer-side parity work, kit invariants, and JOML factory conventions: see [asset-renderer/CLAUDE.md].

[asset-renderer]: ../asset-renderer
[asset-renderer/CLAUDE.md]: ../asset-renderer/CLAUDE.md
