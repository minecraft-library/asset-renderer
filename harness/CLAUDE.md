# vanilla-reference-harness

Single-purpose headless Fabric mod that drives the real MC client to produce the byte-stable ground-truth PNGs [asset-renderer]'s parity tests diff against - this mod lives inside that repo, at `harness/`, and stays its own Gradle build. Eight sweeps: **blocks** (true 3D, not item icons), **entities**, **non-block items** (GUI icons), the **player**, **armored mobs**, **animated glint**, **container screens**, and **animated entities**. **README is the user-facing reference** (architecture, mixin catalog, family map, configuration); this file is the session-refresh / contributor's quick reference.

**The eighth is the odd one and it needs its own boot.** Seven of them freeze `setupAnim` and are ground truth for the mesh as authored; the animated sweep turns that freeze off and is ground truth for the mesh its model poses. Both freezes read `refharness.animated` once per JVM, so a run that poses one subject poses every subject and the two sets cannot share a client. `renderVanillaAllReferences` therefore boots twice - the animated pass is an ordering edge on it rather than an eighth arm of `EVERY`.

## Build / run
- JDK 25 (Loom toolchain; `JAVA_25` mixin compat), Gradle 9.4.1, Fabric Loom 1.16-SNAPSHOT, Fabric Loader 0.19.2, Fabric API 0.147.0+26.1.2, MC 26.1.2.
- Full sweep (blocks + items + entities, ~5 min warm): `./gradlew runRenderReferences [-PrefharnessTargets=ns:id,...]` from this dir, or `./gradlew renderVanillaReferences` from asset-renderer (output → asset-renderer's cache). **Not the whole tree** - it leaves `glint/` and `armor/` alone, which is 337 of 2310 references.
- Whole tree in one boot - **run this after any change to a frame renderer**: `./gradlew runRenderReferences -PrefharnessEverySweep=true`, or `./gradlew renderVanillaAllReferences` from asset-renderer. Every sweep in one client launch, measured at **152s** against 125s for the incomplete full sweep and 195s for the three narrower tasks run separately - it pays the boot once. The narrow modes below stay for scoped iteration; relying on them is what left stale ground truth on disk twice. **The whole-tree render reproduces, measured rather than assumed**: two boots seven hours and 26 perturbation cycles apart hashed the same tree, and every sweep digest the parity store holds rests on that.
- Glint-only (fast, decoupled): `./gradlew runRenderReferences -PrefharnessGlintOnly=true`, or `./gradlew renderVanillaGlintReferences` from asset-renderer.
- Animated-only: `./gradlew runRenderReferences -PrefharnessAnimated=true`, or `./gradlew renderVanillaAnimationReferences` from asset-renderer. ~58 s for 90 subjects x 8 frames into `animation/<subject>/frame_NNN.png`. It is the ONLY run whose client draws a posed subject; every other one, this flag unset, is bit-for-bit what it always was.
- Armor-only (fast, decoupled): `./gradlew runRenderReferences -PrefharnessArmorOnly=true`, or `./gradlew renderVanillaArmorReferences` from asset-renderer. `ArmorSweep` renders a fixed roster of **armored** mobs, adult and baby (zombie / piglin, iron and dyed leather), which the main entity sweep cannot produce - it equips nothing and ages nothing. Diagnostic, not a byte-stable reference set: each subject is fit to 80% of a square canvas so the armor shell stays inside the frame regardless of what was measured, and the consuming diff crops and aligns by silhouette. (The bounds walker **can** see the shell now - it used to reject every `ArmorModelSet` component because the record is generic and they all erase to `Object` - so the reserved margin is belt-and-braces rather than the only thing keeping the shell in frame.)
- Output dirs under the output root: `blocks/`, `entities/`, `items/`, `glint/` (+ `glint/atlas_uv.json`), `armor/`.
- World corruption from a hard JVM exit: `./gradlew resetRefharnessWorld`.
- **World creation fires on the first screen that is not a loading step.** `WorldBootstrap` skips `LevelLoadingScreen`, `ProgressScreen`, `GenericMessageScreen` and `GenericWaitingScreen` and takes over on anything else. Keying on `TitleScreen` alone hung the run with no error and no output whenever the client settled somewhere else first, and the first-run accessibility onboarding is the screen that has actually done it. The accepted risk is the mirror image - firing on a screen the client is not ready to leave. If `createFreshLevel` throws, the catch resets the one-shot and the next settled screen retries; if it neither throws nor produces a level, the watchdog is what ends the run. `RefHarnessClient` halts with exit **4** once 3600 consecutive client ticks have passed with no level or no player, printing the last screen the hook saw and whether creation ever fired - the two facts that say which of the two happened. The count starts at the first tick after the mod initialises and the client ticks at twenty a second throughout the resource reload, so those three minutes are boot **plus** the wait, not three minutes of waiting alone; a machine that takes longer than that to reach a screen trips it. A level and a player together reset the count to zero. **Neither half is under a test, and nothing in this build is** - it has `main` and `client` source sets and no test one, so a change here is caught by the compiler or by an actual run and by nothing in between. Measured: narrowing the skip list to `LevelLoadingScreen` alone, inverting the guard so no world is ever created, and setting the timeout to one tick or to unreachable each compile clean and each leaves asset-renderer's `./gradlew test` green. Renaming a screen class or dropping `hasScheduled()` is caught, because that is a symbol the compiler resolves. Change one of these and run the sweep.

## Sweep architecture

`RefHarnessRenderer` advances one sweep step per client tick after a 60-tick warmup. A run's sweeps come from `HarnessMode` - `FULL` is block → item → entity → player, `EVERY` is those four plus glint, armor and menus; `GLINT`, `PLAYERS`, `ARMOR`, `MENUS`, `ANIMATION` and `PITCH_ROLL` each run one sweep alone. Setting two mode properties now **throws** rather than letting the first silently win. **`FULL` is the odd name** - `EVERY` is the one that renders the whole reference tree, and the gap between them is where stale ground truth accumulated twice. `ANIMATION` is alone in its run by construction rather than by choice, the freezes being a property of the JVM.

Every sweep implements `api.Sweep` (`outputDir` / `enumerate` / `key` / `canvas` / `render`, plus the `prepare` / `beforeSubject` / `afterSweep` hooks) and is driven by `api.SweepRunner`, which owns the work index, the tally, the completion latch and the one-PNG-per-tick pacing. **Every sweep renders exactly one subject per tick**, entity variants included - the readback is async, so firing a second render before the prior one lands would corrupt it.

Drawing goes through `pip.PipTarget`: one offscreen `RGBA8 + DEPTH32` target per renderer, `copyTextureToBuffer` → `NativeImage` → PNG. The target is allocated only once a renderer has committed to drawing (decline before draw), and its GPU textures are deliberately leaked at end-of-sweep rather than closed into an in-flight callback.

Adding a subject is one `Sweep` in `sweep/`, optionally one `FrameRenderer` in `frame/`, one row in `HarnessMode` - **including the `EVERY` arm**, which is the one that renders the whole tree - and, for a narrowing mode, one `optionalProperty(...)?.let { property(...) }` line in `build.gradle.kts`. **Miss that last line and the mode silently runs `FULL`**: the `-P` never becomes the system property `HarnessConfig` reads, so the task named for one sub-tree re-renders every other one instead and reports success.

## Capturing a GUI (the menu sweep)

`MenuSweep` is the one sweep that submits no geometry. A container screen is worth comparing against only as the client composes it - the panel, its two labels, its slots through vanilla's own GUI item atlas - so the capture drives vanilla's own extract-then-draw and changes only where the draw lands.

- **`GuiRenderer` cannot be redirected the way everything else here is.** It resolves `Minecraft.getMainRenderTarget()` at one call site inside its private `draw` and never reads `RenderSystem.outputColorTextureOverride`, which is what `PipTarget` sets and what every other offscreen render depends on - a grep of the extracted client finds exactly four classes holding that field (`GuiItemAtlas`, `PictureInPictureRenderer`, `LevelRenderer`, `RenderType`) and `GuiRenderer` is not one. `MenuRenderTargetMixin` swaps the target at that call site instead, reading `GuiTarget.override`, which is the `GlintClock` pattern: a plain holder the sweep arms and clears so the redirect is inert for every frame that is not being captured. It needs a real `RenderTarget` (a `TextureTarget`), not a `GpuTextureView`.
- **The panel is the background half of a screen's state, and the caller is what draws it.** `AbstractContainerScreen.extractRenderState` calls only `extractContents` / `extractCarriedItem` / `extractSnapbackItem` / `extractTooltip` - that yields the labels and the slots and no panel at all. The blit lives in each screen's own `extractBackground` override, so a capture calls **both** entry points, background first.
- That override reaches its blit through `super`, so the base `Screen.extractBackground` runs first and, with a level loaded, draws the blurred world plus a dark tint behind the panel. `SuppressScreenBackdropMixin` cancels the base only - the cancel returns from `Screen`'s own body and the subclass's blits are untouched - and is gated on a capture being armed as well as on headless.
- `GameRenderer.guiRenderer` and `.fogRenderer` are private with no getters; `GameRendererAccessor` opens both. The draw is `guiRenderer.render(fogRenderer.getBuffer(FogMode.NONE))`.
- Three pieces of global state move for the duration and go back in a `finally`: `windowRenderState.{width, height, guiScale}` (the GUI projection is built from these, so they are what puts the panel on the canvas at one Minecraft pixel per `GUI_SCALE`), the `GuiRenderState` the extract fills, and the target. `screen.init(w, h)` at the panel's own size is what makes `leftPos == topPos == 0`; `mouseX = mouseY = -1` neutralises the slot highlight and the tooltip together, being one test in vanilla rather than two.
- The read-back is `pip/TextureReadback`, shared with `PipTarget` rather than copied. It takes the extent as parameters because the copy's callback runs a frame later, by which time the target that owns the texture may have resized - passing them in is what makes handing it a field that is re-read later unrepresentable.
- **A subject's state lives on widgets `init` builds, so a subject that has something to say says it in `MenuSubject.afterInit`** - after `screen.init` and before the extract, the one window in which a widget both exists and has not been read. The named anvil is the only user: `AnvilScreen.name` is private, `AnvilScreenAccessor` opens it, and the responder is dropped before the value is set because the screen's own sends a rename packet per change. Every other route to that text moves something else too - a named item in the input slot fills the slot and flips the field to its enabled sprite - so two subjects would differ in three ways rather than in the one being measured.
- **A focused text field blinks on the wall clock, so it is pinned.** `TextCursorUtils.isCursorVisible` is `(millis-since-focus / 300) % 2 == 0`, and the anvil focuses its field during `init`; the capture draws immediately after, so the caret is visible on every ordinary run and a 300 ms stall anywhere between the two would blink it out and move the reference by the ten pixels it paints. `FreezeTextCursorMixin` pins it visible - the same freeze `FreezeSpriteAnimationMixin` applies to texture animation, and visible rather than hidden because a freeze holds what the client draws still rather than deciding what it draws.
- The caret has **two forms and the value's length picks which**: vanilla appends a `_` glyph, drawn with a shadow at the text origin, unless the cursor is at the end of a value already at its maximum length, when it fills a one-pixel bar spanning eleven rows instead. The anvil caps its field at 50, so the unnamed subject draws the first and a subject named at that cap draws the second - which is why the named one is at exactly fifty characters and not at a comfortable length.

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

1. **Suppression** - hide window / sky / clouds / hand (`HeadlessWindowMixin`, `HideSkyMixin`, `HideCloudsMixin`, `HideHandMixin`).
2. **Lighting alignment** - flip vanilla's `cardinalLighting()` N/S↔W/E swap to match asset-renderer's `BlockFace.lighting()` (`FlipFaceShadingMixin`).
3. **Entity state / pose freezes** - pin transient entity state so renders are reproducible (see catalog below).
4. **Block-render fixes** - make block / glint renders match the in-world appearance + run deterministically (`FreezeSpriteAnimationMixin`, `ShadeFalseFullBrightMixin`, `BannerFlagModelMixin`, `GlintTexturingMixin`).

## Block / item / glint pipeline

**Blocks are always a 3D sweep, and they are the vanilla inventory icon wherever vanilla has one.** `BlockSweep` routes each block by type:

- **Plain blocks** → `BlockFrameRenderer`: submits via `SubmitNodeStorage.submitBlockModel` at the block's authored `display.gui` pose (`block/block.json`'s `R_XYZ(30°,225°,0°)` + scale `0.625` for most, but stairs are `[30,135,0]` and fence gates `[30,45,0]`) under `ITEMS_3D` lighting. **Which geometry is `BlockIconGeometry.resolve`'s answer**: a block whose `items/<name>.json` is a plain `minecraft:model` root naming a `block/` model submits the quads vanilla baked for that item, at `BlockModelRotation.IDENTITY` - so no blockstate variant rotation and no multipart assembly, which is what makes a stair show its riser and a fence icon a post with two arms. Everything else - `item/generated` blocks (rails, vines, ladders, lily_pad, seagrass, sculk_vein, doors, hanging signs) and dispatch-rooted items - has no vanilla 3D icon, and submits its `BlockStateModel` as before, a flat 2D billboard being no use as block ground truth.
  - **Earlier this renderer took every block's geometry from `BlockStateModelSet`**, on the stated grounds that a block sweep is "never an item-icon sweep". That is right for the sprite-icon blocks and was wrong for the rest: it paired in-world orientation with the inventory pose, which vanilla never draws, and cost 107 blocks their icon's orientation plus 52 their inventory model, out of the 1091 blocks this sweep renders. That vanilla never reads a blockstate to draw an icon is bytecode rather than inference: `ItemModelResolver.appendItemLayers` reads the stack's `DataComponents.ITEM_MODEL` and looks the id up through `ModelManager.getItemModel`, a map filled from `assets/<ns>/items/<name>.json`, and for a plain `minecraft:model` root the bake is `ResolvedModel.bakeTopGeometry(slots, baker, BlockModelRotation.IDENTITY)`. `BlockStateModelSet.get` is invoked from six sites in five classes - `Minecraft.selfTest`, `LevelRenderer.submitBlockDestroyAnimation` and `LevelRenderer.extractBlockOutline`, `SectionCompiler.compile`, `BlockFeatureRenderer.renderMovingBlockSubmits`, and `BlockModelSet.createFallbackModel` - and `BlockModelSet.get` from one, `BlockModelResolver`; none of them a GUI or item path.
  - The predicate reads the shipped `items/<name>.json` rather than a runtime proxy, because asset-renderer applies the same test to the same file - the two must not drift on which blocks are icons.
