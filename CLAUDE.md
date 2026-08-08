# asset-renderer

Headless renderer for Minecraft blocks, items, entities, fluids and portals, outputting `ImageData`
(static PNG or animated frames) via `Renderer<O>`. Group `lib.minecraft`, root
`lib.minecraft.renderer.**`.

Gate questions go through the `parity-gate` skill, `.claude/skills/parity-gate/SKILL.md`.

## Build

- JDK 21 with the **Vector API incubator** (`--add-modules=jdk.incubator.vector`), wired into
  JavaCompile, Test, JavaExec and JMH in `build.gradle.kts`. Missing it anywhere is a class-not-found
  at load, never a silent fallback.
- ASM 9.8 reads Java 25 class files; the tooling flows walk client-jar bytecode with it.
- JitPack dependencies are `strictly()`-pinned inline in `build.gradle.kts`; bump by editing the
  version string. `./gradlew dependencies` for the live set.

## Gates

`./gradlew test` is the fast suite, excluding `@Tag("slow")`. `./gradlew slowTest` hits the network
and the filesystem cache and is never up-to-date-cached.

**Gate once per phase, immediately before the commit, and never re-baseline.** The `parity-gate`
skill runs it: `parityPlan` names what the change reaches and what is blind to it, `parityCapture`
writes a capture, `parityCompare` reports movers, `parityPromote` makes a capture the baseline.

- Determinism is the precondition for a hash. Prove a producer reproduces before comparing digests;
  `build/atlas/atlas.png` fails that by design.
- Look at a render for a change meant to move pixels; hash one for a change meant to move none.
- What a value reaches is provable: perturb it, re-render, and the outputs that move name the reach.
- Scope an already-red task's output to the package you touched and compare; never read its exit
  code.
- `BlockGeometryKitTest` and `FrameTurnTest` build fixtures by reflection into private
  parser-populated fields, so a rename compiles clean and fails at runtime.

Task inventories: `./gradlew tasks --group visual`, `--group tooling`, `--group parity`, `--group
build`. The last holds `generateAtlas`, a worked example of driving a renderer rather than a
resource-regenerator, which is why it is not in `tooling`.

## Tooling

ASM flows walk the extracted client jar and rewrite the shipped tables under
`src/main/resources/lib/minecraft/renderer/`, the one path `ToolingSession` holds. Re-run on an MC
version bump.

- `block_defaults.json` is read by `pipeline/loader/BlockDefaultsLoader`, not `BlockStateLoader`,
  which loads blockstate *variants* from vanilla JSON. It also applies the pack override at
  `renderer/block_defaults.json`, the only way a pack reaches an ASM-derived default state.
- `EntityIndexes` is session-lifetime and `EntityContext` per-subject; do not merge them. There is no
  writer class, and the only post-pass is `EntityGroupLinker.link`.
- `ToolingSession.envelope` builds both header segments from one `flow` local, so renaming a flow
  rewrites every table's header.
- Do not delete `tooling/policy/` for having no callers - `Navigation`'s javadoc is the only written
  statement of how generator hard-coding is sanctioned, and `PolicyPurityTest` reflects on a
  `provenance` field of every `*Policies` class, so they cannot share a superclass.
- **Every instruction walk in `tooling/` is an `AsmWalker` chain** - the one hand-written
  instruction loop left is `EntityGeometryRefResolver.collectBakedModelLayers`, whose body is
  bake-triple consumer accounting rather than a fold: a constructor consuming two or more models
  takes the last n fresh triples in argument order and always empties the fresh buffer, even when
  it held fewer than n, and there is no chain token for that. The walker is a reusable descriptor:
  sources `over`/`clinit`/`from`/`after`/`before`, geometry `real()`/`until`/`limit`, match stages
  that narrow, fold stages (`gather`/`latch` + `commitAt`) that replace the old `pending*` locals,
  and eager terminals; branch-following is `trace` with an always-on cycle guard; three of the four
  bytecode interpreters ride one `Interp<V>` chassis, and `GeometryParser`'s stack/slot half stays
  site-owned pending the same machine-view tokens. Do NOT reintroduce a
  `for (AbstractInsnNode ...)` loop - the engine's cascade rules (claiming, commit-before-reset,
  strict-adjacency) are pinned by the `tooling/walk` test suite, and a hand loop silently
  re-derives them. One-hop neighbour reads (`AsmWalker.nextReal`/`previousReal`) are expressions,
  not walks, and stay statics. `EntityGeometryRefResolverTest` reaches the declined member by
  reflection under its own name, so renaming it compiles clean and fails at runtime.

