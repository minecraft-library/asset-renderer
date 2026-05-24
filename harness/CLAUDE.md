# vanilla-reference-harness

Single-purpose headless Fabric mod that drives the real MC client to produce the byte-stable ground-truth PNGs sibling [asset-renderer]'s parity tests diff against. **README is the user-facing reference** (architecture, mixin catalog, family map, configuration); this file is the session-refresh / contributor's quick reference.

## Build / run
- JDK 21, Fabric Loom 1.16-SNAPSHOT, Fabric Loader 0.19.2, Fabric API 0.147.0+26.1.2, MC 26.1.2.
- `./gradlew runRenderReferences [-PrefharnessTargets=ns:id,...]` from this dir, or `./gradlew :asset-renderer:renderVanillaReferences` from asset-renderer (output goes to asset-renderer's cache).
- World corruption from a hard JVM exit: `./gradlew resetRefharnessWorld`.

## Mixin convention

Every mixin gates on `Boolean.getBoolean("refharness.headless")`. Loom run config sets the property to `true`. Non-harness consumers of the jar (e.g. someone depending on this mod for a different reason) get vanilla behaviour back when the property is unset.

Pattern:
```java
@Inject(method = "...", at = @At("HEAD"), cancellable = true)
private void onX(..., CallbackInfo ci) {
    if (!Boolean.getBoolean("refharness.headless")) return;
    // ... headless-only effect ...
    ci.cancel();
}
```

Mixin classes live in `src/client/java/lib/minecraft/refharness/mixin/` and are registered in `src/client/resources/refharness.client.mixins.json`. The harness has three families of mixins:

1. **Suppression** — hide window / sky / clouds / hand (`HeadlessWindowMixin`, `HideSkyMixin`, `HideCloudsMixin`, `HideHandMixin`).
2. **Lighting alignment** — flip vanilla's `cardinalLighting()` N/S↔W/E swap so harness output matches asset-renderer's `BlockFace.lighting()` (`FlipFaceShadingMixin`).
3. **State / pose freezes** — pin transient entity state so renders are reproducible across runs (see below).

## Adding a new pin mixin

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

**Where to pin which field.** The catalog below is the current pin set; common transient sources are listed in the "Randomization to pin" section.

### State / pose freezes (current pin set)

| Mixin | Target | Effect |
|---|---|---|
| `FreezeAnimationStateMixin` | `LivingEntityRenderer.extractRenderState` | Zeroes `ageInTicks`, `walkAnimationPos/Speed`, `deathTime`, `ticksSinceKineticHitFeedback`, `wornHeadAnimationPos`. Forces `state.isInWater=true` on `AbstractFish` (salmon/cod/tropical_fish render upright). |
| `SuppressShakingMixin` | `LivingEntityRenderer.setupRotations` | Cancels the `isShaking(state)` bodyRot wobble (`cos(floor(ageInTicks)*3.25) * PI * 0.4` degrees) - lands on the bind pose instead of an animation yaw offset. |
| `SkipSetupAnimMixin` | every `EntityModel.setupAnim` callsite from `LivingEntityRenderer.submit` | Skips `setupAnim` so the produced PNG uses the authored `createBodyLayer` bind pose, not vanilla's frame-0 animated pose. Asset-renderer doesn't yet animate; bind pose is the fair comparison target. |
| `BeeStateMixin` | `BeeRenderer.extractRenderState` | Forces `state.isOnGround=true` - skips wing flap math + `bobUpAndDown`'s `0.179 rad` forward-tilt; at-rest pose with flat wings + level body. |
| `EnderDragonModelMixin` | `EnderDragonModel.setupAnim` | Cancels `setupAnim` entirely - authored `PartPose` for every part (flat wings, straight neck/tail, closed jaw). Idempotent with `SkipSetupAnimMixin`. |
| `WitherBossModelMixin` | `WitherBossModel.setupAnim` | Cancels at-rest chest-bob. **Now redundant** under `SkipSetupAnimMixin`; kept as model-specific documentation. |
| `GuardianStateMixin` | `GuardianRenderer.extractRenderState` | Pins `spikesAnimation=1.0` (extended-spikes silhouette), `tailAnimation=0` (defeats per-instance `random.nextFloat()` constructor seed), `lookAtPosition=lookDirection=null` (skips eye-direction block that tracks player camera). |
| `PhantomStateMixin` | `PhantomRenderer.extractRenderState` | Pins `flapTime=0`. Vanilla seeds `flapTime = entity.getUniqueFlapTickOffset() + ageInTicks` (per-instance pseudo-random); 0 = canonical flat-wing glide. |
| `PufferfishStateMixin` | `PufferfishRenderer.extractRenderState` | Pins `state.puffState = Pufferfish.STATE_FULL` (= 2). Iconic adult silhouette. Vanilla transient pufferfish defaults to `STATE_SMALL=0` (deflated). |
| `ZombieVillagerStateMixin` | `ZombieVillagerRenderer.extractRenderState` | Pins `state.villagerData` to `Villager.createDefaultVillagerData()` (type `PLAINS`, profession `NONE`, level 1). Vanilla `ZombieVillager.initializeVillagerData` randomly assigns profession + spawn-biome-driven type. |
| `DonkeyModelMixin` | `DonkeyModel` ctor / `createBodyLayer` | Hides `left_chest` / `right_chest` bones (equipment-driven; visible only when `state.hasChest=true`, which never fires for a freshly-loaded harness entity). |
| `LlamaModelMixin` | `LlamaModel` ctor / `createBodyLayer` | Same as donkey - hides equipment-driven `right_chest` / `left_chest`. |

### Randomization to pin (common sources)

When you encounter a new transient entity that renders inconsistently across runs, check its constructor for `random.nextX()` calls and these methods:

| Entity | Source | Symptom | Pin |
|---|---|---|---|
| Guardian | `clientSideTailAnimation = random.nextFloat()` | tail snaps to different shape | `state.tailAnimation=0` |
| Phantom | `getUniqueFlapTickOffset()` (per-instance) | wings at different cycle phase | `state.flapTime=0` |
| (any) | `getEntityToLookAt(...)` → falls back to `Minecraft.getCameraEntity()` | lookAt drifts with player camera | `state.lookAtPosition=null` |
| ZombieVillager | `BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random)` | profession overlay flips between runs | pin `villagerData` to default |
| Bat | `random.nextFloat()` sleeping flutter | TBD | TBD |

## Bounds walker

`EntityFrameRenderer.walkVisibleExtents` measures every (entity, variant) target's screen bounds by walking the model cube hierarchy through the same transform chain as render, contributing per-opaque-texel positions. A few pitfalls:

- **Plane-cube degenerate polygons.** A 16×16×0 "plane cube" (warden tendrils, wither ribcage) has 2 visible faces + 4 zero-area edge polygons whose UVs collapse to `uMin==uMax || vMin==vMax`. Edges render no pixels but their 4 vertex corners span the full cube extent - if treated as regular polygons they balloon bounds. Walker skips them via the UV-collapse check.
- **Sparse-opacity polygons.** 16×16 plane with a small opaque sticker (warden tendrils): contribute only opaque-pixel positions, not the polygon's 4 corners. Per-texel walk + bilinear interp of opaque-pixel bbox corners.
- **`setupRotations` is virtual.** 14 renderers override it (notably `SquidRenderer` adds `translate(0, -1.2, 0)` after standard scale flip). Without dispatching via reflection to the most-derived `setupRotations` during the bounds walk, the rendered squid drops 1.2m below the canvas.
- **Variant-specific model selection.** `CowRenderer` / `PigRenderer` / `ChickenRenderer` keep `Map<ModelType, AdultAndBabyModelPair<Model>>` and mutate `this.model` in `submit()` based on `state.variant.modelAndTexture().model()`. The bounds walker runs BEFORE any submit, so `getModel()` returns the constructor default (e.g. plain `CowModel` with no horns instead of `WarmCowModel`). `tryResolveVariantModel` replicates the submit-time selection reflectively.
- **`EnergySwirlLayer` conditional rendering.** `CreeperPowerLayer` (and other `EnergySwirlLayer` subclasses) early-return from `submit()` when `isPowered(state)` is false. Walker skips these via reflective `isPowered` invocation, otherwise the charge-effect mesh inflates bounds on unpowered creepers.
- **`EnderDragonRenderer` is non-`LivingEntityRenderer`.** Extends `EntityRenderer` directly with its own `submit()` chain (`translate(0,0,1)` + chirality + `translate(0,-1.501,0)`). Walker has a dedicated `else if` branch.

## Family-locked sizing

Pre-pass measures every (entity, variant) pair, groups by family root via `EntitySweeper.FAMILY_OVERRIDES`, takes union of bounds. Each family member uses the union's canvas + scale + anchor so shared geometry is byte-identical across variants. Variants of the same `EntityType` (cow_cold, cow_warm, ...) automatically share a family without needing an override entry; the override map is for cross-`EntityType` siblings (e.g. mooshroom→cow, stray→skeleton) where the Java pipeline auto-detects a shared `geometry_ref` and the harness must mirror the canvas union.

Hard cap `MAX_CANVAS_SIZE` (default 1024) shrinks oversized canvases (ender_dragon, full-scale wither, giant×6) by uniformly scaling down both canvas dimensions + scale.

## Chirality fix

`poseStack.scale(1, 1, -1)` immediately before `mulPose(rotation)`. The transform chain has an odd number of reflections by default (PIP `scale(s, s, -s)` + vanilla `scale(-1, -1, 1)` in `setupRotations`); the explicit Z-negate flips the cumulative determinant back to positive. Without this, models render with back-faces visible (lights inside, textures wound CW).

## When to delete a freeze mixin

> **Delete `EnderDragonModelMixin` once asset-renderer adds animation support.** Same for any per-renderer freeze mixin: once asset-renderer can reproduce a feature, removing the freeze restores vanilla behaviour as the new ground truth. `SkipSetupAnimMixin` is the broadest one - removing it should be the last step of asset-renderer animation work.

## Session-refresh checklist

1. Check what currently differs: `cd ../asset-renderer && ls cache/asset-renderer/vanilla/26.1/references/entities/ | head` to confirm baseline exists.
2. Re-render one entity to check for harness regressions: `./gradlew :asset-renderer:renderVanillaReferences -PrefharnessTargets=minecraft:cow`.
3. For pose / chirality questions, the empirical answer is the pitch-roll sweep: `-PrefharnessPitchRollSweep=true` renders the first filtered target 576× over a pitch × roll grid (15° steps).
4. Asset-renderer-side parity work, kit invariants, and JOML factory conventions: see [asset-renderer/CLAUDE.md].

[asset-renderer]: ../asset-renderer
[asset-renderer/CLAUDE.md]: ../asset-renderer/CLAUDE.md