- **`EntityBlock` + registered BE renderer** → `BlockEntityFrameRenderer`: dispatches the vanilla `BlockEntityRenderer` (signs/beds/banners/heads/shulker_boxes/bells/decorated_pots/...) against a transient, never-ticked `BlockEntity` wired to `client.level`, capturing the real in-world geometry. **Its static block half takes the icon split too** - `submitRawBlockEntity` runs the same `BlockIconGeometry.swapIn` over the parts it collects, and the block entity still submits on top, so an icon-backed block whose entity adds geometry composes both. Routing is measured: 173 blocks reach this renderer, **118** take one of the five icon-composition branches and never see the swap, **55** reach `submitRawBlockEntity`, and **22** of those have a block-model icon. Only 13 of the 22 resolve a different model that way - the 12 shelves (`block/<wood>_shelf_inventory` against the assembled multipart) and `structure_block` (`block/structure_block` against its `mode=load` model). The other 9 (beacon, `enchanting_table`, lectern, spawner, `trial_spawner`, vault, `test_instance_block`, `suspicious_sand`, `suspicious_gravel`) name the same model either way and are byte-identical across the change.
- **`EntityBlock` without a renderer** (barrel, hopper, brewing_stand, furnace, chiseled_bookshelf, ...) → falls back to the plain `BlockFrameRenderer` path and takes the icon split there.