Every gate here reads the **shipped** JSON, which a generator refactor does not regenerate, so a
green gate is no evidence about a `tooling/` change. Compare emitted bytes A/B against a capture from
the clean tree before the first edit. A flow run dirties its own table - that is the signal, so
restore before the next measurement. Diff the diagnostics log too: a byte-identical table is not an
unchanged run.

## Skip these

- `cache/` - texture packs, render output, harness ground truth.
- `texturepacks/` - the same.
- `build/` - Gradle output.
- `.jmh/` - captured JMH session output.
- `notes/` - gitignored working notes: research packs, ledgers, probe tables. Read one when picking
  up a live effort; nothing downstream reads them.

Durable rules and decisions belong in this file. The measurements and narratives that produced them
belong in the commit that landed them, and in the reason recorded with the baseline they moved.

## The pack filter

`pack.mcmeta`'s `filter.block` is the only place a resource pack can **remove** something a lower
pack shipped.

- The pattern tests the **file path**, subdirectory and extension included, because
  `PathPackResources` relativises against `<pack>/assets/<ns>`. An id-keyed loader has no path to
  offer, so the erase lives at the file listing rather than the predicate.
- Namespace and path are answered **independently**, each an `anyMatch` over the whole list, and an
  absent pattern is constant-true: `[{"namespace":"x"},{"path":"y"}]` hides every file below.
  `IdentifierPattern.locationPredicate` is the per-entry conjunction, never called here.
- The client installs the filter once per pack at the resource manager, blind to what is listed, so
  it reaches every subtree and no loader is exempt. One `PackStack` serves both roots here where
  vanilla keeps two.
- `pipeline/pack/PackSubtree` is the one `(pack x root x namespace)` walk and the one filter site,
  yielding **files** in resolution order rather than a winner per id, because its callers disagree
  about what winning means. Do not simplify it to a winner map - that deletes `BlockStateLoader`'s
  malformed-file fallback.
- The walk takes its **root** as a value, as `PackType` does, so `data/` registries walk the same
  code. A `data/` namespace is **named, not enumerated**, because `PackAcquisition.namespaces`
  discovers namespaces from `assets/` alone and reusing that set is wrong in both directions.

Subtrees go through the walk; **point reads stay hand-written**, because a named file has nothing to
enumerate. `BlockRendererOverrides` is exempt by API - three fixed pack-root paths through
`getResource`, zero `entries()` calls, unreachable by a per-namespace `filter.block`.
`BlockModelLoader.reportShadowedIds` runs the enumeration backwards, probing `exists()` for a
supplied id set. Extract a diagnostic when two callers need it, not one.

## Sub-tick sampling

`bake` lives on `Timeline`, not `TickTimeline`, and reads each frame's instant off the millisecond
axis; the tick handed to a draw is that instant floored.

- `Timeline.gameTime` returns a `SubTickLoop` over the same span at the same speed. A step's delay is
  shared across its sub-steps, leftover ms to the earliest frames - 3 steps give `17/17/16` - so a
  tick still spans exactly 50 ms. `subTickSteps <= 1 || frameCount <= 1` returns the whole-tick
  schedule.
- `SubTickLoop` is deliberately not a `TickTimeline` - an off-lattice instant has no honest `tickAt`,
  and claiming one breaks the `millisAt(f) == tickAt(f) * 50.0` identity.
- A draw needing the fraction implements `RasterPass.ContinuousRasterizer`; everything else keeps
  `FrameRasterizer`, which keeps `Textures.*AtTick` on `int` - a fraction there is anti-parity.
- Use it only where appearance is a continuous function of time; a flipbook bakes duplicates, and the
  portal is the only user.

## Time-driven item icons

`minecraft:time` is a normalized **sun angle**, not a linear day fraction:
`data/minecraft/timeline/day.json` eases one `360 -> 0` pair anchored at noon over 24000 ticks with a
cubic Bezier `[0.362, 0.241, 0.638, 0.759]`, reproduced float-for-float by `option/SunAngle`. A
linear ramp is off by more than two clock faces at sunrise.

