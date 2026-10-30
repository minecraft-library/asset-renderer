# vanilla-reference-harness

Single-purpose headless Fabric mod that drives the real MC client to produce the byte-stable ground-truth PNGs [asset-renderer]'s parity sweeps diff against - this mod lives inside that repo, at `harness/`, and stays its own Gradle build. The sweeps: **blocks** (true 3D, not item icons), **entities**, **non-block items** (GUI icons), the **player**, **armored mobs**, **animated glint**, **container screens**, **tooltips**, **animated entities**, and **walking entities**. **README is the user-facing reference** (architecture, mixin catalog, family map, configuration); this file is the session-refresh / contributor's quick reference.

**The last two lift the freeze every other sweep is defined by, and all of them share one boot.** **The
order is load-bearing**: `HarnessMode.resolve` sorts `BIND` before `IDLE` before `WALK`, because a
freeze SKIPS `setupAnim` rather than undoing it, so a posed sweep ahead of a frozen one leaves every
bone it touched posed and the frozen sub-trees record that. `-Drefharness.modes=a,b,c` is how a run
names more than one mode; the single-mode booleans each name one and compose with it.

## Where the rest is written down

The rest is under `docs/harness/`, one topic a file; read the one that covers what you are touching
before you touch it. The renderer's side of every contract below is routed from
[asset-renderer/CLAUDE.md].

| Working on | Read |
|---|---|
| A run mode - full, glint, animated, walk, armour - and the output tree; a run that hangs with no output or halts with exit 4 | [Running](../docs/harness/Running.md) |
| The probes and the pack-tooltip run - `TexCoordProbeMixin`, `TexCoordProbeBlendMixin`, `SelectHarnessPacksMixin`, `PackSourceSweep`, `PackIconSweep` and `TooltipRenderUtilAccessor` | [Probes](../docs/harness/Probes.md) |
| `HarnessMode`, `api.Sweep` and `SweepRunner`, `PipTarget`, the pacing, the posed sub-trees | [SweepArchitecture](../docs/harness/SweepArchitecture.md) |
| `GuiFrameRenderer`, `MenuSweep`, `TooltipSweep`, the capture's three mixins, and capturing a GUI | [GuiCapture](../docs/harness/GuiCapture.md) |
| `BlockSweep`, `ItemSweep`, `GlintSweep`; the block-render mixins and when one may be deleted | [BlockPipeline](../docs/harness/BlockPipeline.md) |
| Writing or gating an entity pin or freeze mixin - the extraction call chain, the randomization sources | [EntityPins](../docs/harness/EntityPins.md) |
| What each existing entity pin, freeze and `*IdleMixin` does, and when one may be deleted | [PinCatalog](../docs/harness/PinCatalog.md) |
| `EntityBoundsWalker` and `DegenerateUvPolygonMixin`, the `[PX]` triangle dump, family-locked canvas sizing, the chirality flip | [BoundsAndCanvas](../docs/harness/BoundsAndCanvas.md) |

## Build / run

- JDK 25 (Loom toolchain; `JAVA_25` mixin compat), Gradle 9.4.1, Fabric Loom 1.16-SNAPSHOT, Fabric Loader 0.19.2, Fabric API 0.147.0+26.1.2, MC 26.1.2.
- Whole tree in one boot - **run this after any change to a frame renderer**: `./gradlew renderVanillaAllReferences` from asset-renderer, which passes `-PrefharnessModes=EVERY,ANIMATION,WALK`. The narrow modes stay for scoped iteration; relying on them is what left stale ground truth on disk twice. `renderVanillaReferences` - mode `FULL` - is one of them: it leaves `glint/`, `armor/`, `menus/`, `tooltips/`, `idle/` and `walk/` alone. Every mode, what each writes and what the whole-tree boot was measured at are [Running](../docs/harness/Running.md).
- World corruption from a hard JVM exit: `./gradlew resetRefharnessWorld`.
- **Nothing in this build is under a test** - it has `main` and `client` source sets and no test one, so a change here is caught by the compiler or by an actual run and by nothing in between.

## Adding a sweep

Adding a subject is one `Sweep` in `sweep/`, optionally one `FrameRenderer` in `frame/`, and one row in `HarnessMode` - **including the `EVERY` arm**, which is the one that renders the whole tree. A narrowing mode is named through `-PrefharnessModes`, which `build.gradle.kts` already forwards, so it needs no line of its own there. **A boolean property, where a mode has one, is the line whose omission silently runs `FULL`**: the `-P` never becomes the system property `HarnessConfig` reads, so the task named for one sub-tree re-renders every other one instead and reports success.

The `api.Sweep` contract it implements, the per-tick pacing, and why a frame renderer retires a
replaced texture rather than closing it are
[SweepArchitecture](../docs/harness/SweepArchitecture.md).

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
2. **Lighting alignment** - flip vanilla's `cardinalLighting()` N/S↔W/E swap to match asset-renderer's `FaceShade.of` (`FlipFaceShadingMixin`).
3. **Entity state / pose freezes** - pin transient entity state so renders are reproducible (the catalog is [PinCatalog](../docs/harness/PinCatalog.md)).
4. **Block-render fixes** - make block / glint renders match the in-world appearance + run deterministically (`FreezeSpriteAnimationMixin`, `ShadeFalseFullBrightMixin`, `BannerFlagModelMixin`, `GlintTexturingMixin`).

## Session-refresh checklist

1. Confirm baseline exists: `cd .. && ls cache/asset-renderer/vanilla/26.1/references/{blocks,entities,items,glint} | head`.
2. Re-render one target to check for regressions: `./gradlew renderVanillaReferences -PrefharnessTargets=minecraft:cow` from asset-renderer (entity) or `minecraft:chest` (BE block).
3. Glint iteration: `./gradlew renderVanillaGlintReferences [-PrefharnessTargets=minecraft:nether_star]` from asset-renderer.
4. Pose / chirality questions: the empirical answer is the pitch-roll sweep - `./gradlew renderVanillaPitchRollProbe -PrefharnessTargets=ns:id` from asset-renderer, or `-PrefharnessPitchRollSweep=true` from this directory (first filtered target rendered 576× over a 15° pitch × roll grid). It is its own task on the asset side now: riding `renderVanillaReferences` made a task named for references render none.
5. Asset-renderer-side parity work: see [asset-renderer/CLAUDE.md], whose table routes to the renderer's rules. Kit invariants and JOML factory conventions: [Geometry](../docs/renderer/Geometry.md). The texel fetch, the GPU's interpolation and reading the texture-coordinate probe: [TexelFetch](../docs/renderer/TexelFetch.md) and [TexCoordProbe](../docs/renderer/TexCoordProbe.md).

[asset-renderer]: ..
[asset-renderer/CLAUDE.md]: ../CLAUDE.md