`ItemSweep` renders only non-`BlockItem`s (BlockItems are already covered by `BlockSweep`) through `ItemFrameRenderer` - the vanilla GUI inventory-icon path - to `items/`.

### Block determinism + in-world-appearance fixes
- **`FirstVariantRandomSource`** (`nextInt → 0`) pins weighted variant lists (bedrock/stone/netherrack rotations, rotated-cube tiles) to `variants[0]`, matching asset-renderer's `BlockStateLoader.parseVariants`. A live `RandomSource` baked a random rotation into asymmetric-texture references.
- **noon lightmap pin** (`RefHarnessRenderer.pinNoonLighting`): freeze `ADVANCE_TIME` + `/time set noon` during warmup so the in-world lightmap the BE path samples is stable across the sweep. Plain blocks don't sample the lightmap; only the BE path needs it.
- **Translucent sheet selection** (`BlockFrameRenderer`): `translucentBlockSheet` when any part flags `FLAG_TRANSLUCENT` (stained_glass, ice, slime/honey_block, tinted_glass), else `cutoutBlockSheet`. Matches asset-renderer's source-over alpha blend.
- **Inventory tints** (`resolveInventoryTints`): biome/constant tints resolved to vanilla's no-world `color(state)` (colormap default = the value baked into a block-item icon). `sugar_cane` exception - held-item colour is white (`-1`) but the in-world block is grass-tinted, so substitute `GrassColor.getDefaultColor()`.
- **tripwire_hook cardinal snap** (`CardinalSnapPart`): re-snaps the hook's ±45° faces from vanilla's sub-ULP-tie cardinal (NORTH → 0.40, too dark) to the asset-renderer / in-world cardinal (UP → full-bright).