- Tick 0 is noon and yields exactly `+0.0f`; a `-0.0f` breaks `gui().atTick(0) == gui()` and costs
  every item `ItemRenderer.resolveRenderItem`'s baked fast path and its baked tints.
- `deriveTimeline` must walk **all** branches of the item's tree - the clock's dispatch sits behind a
  `context_dimension` select no offline context can evaluate.
- `context_dimension` is pinned to `ItemModelContext.DIMENSION_OVERWORLD`: the tree's fallback is the
  Nether/End branch whose `source` is `random`, so unevaluable resolves a different face per render.
  `source` is unparsed today; if it is modelled, this pin keeps the clock working.
- Uniform tick sampling covers 60 of 64 faces - the eased curve lingering near noon and midnight.

## JMH

`./gradlew jmh` with `-PjmhWarmup` (3), `-PjmhIters` (5), `-PjmhForks` (2), `-PjmhInclude=<regex>`
and `-PjmhProfilers=gc,stack`. Forks get `-Xmx2g` plus the Vector module. Benches live in
`src/jmh/java/lib/minecraft/renderer/bench/`.

## Parity: the harness contract

The [vanilla-reference-harness] is `harness/`, its own Gradle build with its own `gradlew`. It
renders every subject through the real Minecraft client at a locked iso pose; those PNGs are the
byte-stable ground truth the six sweeps diff against, one sub-tree each under
`cache/asset-renderer/vanilla/<mc>/references/`. Internals live in
[vanilla-reference-harness/CLAUDE.md]. The Java side walks vanilla model bytecode into
`entity_models.json` and `entity_geometry.json`.

- One repository means one sha, so provenance records `asset_sha` alone - a second would be equal to
  it by construction.
- The sweeps are diagnostic reports, not pass/fail gates; one becomes a gate only when its table is
  compared against a baseline.
- The player and armour sweeps rescale **both sides** before diffing, so their delta is a LOOK gauge.
  Their raw renders are the byte gate: `vanilla.png` / `java.png` are what the renderers produced,
  and `aligned_*.png` is the resample the delta, the diff and the panel come from.
- Re-rendering refreshes ground truth and does not fix a regression, and only
  `renderVanillaAllReferences` refreshes the whole tree.
- A reference that moves on a re-render with your change stashed was stale, not moved.

Capture, compare and promote go through the `parity-gate` skill; the re-render runbook is its
`references/procedures.md`.

## Geometry rules

### Faces, corner phase and unwrap

There is one `Face`; what varies per direction lives on `CornerPhase` and `Unwrap`, which vary
independently of each other and of the subject - the block-entity path takes `POLYGON` and
`ShieldKit`, an item path, takes `BAKERY`, both correct.

- `CornerPhase` fixes which corner a quad starts at, and with it the fan's diagonal and the UV slot
  paired with each vertex. Every `BAKERY` index array is a cyclic rotation of its `POLYGON`
  counterpart, never a reversal, so the two split on opposite diagonals and neither derives from the
  other; `BAKERY`'s pairing is the identity by construction.
- The fan is emitted in one place, `BlockGeometryKit.addQuad`; `FluidGeometryKit.addNonPlanarTop`
  cannot join, because a sloped top's four corners are not coplanar.
- The CTM grammar is a spelling, not a second direction vocabulary: `CtmRule.faces` is an
  `EnumSet<Face>` and the dialect stops at `CtmParser.parseFaceSet`.

### Frame turns