### Block-entity icon composition (`BlockEntityFrameRenderer`)
Most BEs render raw - skull/chest/shulker_box/conduit/decorated_pot/beacon already sit on the unit block and equal their icon. Five families instead **compose an inventory icon** (canonical facing + recenter-and-fit on a vanilla-extent-walker bbox, `ICON_FIT_EXTENT = 1.4`):
- **bed** - merge both halves (default state is only the foot) at canonical NORTH facing, `iconRotation = 90°`.
- **banner / wall_banner** - replace per-facing yaw with a canonical 180°; flat flag (`BannerFlagModelMixin`).
- **skull** - wall heads re-pointed to the ground transform (rotation 0); dragon head recenter/fit + jaw closed (`animationProgress = -2.5`).
- **sign** - 180° yaw about block-centre so the face turns toward the camera (standing / wall / 3 hanging forms).
- **copper_golem_statue** - entity-convention model (y-down/mirrored), 180° Z flip to stand upright, then fit.

### Glint
`GlintSweep` renders each foil subject as `FRAME_COUNT = 30` frames, stepping `GlintClock.overrideT` by `STEP_MILLIS = 1000` (both **must match asset-renderer `TestGlintParityVanilla`**). `GlintTexturingMixin` substitutes `overrideT` for vanilla's wall-clock glint time, rebuilding the exact scroll matrix. Subjects: 7 always-foil GUI items (item glint, via `ItemFrameRenderer`) + 4 worn leather-armor diagnostics (armor glint, via an `armor_stand` through `EntityFrameRenderer`). Also dumps `glint/atlas_uv.json` (each item's items-atlas sprite-UV rect) so the asset side samples the glint through vanilla's exact `UV0`.

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

**Subclass ordering caveat.** Subclass `extractRenderState` calls `super.extractRenderState(...)` FIRST, then writes subclass-specific fields. A mixin on the BASE `LivingEntityRenderer` fires before the subclass writes and gets overwritten. **Pin subclass-specific fields in dedicated subclass-renderer mixins** (`GuardianStateMixin` targets `GuardianRenderer`, not `LivingEntityRenderer`).

### Entity state / pose freezes (pin set)

Full catalog + formulas in README's Mixins section. Quick reference:

- `FreezeAnimationStateMixin` (`LivingEntityRenderer.extractRenderState`) - zero per-tick anim fields (`ageInTicks`, `walkAnimationPos/Speed`, `deathTime`, ...); force `isInWater` on `AbstractFish` (salmon/cod/tropical_fish upright).
- `SuppressShakingMixin` (`LivingEntityRenderer.setupRotations`) - cancel the `isShaking` bodyRot wobble.
- `SkipSetupAnimMixin` (every `setupAnim` callsite) - authored bind pose, not frame-0 animated pose. **Broadest freeze**, and the one `refharness.animated` lifts.
- `WitchNoseMixin` (`WitchRenderer`) - `entityId = 0`. Vanilla bobs the nose at `0.01 * (entityId % 10)` so a crowd of witches does not bob in lockstep, and an id comes off a counter over every entity the client has built - so which of the ten frequencies a subject gets is a function of how many were built before it. Inert while `setupAnim` is frozen; without it the animated set does not reproduce.
- No bee pin. `BeeModel.setupAnim` skips the wing flap and the body bob when `isOnGround`, and a
  never-ticked bee reports false - so forcing the landed pose was a state no subject here is in,
  inert on every frozen sub-tree and worth 120 of animated delta on the one that reads it.
- `EnderDragonModelMixin` / `WitherBossModelMixin` - cancel `setupAnim`; now redundant under `SkipSetupAnimMixin`, kept as model-specific docs.
- `GuardianStateMixin` - `tailAnimation = 0`, `lookAt = null`; `spikesAnimation` deliberately unpinned.
- `PhantomStateMixin` - `flapTime = 0`.
- `PufferfishStateMixin` - `puffState = STATE_FULL`.
- `ZombieVillagerStateMixin` - `villagerData = default` (PLAINS/NONE/1).
- `DonkeyModelMixin` / `LlamaModelMixin` - hide equipment-driven `left_chest`/`right_chest` bones.
- `ArmorStandSpawnFlagsMixin` (`ArmorStandModel.<init>`) - force `left_arm` / `right_arm` invisible, reached through `root` because they are declared on `HumanoidModel`. Gives the stand the arms a freshly spawned one has. **Both flags this model reads live in one synched byte that defaults to zero, with opposite senses**: `showArms()` is `(flags & 4) != 0` (plain bit, spawned = no arms) while `showBasePlate()` is `(flags & 8) == 0` (inverted - the stored bit means "no base plate", spawned = plate kept). `SkipSetupAnimMixin` cancels the `setupAnim` that applies both, so every part keeps `ModelPart`'s constructed `visible = true` - right for the plate, wrong for the arms, so only the arms are pinned.
- `TurtleEggBellyMixin` (`AdultTurtleModel.<init>`) - force `eggBelly.visible = false` (asset-renderer toggles the `egg_belly` bone off; a default turtle has `hasEgg = false`). Pinned at model construction for the same reason as `ArmorStandSpawnFlagsMixin` - `setupAnim` (which sets `eggBelly.visible = hasEgg`) is cancelled.
- `ArmadilloShellMixin` (`ArmadilloModel.<init>`) - force `cube.visible = false`. An armadillo has two whole bodies in one mesh and `setupAnim` picks between them off `isHidingInShell`, an uninitialised boolean, so a fresh one is walking. With the skip in force both bodies drew and the shell closed the gap between the legs, disagreeing with the Java render on 5916 pixels of silhouette. **Its constructor is `(ModelPart, AnimationDefinition x4)`** - a `(ModelPart)` injector crashes the client with `InvalidInjectionException` rather than failing to apply.
- `CamelSaddleReinsMixin` (`CamelSaddleModel.<init>`) / `EquineSaddleLinesMixin` (`EquineSaddleModel.<init>`) - force the reins invisible on a saddle nothing is riding, which is what `setupAnim` writes from `isRidden` and the skip leaves at `true`. One class serves five equines: donkey and mule bake their saddle through `DonkeyModel.createSaddleLayer` and are still handed `EquineSaddleModel` to pose it, so the model a layer holds is what says which bones toggle and the factory that baked the mesh is not.