`face.Turn` is the order-8 diagonal group: every frame relation pairs a face with itself or its own
opposite, so each is `diag(+-1, +-1, +-1)` and a ninety-degree turn appears nowhere. Four elements
are in use - identity, `HALF_X` (model to upright frame), `MIRROR_Y` (shading flip), `MIRROR_X` (the
cube `mirror` flag's face swap).

- An `SO(3)`-only abstraction cannot express it: four relations are reflections, and a mirrored shell
  cube is `HALF_X.then(MIRROR_X)` rather than a ninth relation.
- The face map reads a face's axis off its normal and its opposite off one bit of its ordinal, which
  holds because `Face` declares its constants in opposing pairs - asserted, not assumed.
- Naming the relation does not make divergence unrepresentable; it buys one greppable token with a
  test pinning its value.
- Two turns can hide in one function - `ArmorKit.intoModelFrame` applies `HALF_X` to the geometry and
  then `MIRROR_Y` to the shading normal.

### Boxes and unwraps

- Every cube box is formed in vanilla's operand order and `Box.grown` is the one place it happens:
  the corners are `origin - grow` and `(origin + size) + grow`, as `ModelPart$Cube` forms them.
  `((origin - grow) + size) + grow + grow` is algebraically the same and arithmetically not.
- A bone's uniform `scale` multiplies each operand, never the assembled corners, so
  `BoneKit.scaledCubeBounds` scales origin, size and grow separately and `x * 1f == x` stays free.
- Derive each lattice endpoint from its own integer pixel offset, never `origin + extent * u`:
  `n * 0.03f` is one ULP low at `n = 12` and every limb is 12 px, so `HumanoidPart` stores `maxPx`
  rather than a span.
- `HumanoidPart`'s skin rectangles are `Unwrap.Atlas.rect` at the part's atlas origin under
  `Turn.HALF_X`, derived rather than tabulated, and a scope's extent and both layouts follow from the
  union of its parts' boxes, so `PlayerOptions.Type` owns them.
- The skull scope shares that arithmetic and never the constant or the predicate: `0.02f` and
  `OVERLAY_INFLATE` are two calibrations at two u/px scales, and unifying the gate deletes the hat
  layer on every legacy 64x32 skin.
- The 64x32 left-limb fallback is the production armour path, the armour atlas being 64x32, and it
  substitutes a different part's atlas origin, so no permutation of the part's own strip reaches it.
- `Unwrap.Atlas.crop` stays a cropped buffer: `buildBox` addresses each face's `W x H` buffer by the
  full `[0,1]` UV rect, so a sheet plus a rectangle is a sampler change, and the two part company on
  a mirrored cube where the crop reverses the row and a mirrored UV interpolates a descending `u`.
- `Unwrap.Atlas.rect` ignores its own `mirror` flag, because six entity-kit predicates ask what a
  face's own strip holds and the caller applies `Turn.MIRROR_X`; only `crop` applies both.

### JOML factories

The factory name orders the quaternion product; application to `v` is reversed, because
`q . v . q^-1` composes right to left. Vanilla uses both.

| Factory | Product | Applies to `v` | Site |
|---|---|---|---|
| `rotationZYX(z, y, x)` | `q_z . q_y . q_x` | X, then Y, then Z | `BoneKit.applyBoneRotation` |
| `rotationXYZ(x, y, z)` | `q_x . q_y . q_z` | Z, then Y, then X | `Camera.buildGuiDisplayTransform` |

## Pose, facing and canvas fit

Every iso subject shares `Projection.VANILLA_ISO` - `(30, 225, 0)` with `Lens.ISOMETRIC_BLOCK`,
vanilla's `display.gui` pose and scale. It is facing-neutral, presents the model's `-Z` side, and
`Projection` is its sole owner. `EntityGeometryKit.DEFAULT_ENTITY_LIGHTING` is the separate
`(210, 45, 0)` lighting frame, decoupled from the camera pose.

- Facing is per-renderer, a model-to-world `Placement` composed by `ModelEngine` as
  `pose . placement . modelSpin`: identity for block, fluid and portal, `R_Y(180)` for the player,
  `R_Z(180) = diag(-1, -1, 1)` for the entity, which also un-flips its Y-down model.
- `ModelEngine.rasterizeFitted` with `FitRequest` is the one fit path, serving player and entity;
  block, fluid and portal render a unit cube at fixed scale and never fit. Kits emit fit-neutral
  geometry, and scale and centring live only in `ModelEngine.prepareFit`, which forks on request mode
  and lens kind and measures through `ModelEngine.orient`, the exact render orientation.
- Orthographic bakes the scale in 3D, because the window-depth grid is a function of the canvas scale
  and is not scale-invariant; perspective and oblique carry a 2D post-projection fit over
  unit-normalized geometry.
- No canvas is odd-width, on either side. A symmetric subject's front corner lands exactly on the
  anchor - a pixel centre at odd width, a boundary at even - and the placement is the harness's own
  convention rather than vanilla's, which is what makes rounding it legitimate.

## Depth: the contract

- The `1/400` coverage snap (`ModelEngine.snapToCoverageGrid`) must never move depth:
  `depthOnUnsnappedPlane` re-reads each vertex's depth off its triangle's unsnapped plane at its
  snapped position, in `double`.
- `Projected.z0/z1/z2` is raster depth; `p0/p1/p2.z()` is the camera-space depth the translucent
  `quadDepthKey` sort reads. Do not collapse them.
- Depth is compared on vanilla's window grid: `ModelEngine.onVanillaDepthGrid` rounds each
  interpolated depth through `0.5f - depth * k`, `k = scale / (2 * VANILLA_DEPTH_RANGE)` at
  `VANILLA_DEPTH_RANGE = 1000`, where a `float` step beside `0.5` is `2^-24`.
- Round the interpolated depth, never the three vertex depths - rounding at the vertices tilts each
  triangle's plane by its own vertices' rounding.
- Depth comes off a per-triangle `float` plane, `z0 + dzdx*dx + dzdy*dy`, solved once from the snapped
  positions. A perspective lens keeps the barycentric form.
- There is no depth tolerance anywhere: `depthFails` is a bare `depthVal < existingDepth`, with no
  emissive slack, no coincident-overlay clearance inflate and no trim separation.
- A coplanar pair is last-drawn-wins, as `GL_LEQUAL` is, so `EntityModelData.getBones()`'s insertion
  order is the tied-depth priority - do not swap it for a hash map.
- The grid quantum is coarser than two `float` plane solutions of one plane differ by, so most
  coplanar contests fall to draw order, which is a ceiling and not a lever.
- On an additive pass a coplanar tie is a doubling rather than a tie-break: both fragments accumulate,
  as vanilla's do.
- `emissive`, `writesDepth` and `sorted` are three independent declarations carried as one
  `PassDeclaration`, and one entity can split them across its own two passes. A self-occluding pass
  needs the write and the sort together.
- The sort is by quad centroid, so a later quad still loses over the part of its area behind an
  earlier one, and `quadDepthKey` identifies the shared diagonal in camera space, never on screen.
- The fill rule is classified on the sign-normalized edge direction: `EdgeCoefficients.of` negates the
  coefficients when `denom < 0`, so with `e >= 0` marking the interior a left edge goes up and a top
  edge goes right. The mirrored reading is the bottom-right rule and hands a shared sample to the
  opposite face from the GPU.
- A fetch may not step outside the face's own UV rectangle; `ModelEngine.lastTexel` bounds it via
  `ceil(uMax * w) - 1`.
- The reference set's own depth range is part of the contract, and both harness frame renderers are at
  `1000`. A change emulating the reference's rounding cannot be evaluated against a coarser reference
  - fix the ground truth first.

## Armour

Vanilla never derives worn armour from the wearer's own mesh - it builds a few `ArmorModelSet`s and
hands each renderer the one its subject wears. There is no armour file; the shell rides the wearer's
`layers[]` row, `overlay.geometry` pointing into `entity_geometry.json` like any other mesh.

```json
{ "source": "HumanoidArmorLayer", "layer_index": 3, "id": "armor",
  "overlay": { "geometry": "HumanoidModel#createBaseArmorMesh",
               "grow": { "inner": 0.5, "outer": 1.0 } } }
```

- Being armoured is carrying a resolved shell: one `Optional<Shell>` with no classification flag, so
  a failed mesh walk fails loudly rather than falling back.
- The mesh is registered ungrown and unscaled; both layer deformations and any whole-mesh scale ride
  the row, and `ShellWalk.of` sums a cube's deformation with the row's at index time in the parser's
  operand order. `Shell.meshOffset()` derives the feet anchor as `24.016f * (1f - meshScale)`.
- `EntityIndexBuilder.humanoidArmorOf` joins the geometry raw - no hidden-bone strip, no part subset,
  no clearance bump.
- The shell is built two-sided by `SurfaceTraits.WORN_SHELL`, vanilla submitting it through a no-cull
  cutout pipeline. Armour geometry differs from block geometry by those two bits and not by a code
  path, so one `buildBox` serves block, item, player, cape and shell alike.
- `Shell`, `ArmorForm` and `ArmorSlot` own what varies by shell, by that shell's shape, and by slot
  alone. Do not forward one onto another.
- `ArmorSlot` declares LEGGINGS first, vanilla's innermost layer, and all three armour walks iterate
  slot-outermost so a later slot paints over an earlier one whatever the rectangles do.
- `onLayer` is generic because one of its three pairs is a `LayerType`, and naming it puts
  `asset.equipment` back on `option.spec`'s import list.
- `ArmorForm.playerSlots(part)` is `static` and ADULT-only, because half the corpus's bone names have
  no player body part and a parameterised accessor would drop a box silently.
- The helmet's second box is a peer row on both paths - the shell's `hat` cube, or a second
  `ShellPart.Body` over the part's overlay rectangle - and `keepsChildren()` is read at build.
- `ArmorKit.buildArmor3D` takes a `ShellPart` list, a `UnaryOperator<Box>` frame mapping and an
  `ArmorForm`; those three are the whole difference between a player and a worn shell, and the
  mapping is an argument rather than a branch because it alone is arithmetic.
- `ArmorForm.covers` and `ShellWalk`'s pivot chain are bounded by a visiting set, not a depth cap.
- A genuinely distinct second shell repeats the row's members under `overlay.alternate` with the
  `when` that selects it and the `form` it keeps; `ArmorMeshIndex.Set.sameShellAs` decides
  distinctness by construction, never by name. `Shell.forAppearance` evaluates that gate once in
  `Entity.resolve`, outside the age fork, so one slot serves two axes.
- A baby wears its own shell and nothing downstream branches on age; it draws `humanoid_baby` in all
  four slots and never a trim, and its pose is a mesh argument the geometry key names.
- A baby shell's `inner_body` cube is named by no slot and can never draw, and its feet are
  cross-parented onto the opposite legs. Both are vanilla's; normalising them edits shipped data.
- The canvas measures the shell on both sides - `ArmorKit.screenBounds` unions each equipped slot's
  alpha-tight bounds into the fit, and `ArmorKit.slotMesh` rebuilds the bone tree for
  `EntityGeometryKit.computeScreenBounds`. A reference canvas the same size armoured as bare is the
  symptom of an unmeasured shell, not evidence that vanilla clips.

## Entity model form

`entity_models.json` is the normalized model form: one entry per base entity under a top-level
`models` map, carrying `geometry_ref` and its overlays once.

- Axes are orthogonal dimensions, all option-encoded, resolved at render from `EntityAppearance`. A
  `size` axis's default is the option-less domain member taken last-first, so a one-option axis
  answers the larger form.
- The index is keyed by plain entity id and nothing synthesises a `minecraft:<id>_<option>` key. Do
  not revive id-encoding as a convenience API - the keyspace is the vanilla entity registry, so a
  synthesised key and a declared one are indistinguishable. `variant_of` is in-memory only.
- A `bones.toggles` entry's `default` is derived from the packed flag's polarity, not assumed false:
  `EntitySpawnFlagResolver` reads the accessor's mask and branch alongside the byte's registered
  default, taking both arms off the jump. Anything that does not match answers `false`.
- Every `<init>` feeds the field-to-bone map, not the first, because a model offering both a `(root)`
  and a `(root, Function)` form builds its parts in the wider one. A miss falls back to
  `StringUtil.toSnakeCase`; a raw Java field name is never a bone name.
- A `tint_by` axis decides what colour a dye draws as, through `TintAxis.resolve`, and is not a
  multiply by the selected dye. `WOOL` takes vanilla's three-quarter floor with WHITE replaced
  outright, and a non-identity axis is a `resolve` override rather than a branch in the renderer.
- `when: {tinted: true}` is a lossy transcription of a dye comparison, so `AppearanceGate.TintedGate`
  fires when the selection resolves to something other than the row's own baked tint, not whenever a
  dye is selected. The render path evaluates the gate; there is no second copy of it.
- An overlay's `blend` is a composition and `cutout` is the absence of one: `blendTokenOf` emits it
  when a resolved pipeline declares no blend function, and `parseBlend` maps it to
  `BlendMode.REPLACE`, which differs from `NORMAL` only at partial alpha.
- A block an entity holds is tinted at the no-world-context point and never at a biome, the same
  point a block-item icon resolves at, so one constant serves both - `Biome.INVENTORY_DEFAULT`.
- The carried-block path applies blockstate variant rotation and the icon path must not, because a
  carried block resolves a blockstate whose variant rotation is baked in.
  `EntityRenderer.buildBlockOverlayTriangles` appends it after the translate, so it applies first to
  the still-origin-centred cube, with both angles negated.
- A block drawn with no world position draws a fixed entry of its weighted variant list, seeded `42`,
  and `nextInt(size)` is the index because no shipped array carries a weight.
- That draw is resolved at index build - `BlockIndexBuilder.drawWithoutPosition` onto
  `Block.Variant.noPosition` - because a variants array is shipped data a pack can edit, where
  bytecode can be frozen into a table.
- A baby render draws the baby overlay form and skips block overlays. An overlay reaches a baby only
  when it declares a `baby` node, and a delta naming its own `geometry` does not inherit the row's
  `grow`, because the tooling already baked that mesh's deformation.
- `EntityModelLoader` is a thin orchestrator - two `document.as` reads handed to
  `EntityIndexBuilder.assemble`, which owns the geometry join, the mesh surgery, the axes pivot, the
  per-variant fold, the grouping and every leaf decode.

## Block icons

A block item's inventory icon is the item model its `minecraft:item_model` component names, baked at
the identity model rotation, so it carries no variant model, no variant rotation, no `uvlock` and no
multipart assembly.

- `Block#modelIcon` is the whole gate, true exactly when `ItemModelTreeLoader.deriveBlockItemModels`
  has an entry. `BlockRenderer.Isometric3D` then renders `Block#model()` with a null variant when the
  caller names no state; a named state gets the full blockstate treatment.
- A block with no entry has a flat sprite or a special renderer as its vanilla icon, so the 3D render
  is this pipeline's own stand-in at the default state's orientation.
- The harness applies the identical predicate to the same shipped `items/<name>.json`, deliberately
  not a runtime proxy, so the two repos cannot drift on which blocks are icons.
- A block entity does not stop a block from having an icon vanilla bakes from a block model.

## Porting a new entity

Always check whether the subject's renderer overrides `setupRotations` or carries a scale override,
and replicate it in the kit's transform chain.

- Only two `setupRotations` translates survive the harness rest pose; the rest sit behind gates the
  frozen animation state makes false.
- The squid's age-conditional Y shift is applied to the **mesh** at load
  (`EntityIndexBuilder.shiftModel`), because the renderer and the canvas-sizing bounds walk both read
  the mesh. The pufferfish's is an expression, so the walk declines it and it stays latent.
- A `setupRotations` shift is invisible unless the canvas is group-unioned, because the fit centres
  the bounds it was handed.
- `MeshTransformer.scaling(F)` is baked by the tooling and is exact - do not look for a runtime
  workaround. It rewrites only the `MeshDefinition` root, so it collapses to a root pivot and a root
  scale that rides the geometry key as `@scaled=F`.
- `PartPose.scaled(F)` scales the pivot as well as the three scale components where `withScale(F)`
  scales only the three; the asymmetry is vanilla's.
- `BabyMeshTransform` is the second whole-mesh transformer and is not a scale: it rewrites a pose per
  top-level bone through one of two operators chosen by name, and it translates **before** it scales.
- A vanilla field spelled `BABY_TRANSFORMER` is not always one.

## Debugging a mismatch

1. `git log --oneline master..HEAD` for branch state.
2. Look at the subject's panel: `cache/visual/<sweep>-parity-vanilla/<subject>/diff_panel.png`.
3. Re-run the one subject: `./gradlew entityParityVanilla -PentityId=minecraft:X -q`.
4. Go to pixels with `-Dasset.entity.pixel.dump=x0,y0,x1,y1` and `-Dasset.entity.bounds.dump=true`,
   then walk back from the write log to the texel, shade and blend that produced the mismatch. Every
   custom flag lives under `asset.*` and auto-forwards to each JavaExec and Test fork.
5. For a vanilla source lookup, `javap -c -p` the class under `cache/dragon-extract/`. A bridge
   method usually just calls the narrowed one.

- `RendererDebug.pixelWrite` logs a colour write and the **candidate** depth, never the stored one, so
  a `WRITE` line says nothing about what the buffer held; add a temporary probe when that is the
  question.
- Check the canvas width a dumped `idx` implies before comparing two lines - one subject's appearances
  have different canvases and therefore different depth arrays.
- Armour triangles carry a `debugTag` only when `-Dasset.entity.pixel.dump` is armed. Without it they
  log `tag=null` and `pixelTriangle` skips them entirely.
- Read a suspected tint fault per channel, never through luma - a hue error cancels in mean signed
  luma while mean absolute delta stays high.
- `java-only = 0` with `vanilla-only > 0` is a strict-subset silhouette, and reads as dropped faces.
- Two byte-identical references name one appearance, so an axis you added is not being selected.
- A `[PX]` dump agreeing with the reference to `0.001` px rules geometry out and points at coverage or
  the texel fetch.

## Decisions that stay closed

A refusal whose stated mechanism is not in the code is void - check it against source before
honouring one. Refused changes and accepted gaps share a shape and are listed together.

Renderer-wide:

- Do not wire `SpecialTransform` - a block entity's `presentation()` transform already carries that
  pose, so applying it again double-applies.
- Do not re-parent `ToolingException` under `RendererException` - `AtlasRenderer`'s skip-and-continue
  catches exist so one bad model never aborts a batch, and a missing client-jar class must abort it.
- Do not plan a light sweep on the `-Dasset.entity.L<idx>d{x,y,z}` knobs - they are inert downstream
  of `Lighting.resolveEntity` while `-Dasset.depth.range` moves the same rows.

Geometry:

- Do not give `Unwrap.Element` and `Unwrap.Atlas` one signature - `Element` reflects a position about
  16 and `Atlas` never receives one, so a merged method ignores an argument.
- Do not add a normalising overload to `Unwrap` - the divisor means three things, so one scale
  argument rescales every block-element UV on an HD pack.

Depth:

- Do not reorder bones to chase a tied-depth seam - bone order is the tooling's `addOrReplaceChild`
  order, not vanilla's `PartDefinition.children` bake order, and reproducing it was priced and
  refused.
- Do not add a depth tolerance - a depth buffer stores a quantised value and compares it exactly, so
  slack is a fitted constant.
- Do not replace the per-triangle depth plane with per-pixel barycentrics under an orthographic lens.
- Do not switch the tie to first-drawn-wins - vanilla's test is `GL_LEQUAL`.
- Do not take the lower texel at a face's lower UV bound - the corpus resolves that tie opposite ways
  on and off the canvas centre, and taking it reads into transparent sheet padding.
- Do not move the fit to a top-left origin to dissolve the centre-column contest - per-column
  coverage already agrees with vanilla, so moving the alignment shifts every row off its placement.
- Do not emit inflated degenerate plane faces - flatness is judged on the authored size while the
  corners come from the inflated bounds, and sub-pixel barycentric coverage does not match a GPU's
  edge functions.

Armour:

- Do not unify the two corner assemblies - a `ShellPart` row carries no bone-chain matrix where
  `EntityGeometryKit.computeScreenBounds` composes them, and the two frames sit a `HALF_X` apart.
- Do not fold `ArmorForm` into `Shell` - two shells of one shape share its part tables by reference,
  and the player's armour path holds no `Shell` at all.
- Do not invert the armour walks back to part-outermost - the six body-part rectangles happen to be
  disjoint and the contract must not rest on that.
- Do not give `SurfaceTraits` a second constructor - a four-argument form puts `boolean` in the same
  first three positions with two swapped, so a transposition compiles silently.

Entity:

- Do not size an entity canvas from the selected coat - vanilla's family-fit pre-pass builds a fresh
  render state whose variant is the enum's default.
- Do not model an unset villager level as "no badge" - `VillagerData` raises any level to one, so
  `VillagerProfession.drawsBadge()` and `isBaby` are what suppress one.
- Do not recover the armour frame from the wearer's `body` bone - it carries no rotation member, and
  a baby's body pivot is not a mesh transform.

## Developer scripts

Scripts live in `scripts/`, not bundled into the JAR. `scripts/parity/` is the parity toolkit, run as
`python scripts/parity <command>` and documented in its own `README.md`.
`scripts/euler_reference_svg.py` regenerates the SVG in `EulerRotation`'s javadoc.

[vanilla-reference-harness]: harness
[vanilla-reference-harness/CLAUDE.md]: harness/CLAUDE.md