### Randomization to pin (common sources)

When a new transient entity renders inconsistently across runs, check its constructor for `random.nextX()` and these methods:

| Entity | Source | Symptom | Pin |
|---|---|---|---|
| Guardian | `clientSideTailAnimation = random.nextFloat()` | tail snaps to different shape | `state.tailAnimation=0` |
| Phantom | `getUniqueFlapTickOffset()` (per-instance) | wings at different cycle phase | `state.flapTime=0` |
| (any) | `getEntityToLookAt(...)` → falls back to `Minecraft.getCameraEntity()` | lookAt drifts with player camera | `state.lookAtPosition=null` |
| ZombieVillager | `BuiltInRegistries.VILLAGER_PROFESSION.getRandom(random)` | profession overlay flips between runs | pin `villagerData` to default |
| Bat | `random.nextFloat()` sleeping flutter | TBD | TBD |
| Witch | `entityId % 10` nose-bob frequency | nose sits at a different angle every run from tick 1 on | `state.entityId=0` |

**A per-instance offset is a randomization even where no `random` call is in sight.** Vanilla spreads
an idle animation across a crowd by seeding it from the entity's network id, and that id is a counter
over everything the client has built - so it reads as deterministic per subject and is not. It is
invisible while `setupAnim` is frozen, and it is what a strip catches: frame 0 is stable, because
every one of these enters as a phase and `sin(0)` is zero whatever the frequency.

## Bounds walker

`EntityBoundsWalker` measures each (entity, variant) target's screen bounds by walking the model cube hierarchy through the render transform chain, contributing per-opaque-texel positions. It is package-private and holds the whole bounds half; `EntityFrameRenderer` keeps one and answers `measureBounds`, which is the way in. `walkVisibleExtents` is the walk itself and is private to the walker. Pitfalls (full detail in README's Pipeline gotchas):

- **Plane-cube degenerate polygons** - a 16×16×0 "plane cube" (warden tendrils, wither ribcage) has 4 zero-area edge polygons whose corners span the full extent; skip via the `uMin==uMax || vMin==vMax` UV-collapse check. **The RENDER has to take the same check, and `DegenerateUvPolygonMixin` is where it does** - the walker's comment says those polygons "render no pixels", which is true only while the cube is *un*inflated. An overlay layer that inflates a plane cube (`tropical_fish`'s pattern overlay, `grow 0.008`) moves the corner positions but not the UV unwrap, which `ModelPart$Cube` derives from the authored size - so the 4 edges become real slivers two inflates thick that still carry a zero-width UV strip, and the GPU rasterizes them, sampling one texel column across a surface with no texture extent. At 256 px/block that sliver is `0.181` px: invisible in game at any real entity size, wide enough here to catch a sample column and paint a hard 1-px line down the reference, **detached from the subject by up to 55 px**. It cost the six large-shape `tropical_fish` rows a combined `2.2` of asset-renderer parity against a renderer that is right not to draw it. The mixin filters at `ModelPart$Cube` construction so the render and the measurement read the same `polygons` array and cannot drift; it moved 8 of 2311 references (six fish, two `bee`), all better, and is byte-neutral for every plain plane cube. **A long thin straight line in a reference is a draw defect until proven otherwise** - check whether it is detached from the silhouette before looking for geometry.
- **Sparse-opacity polygons** - contribute only opaque-texel positions (per-texel walk + bilinear interp of the opaque bbox corners), not the polygon's 4 corners.
- **`setupRotations` is virtual** - 14 overrides; e.g. `SquidRenderer` adds `translate(0,-1.2,0)`. Dispatch reflectively to the most-derived override or the squid drops below the canvas.
- **Variant-specific model selection** - `Cow/Pig/Chicken` renderers mutate `this.model` in `submit()`; the walker runs first, so `tryResolveVariantModel` replicates the selection reflectively.
- **Age-specific model selection** - `AgeableMobRenderer.submit` mutates `this.model` to `babyModel`/`adultModel` from `state.isBaby` for the same reason, so `getModel()` hands the walker whichever age the PREVIOUS subject rendered. Invisible to the main sweep (every subject is an adult and the field starts adult) but it makes any baby render order-dependent: a baby measured against the adult mesh floats in an oversized canvas, an adult measured against the baby mesh is scaled up until it clips. `tryResolveAgeModel` replicates the pick.
- **Size-specific model selection** - same shape again, and `tryResolveSizeModel` is the table-driven one: `SIZE_MODELS` names the render-state field and the renderer's meshes smallest-first, per renderer. Three entries - `PufferfishRenderer` on `puffState` (int), `SalmonRenderer` on `variant` (enum ordinal), and `ArmorStandRenderer` on `isSmall`. The last is a **boolean**, so it indexes the pair `[smallModel, bigModel]` as `small ? 0 : 1`, which is what keeps "smallest first" true for a selector that is a flag rather than a size. Without it a small armour stand is measured against `bigModel` and framed for a body it is not drawn on.
- **`EnergySwirlLayer` conditional rendering** - `CreeperPowerLayer` etc. early-return when `!isPowered(state)`; skip via reflective `isPowered` or the charge mesh inflates unpowered-creeper bounds.
- **Unworn equipment layers must pad no bounds** - `isLayerActiveForState` activates *every* equipment layer once the subject carries *any* equipment (`carriesEquipment` is generic), so a saddled horse also walks its body-armour layer and an armoured horse its saddle layer - only one item is worn at a time. The unworn layer resolves no equipment texture through `EquipmentClientInfo`, so `walkLayerExtents` skips it entirely rather than falling back to the body texture; the fallback would measure that layer's body-shaped, inflated mesh (horse body armour is `createBodyMesh@grow=0.1@scaled=1.1`) as fully opaque and grow the canvas 3-5px past what renders (horse/nautilus saddle framing). A layer with no `LayerType` (wool, mooshroom body, humanoid armour, elytra) genuinely has no equipment texture and still falls back to the body texture.
- **A layer gated on more than equipment needs the rest of its gate** - `RopesLayer` (happy_ghast) draws only when `state.isLeashHolder` **and** the body item is a harness. `carriesEquipment` answers the second half, so once equipment could be selected the layer started being walked for a subject holding no leash, and its mesh is the whole ghast body at `CubeDeformation(0.2)` on the 4× transformer. `isLayerActiveForState` asks the first half through `stateFlag(state, "isLeashHolder", ...)`, the same shape as the wool layer's `isSheared` probe. Read the flag off the render state, not off what the sweep requested - the two are set by different routes and only the field answers for the draw.
- **`EnderDragonRenderer` is non-`LivingEntityRenderer`** - own `submit()` chain (`translate(0,0,1)` + chirality + `translate(0,-1.501,0)`); dedicated `else if` branch.

## Family-locked sizing

**Every entity canvas width is rounded up to even, and the asset-renderer rounds its own the same way** (`EntitySweep` after the cap shrink; `EntityRenderer.evenWidth` there). The canvas centres the subject's anchor at `scope.width() / 2.0f`, and for a left-right symmetric subject the front vertical corner **is** the anchor, so it lands on `w/2` regardless of extent - a pixel centre at odd `w`, which puts a sample exactly on the screen edge where its two faces meet, and a pixel boundary at even `w`, which no sample can reach. Odd widths were putting up to **85%** of a row's whole parity error on that one column (36 rows above 25%, zero even-width rows at any); rounding took that to **0** rows and the corpus's centre-column mass from `3.27%` to `0.57%`, worth `-4.05` on the entity sum. Only the **width** - a subject is symmetric left to right, not top to bottom, so the height has no corresponding edge. **This is legitimate because the placement is the harness's convention and not vanilla's**: the canvas is `ceil(bounds.width() * PIXELS_PER_BLOCK)` off the harness's own alpha-tight walk, drawn into a PiP scope the harness created, and vanilla never renders an entity into a `181x202` frame. The two repos must move in the same commit pair, and it moves **265 of 2311** references. Glint / player / armour canvases are `Canvas.square(IMAGE_SIZE)` at a fixed even `512` and were never in scope.

Pre-pass measures every (entity, variant) pair, groups by family root via `EntityRoster.FAMILY_OVERRIDES`, takes the union of bounds. Each family member uses the union's canvas + scale + anchor so shared geometry is byte-identical across variants. Variants of one `EntityType` (cow_cold, cow_warm, ...) auto-share a family; the override map is for cross-`EntityType` siblings (currently just `stray→skeleton`). Data-driven variants (`cow`, `pig`, `wolf`, `cat`, ...) enumerate via `EntityRoster.VARIANT_REGISTRIES`; enum variants without a registry (the equine coat enum, `MushroomCow.Variant` red/brown) are enumerated by their own arms of `EntitySweep.enumerate`. **The pre-pass enumeration is deliberately not the render enumeration**: it measures registry variants and the plain default of everything else, *including* horse and mooshroom, whose coats and colours the render pass expands. Bounds unions only grow, so measuring those expansions would grow their canvases and move every horse and mooshroom reference. Mooshroom is deliberately NOT overridden into cow: the asset-renderer id-encodes it as `mooshroom_red`/`mooshroom_brown` that no longer roll into cow's family-union, so cow canvas-fits to its own body (overriding pushed cow down by the mushroom height). Hard cap `MAX_CANVAS_SIZE` (default 1024) shrinks oversized canvases (ender_dragon, full-scale wither, giant×6) by uniformly scaling down both canvas dimensions + scale. **Above the cap, an over-measure stops being padding and becomes a resize**: below it a few phantom pixels of bounds only widen the frame, but above it the uniform shrink is computed from the measured longest side, so the whole subject renders smaller. happy_ghast's ropes layer padded its bounds 1462×1714 → 1485×1755 and the subject came out 2.34% small - a whole-silhouette mismatch that reads nothing like the 3-5px halo the same class of bug leaves on a horse.

## Chirality fix

`poseStack.scale(1, 1, -1)` immediately before `mulPose(rotation)`. The transform chain has an odd number of reflections by default (PIP `scale(s, s, -s)` + vanilla `scale(-1, -1, 1)` in `setupRotations`); the explicit Z-negate flips the cumulative determinant back to positive. Without this, models render with back-faces visible (lights inside, textures wound CW).

## When to delete a freeze mixin

> **A `setupAnim` freeze is never deleted; it is gated.** `SkipSetupAnimMixin` and the two per-model
> cancels beside it (`EnderDragonModelMixin`, `WitherBossModelMixin` - the dragon's is load-bearing,
> its renderer not being a `LivingEntityRenderer`) are what `entities/` is ground truth FOR, and the
> asset-renderer's own default draws the mesh as authored. Deleting one would move 88 of the 90
> modelled entities. So each of them, and `FreezeAnimationStateMixin`'s `ageInTicks`, reads
> `HarnessConfig.ANIMATED` and the two reference sets exist side by side.
>
> A **pin** is a different thing and outlives every freeze: `GlintTexturingMixin`, `WitchNoseMixin`,
> the guardian's tail and gaze, the phantom's flap offset all take an input that varies with the
> client's own history out of the render, and an animated run needs them more than a frozen one does,
> because a frozen run reads most of them through no code at all.
>
> On the block side, `FreezeSpriteAnimationMixin` (texture animation) and `BannerFlagModelMixin`
> (cloth wave) delete when asset-renderer animates those; `ShadeFalseFullBrightMixin` is permanent
> (it enforces in-world parity, not a freeze).
>
> **A pin that reaches nothing under the freeze is not free.** It is unread on seven sub-trees and
> read on the eighth, so a value chosen to look right is a state the asset side has no way to know
> about and reads as its own defect. The guardian's spikes were exactly that - pinned to the alert
> pose the field is inert in a frozen run, worth 128 of animated delta against a table reproducing
> vanilla's own zero. Where such a field is deterministic already, leave it alone and say so.

## Session-refresh checklist

1. Confirm baseline exists: `cd .. && ls cache/asset-renderer/vanilla/26.1/references/{blocks,entities,items,glint} | head`.
2. Re-render one target to check for regressions: `./gradlew renderVanillaReferences -PrefharnessTargets=minecraft:cow` from asset-renderer (entity) or `minecraft:chest` (BE block).
3. Glint iteration: `./gradlew renderVanillaGlintReferences [-PrefharnessTargets=minecraft:nether_star]` from asset-renderer.
4. Pose / chirality questions: the empirical answer is the pitch-roll sweep - `./gradlew renderVanillaPitchRollProbe -PrefharnessTargets=ns:id` from asset-renderer, or `-PrefharnessPitchRollSweep=true` from this directory (first filtered target rendered 576× over a 15° pitch × roll grid). It is its own task on the asset side now: riding `renderVanillaReferences` made a task named for references render none.
5. Asset-renderer-side parity work, kit invariants, and JOML factory conventions: see [asset-renderer/CLAUDE.md].

[asset-renderer]: ..
[asset-renderer/CLAUDE.md]: ../CLAUDE.md
