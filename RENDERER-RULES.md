# asset-renderer rules

The renderer's own domain rules: what the pipeline reads, what the kits emit, and what the raster
contract is. Load this when working on `src/main/java/lib/minecraft/renderer/**`; `CLAUDE.md` carries
the orientation and points here.

Every rule below is durable. The measurements that produced one belong in the commit that landed it,
and in the reason recorded with the baseline it moved.

## Options and the vocabulary they name

**Everything in `option/` is an `*Options` bag**, whether a renderer takes it whole or another bag
nests it: `OutputOptions`, `AnimationOptions`, `ArmorOptions`, `SkinOptions`, `TextureOptions`,
`DecorationOptions`, `AppearanceOptions`. `option/slot/` is the one sub-package, holding the
per-renderer `LayerSlot` enums a caller's `layerDecorator` splices against. `AtlasSidecar` and
`AtlasTile` are the exception and sit beside `AtlasOptions`, being what an atlas run hands back.

**What a bag names is not a bag.** The vanilla vocabulary a selection is drawn from is domain data
whichever side supplies it, and the pipeline reads it too, so it lives under `asset` and `option`
points down at it - never the reverse. `asset/appearance/` holds the entity axes (`Age`, `Size`,
`TintAxis`, `HorseMarking`, `IronGolemCrackiness`, `CopperWeathering`, `TropicalFishPattern`,
`Villager`) and `AppearanceGate`, the parsed `when` that tests a selection. `asset/equipment/` holds
the worn-armour vocabulary beside the shell walk that reads it. `asset/DyeColor` is the palette,
`asset/pack/item/ItemModelContext` the item-tree evaluation context. `asset/pack/rule/ItemContext`
is the older instance of the same shape and the precedent for all of them.

- A value type exactly one bag names **nests inside that bag** rather than sitting beside it -
  `MenuOptions.MenuSlotContent`, `GridOptions.GridTile`, `FluidOptions.CornerHeights`,
  `LayoutOptions.Layout`, `AnimationOptions.Schedule`.
- `AppearanceOptions` is the one caller bag whose readers are all asset-side: `Entity.resolve` and
  every `AppearanceGate` arm take one, so `asset -> option` survives there by design. That edge is
  known and open; do not "fix" it by moving the bag out of `option`.
- **A type moved between `option/**` and `asset/**` carries its own reach with it.** Both claims over
  those trees are `derived`, so each answers the reference graph for the changed FILE and where the
  file sits decides nothing. What the move owes is the regeneration: the claim on its new package
  derives a different trigger path, and `python parity/scripts/parity triggers` writes it.

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

## Texture flipbooks

A texture's `.mcmeta` animation resolves against the strip it plays over into one `Flipbook` - the
frame rectangle, the entry sequence with every deferred duration substituted, the cycle length and
the interpolate flag. `AnimationKit` owns the pixels alone: which entry a tick lands on, the crop and
the blend.

- **The table is pack state, so it is built at LOAD and never at generation.** The frame rectangle
  falls back to the strip's own width and the implicit entry count is the strip's height divided by
  it, so a pack swapping either the sidecar or the PNG swaps the table. It is memoised on `PackStack`
  on the same `(pack, id)` key the decoded pixels take.
- A strip holding no whole frame resolves to NO flipbook, which is what a caller renders as the strip
  unchanged - the two early-outs `resolveTextureAtTick` used to take per fetch.
- **`RendererContext.findFlipbook` is defaulted rather than forwarded**, joining `sampleBiomeTint`
  and `sampleRedstoneTint` as the lookups `Forwarding` deliberately leaves out. It resolves against
  `resolveTexture` and `findAnimation`, both already forwarded, and forwarding it would pair the
  delegate's frame rectangle with a wrapper's pixels - which for `AtlasRenderer`'s static context,
  whose `findAnimation` is pinned empty on purpose, re-animates the atlas.
- The default asks for the sidecar before the strip, so a texture that ships no animation decodes
  nothing.
- A block's own flipbooks ride `Block.flipbooks()`, resolved at index build over its model, its
  block-entity texture and every variant and multipart apply - over-inclusive across variants by
  design, which only ever lengthens the loop `Timeline.deriveTickStrip` folds them into. Nothing the
  store hashes renders that AUTO path, `blockFlipbook` being a LOOK driver whose output no manifest
  holds, so a change to it is measured by rendering its four blocks before and after and diffing the
  bytes.

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
  `FrameRasterizer`, which keeps `RendererContext.*AtTick` on `int` - a fraction there is anti-parity.
- Use it only where appearance is a continuous function of time; a flipbook bakes duplicates, and the
  portal is the only user.

## Time-driven item icons

`minecraft:time` is a normalized **sun angle**, not a linear day fraction:
`data/minecraft/timeline/day.json` eases one `360 -> 0` pair anchored at noon over 24000 ticks with a
cubic Bezier `[0.362, 0.241, 0.638, 0.759]`, reproduced float-for-float by `asset/pack/item/SunAngle`. A
linear ramp is off by more than two clock faces at sunrise.

- Tick 0 is noon and yields exactly `+0.0f`; a `-0.0f` breaks `gui().atTick(0) == gui()` and costs
  every item `ItemRenderer.resolveRenderItem`'s baked fast path and its baked tints.
- `deriveTimeline` must walk **all** branches of the item's tree - the clock's dispatch sits behind a
  `context_dimension` select no offline context can evaluate.
- `context_dimension` is pinned to `ItemModelContext.DIMENSION_OVERWORLD`: the tree's fallback is the
  Nether/End branch whose `source` is `random`, so unevaluable resolves a different face per render.
  `source` is unparsed today; if it is modelled, this pin keeps the clock working.
- Uniform tick sampling covers 60 of 64 faces - the eased curve lingering near noon and midnight.

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
opposite, so each is `diag(+-1, +-1, +-1)` and a ninety-degree turn appears nowhere. Five elements
are in use - `HALF_X` (model to upright frame), `MIRROR_Y` (the shading flip an entity's folded stack
is relit through), `MIRROR_Z` (the same relation for the player's upright boxes), `MIRROR_X` (the cube
`mirror` flag's face swap) and `INVERT` (the camera-facing flip both `EntityLighting.shade` and the
block-icon relight take). `NONE` is declared and named nowhere in production.

- `MIRROR_Y` and `MIRROR_Z` are one relation reached from two frames, and the group law is what says
  so: `HALF_X.then(MIRROR_Y) == MIRROR_Z`, by mask xor. So a list's turn is a property of the normal
  it stores rather than of the lighting entry, and the same list is owed a different member on either
  side of a frame change - which is why the turn and the list are one argument in practice and never
  independently chosen.

- An `SO(3)`-only abstraction cannot express it: four relations are reflections, and a mirrored shell
  cube is `HALF_X.then(MIRROR_X)` rather than a ninth relation.
- The face map reads a face's axis off its normal and its opposite off one bit of its ordinal, which
  holds because `Face` declares its constants in opposing pairs - asserted, not assumed.
- Naming the relation does not make divergence unrepresentable; it buys one greppable token with a
  test pinning its value.
- A frame change and a shading flip are two turns, and separating them is what keeps each one
  greppable: `EntityArmorKit.intoModelFrame` applies `HALF_X` to a shell's geometry and stored normal, and
  the `MIRROR_Y` that lights it is the fold's, one argument at one call.

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

**An entity and a player are each lit once, after their layers are folded.** Vanilla binds
`Lighting.ENTITY_IN_UI` once per GUI entity draw before any layer is submitted, so a wearer, its
overlays, its carried block, its wings and everything it wears light under one entry. Both renderers
do exactly that - `EntityRenderer` over the entity's folded stack through `Turn.MIRROR_Y`,
`PlayerRenderer` over the player's through `Turn.MIRROR_Z`. Block, fluid and portal are not in this
rule: their kits bake `Lighting.inventory` at emit time and nothing relights them.

- **The fold owns the entity shade; no entity-side producer resolves one.** `EntityGeometryKit`,
  `EntityArmorKit.intoModelFrame` and `EntityRenderer.buildBlockOverlayTriangles` all emit
  `Shading.UNLIT`.
  A player-side producer may still carry the `BlockGeometryKit.buildBox` cardinal bake, because the
  player's relight overwrites it either way - so `UNLIT` marks the entity path's producers, not every
  triangle either fold receives.
- The pass reads a triangle's **stored normal and its emitted traits**, so a producer that re-frames
  geometry after building it must turn the stored normal with it. `EntityArmorKit.intoModelFrame` is that
  turn for a worn shell, `ElytraKit.buildPlayerWings3D` for the player's wings.
- Those traits are read for lighting as well as for coverage: `cullBackFaces` picks the per-face
  orientation and `directionalLight` gates the full-bright arm. So a producer rewriting either -
  `EntityRenderer.buildBlockOverlayTriangles` rewrites both, for the `red_mushroom` speckle - decides
  that geometry's lighting at a distance from the pass that applies it.
- **A `1.0f` shade names nothing on its own.** It is `Shading.UNLIT`, the value a relight answers for
  a face declaring no directional light, the value the Lambertian saturates to, and what
  `Lighting.inventory` bakes onto every UP face. A missing relight is diagnosed from the producer, not
  from the scalar.

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
  `quadCamDepthKey` sort reads. Do not collapse them.
- Depth is compared on vanilla's window grid: `DepthMath.onVanillaDepthGrid` rounds each
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
  earlier one, and `quadCamDepthKey` identifies the shared diagonal in camera space, never on screen.
- The fill rule is classified on the sign-normalized edge direction: `EdgeCoefficients.of` negates the
  coefficients when `denom < 0`, so with `e >= 0` marking the interior a left edge goes up and a top
  edge goes right. The mirrored reading is the bottom-right rule and hands a shared sample to the
  opposite face from the GPU.
- A fetch may not step outside the face's own UV rectangle; `ModelEngine.lastTexel` bounds it via
  `ceil(uMax * w) - 1`. **A scrolled pass is the exception, and it is told apart by the PASS rather
  than by the coordinate**: `PassDeclaration.wrapsTexture` turns the bound off and wraps into the
  sheet instead. Inferring it from "the coordinate ran past `1`" is what does not work - a block's
  own geometry does that, the decorated pot's sherds and one water flow frame authoring a rectangle
  whose upper corner rounds a texel beyond, and wrapping those reads from the opposite edge for
  `0.7233` of block delta over the pot alone. The wrap is worth 5.34 of the breeze's delta where it
  does belong; a clamp at the sheet's edge smears its wind's last column instead.
- The reference set's own depth range is part of the contract, and every harness `FrameRenderer` is
  at `1000` - the depth-quantum probe, which drives two ranges and refreshes no reference, is not one.
  A change emulating the reference's rounding cannot be evaluated against a coarser reference - fix
  the ground truth first.

## Armour

Vanilla never derives worn armour from the wearer's own mesh - it builds a few `ArmorModelSet`s and
hands each renderer the one its subject wears. There is no armour file; the shell rides the wearer's
own `armor` node, its `geometry` pointing into `entity_geometry.json` like any other mesh.

```json
"armor": { "source": "HumanoidArmorLayer", "layer_index": 3,
           "geometry": "HumanoidModel#createBaseArmorMesh",
           "grow": { "inner": 0.5, "outer": 1.0 } }
```

- Being armoured is carrying a resolved shell: one `Optional<Shell>` with no classification flag, so
  a failed mesh walk fails loudly rather than falling back.
- The mesh is registered ungrown and unscaled; both layer deformations and any whole-mesh scale ride
  the row, and `ShellWalk.of` sums a cube's deformation with the row's at index time in the parser's
  operand order. `Shell.meshOffset()` derives the feet anchor as `24.016f * (1f - meshScale)`.
- `EntityIndexBuilder.humanoidArmorOf` joins the shell's own mesh - what a wearer rests without, the
  subset a pass of its is restricted to and the deformation one surrounds it with are derived onto
  meshes the wearer names, and an armour row names none of them.
- The shell is built two-sided by `SurfaceTraits.WORN_SHELL`, vanilla submitting it through a no-cull
  cutout pipeline. Armour geometry differs from block geometry by those two bits and not by a code
  path, so one `buildBox` serves block, item, player, cape and shell alike.
- `Shell`, `ArmorForm` and `ArmorSlot` own what varies by shell, by that shell's shape, and by slot
  alone. Do not forward one onto another.
- `ArmorSlot` declares LEGGINGS first, vanilla's innermost layer, and all three armour walks iterate
  slot-outermost so a later slot paints over an earlier one whatever the rectangles do.
- `onLayer` is generic because its three call sites hand it two different types - a `LayerType` pair
  at `ArmorForm.layerType`, a `Vector3f` deformation pair at `ShellPart.box` and at
  `ArmorKit.buildArmor3D` - so no concrete signature serves all three.
- `ArmorForm.playerSlots(part)` is `static` and ADULT-only, because half the corpus's bone names have
  no player body part and a parameterised accessor would drop a box silently.
- The helmet's second box is a peer row on both paths - the shell's `hat` cube, or a second
  `ShellPart.Body` over the part's overlay rectangle - and `keepsChildren()` is read at build.
- `ArmorKit.buildArmor3D` takes a `ShellPart` list, a `UnaryOperator<Box>` frame mapping and an
  `ArmorForm`; those three are the whole difference between a player and a worn shell, and the
  mapping is an argument rather than a branch because it alone is arithmetic.
- **The two wearers are two kits, as the geometry kits are.** `EntityArmorKit` starts from the
  `Shell` an entity is dressed in; `PlayerArmorKit` holds no `Shell` at all and dresses the player's
  own body boxes; `ArmorKit` is what they share, from `buildArmor3D` down. Keeping them in one type
  is what let a shared class carry both subjects at once, and reach is resolved per CLASS - so a
  player producer read the whole entity appearance surface through the half of the kit it never
  enters. A kit that renders for one subject says so by being that subject's kit.
- `ArmorForm.covers` and `ShellWalk`'s pivot chain are bounded by a visiting set, not a depth cap.
- A genuinely distinct second shell repeats the node's members under `alternate` with the
  `when` that selects it and the `form` it keeps; `ArmorMeshIndex.Set.sameShellAs` decides
  distinctness by construction, never by name. `Shell.forAppearance` evaluates that gate once in
  `Entity.resolve`, outside the age fork, so one slot serves two axes.
- A baby wears its own shell and nothing downstream branches on age; it draws `humanoid_baby` in all
  four slots and never a trim, and its pose is a mesh argument the geometry key names.
- A baby shell's `inner_body` cube is named by no slot and can never draw, and its feet are
  cross-parented onto the opposite legs. Both are vanilla's; normalising them edits shipped data.
- The canvas measures the shell on both sides - `EntityArmorKit.screenBounds` unions each equipped slot's
  alpha-tight bounds into the fit, and `EntityArmorKit.slotMesh` rebuilds the bone tree for
  `EntityGeometryKit.computeScreenBounds`. A reference canvas the same size armoured as bare is the
  symptom of an unmeasured shell, not evidence that vanilla clips.

## Entity model form

`entity_models.json` is the normalized model form: one entry per base entity under a top-level
`models` map, carrying its `renderer`, its `axes` and its overlay rows once. How the generators
derive each member is [tooling/CLAUDE.md]'s; this is what the loader reads.

- **Geometry is named per option, never on the entry.** The member is `geometry`, and it sits at
  `axes.<axis>.options.<option>.geometry` and on each overlay row - there is no `geometry_ref`
  anywhere in the shipped bytes. Its value is a **factory coordinate**,
  `AllayModel#createBodyLayer`, resolved against `entity_geometry.json`, and the `@grow=`,
  `@scaled=`, `@fparam=` and `@baby=` suffixes name a derivation of that factory's mesh rather than
  a second factory.
- Axes are orthogonal dimensions, all option-encoded, resolved at render from `AppearanceOptions`. A
  `size` axis's default is the option-less domain member taken last-first, so a one-option axis
  answers the larger form.
- The index is keyed by plain entity id and nothing synthesises a `minecraft:<id>_<option>` key. Do
  not revive id-encoding as a convenience API - the keyspace is the vanilla entity registry, so a
  synthesised key and a declared one are indistinguishable. `variant_of` is in-memory only.
- **A bone says whether it draws and what flips it, and never which way a toggle points.** The side
  each rests on is the bone's own `visible` on the mesh that renders - a bone naming a toggle and
  resting hidden is one that toggle draws - so nothing declares it twice. A bone that rests undrawn
  and names no toggle can never draw, so the tooling drops it and no member has to say so. What a
  subject rests without is per SUBJECT because the two halves key apart: a renderer can re-enable a
  bone its model class never draws, and the illusioner does, which is why the four illagers split
  one `IllagerModel` mesh into one per rest state rather than sharing it.
- **A bone that rests undrawn takes its subtree with it.** Vanilla's `visible = false` skips the part
  and everything under it; removing the name alone re-parents each orphan to the root, so geometry
  that should have vanished lands somewhere the subject is not - four sprigs of coral floating clear
  of a zombie nautilus.
- **`rest` says which constant each enum render-state field holds before anything happens**, per
  entity, because that is the subject's own fact and not its model's: one `IllagerModel` serves every
  illager and only the pillager hangs its arms. Answering no constant at all is a state no enum is
  in, and it drew three illagers with the wrong pair of arms.
- **A question about a reference nobody supplied rests at nothing, and `isEmpty` is the exception**:
  a stack nothing was put in is empty, so `PoseFold` answers it true where it resolves one. Answering
  nothing says the opposite, and undressed a zombie nautilus of the corals it wears unarmoured.
- **An equipment layer carries a `bones` node of its own, and `pose` is what names the class it is
  read against.** A layer poses its mesh with the model class the renderer hands it, which is not
  always the class that baked the mesh: every equine saddle is posed by `EquineSaddleModel` while a
  donkey's is baked by `DonkeyModel#createSaddleLayer`. The geometry coordinate's head names that
  class everywhere else, so `pose` is written only where the two disagree - the donkey's and the
  mule's saddle rows, and nothing else in the corpus. Reading the baking class instead answers the
  wearer's `chest` gate for a mesh whose gated bones are reins.
- **A layer's toggles take the same selection the wearer's do.** One flip serves both, so an equipped
  saddle draws its reins for a `ridden` subject and its chest panniers for a `chest` one, and the
  layer's mesh takes the resting strip the body's already did.
- A bone name is never a raw Java field name; a miss falls back to `StringUtil.toSnakeCase`.
- **A `texture_by` axis answers for itself on an overlay pass, and the horse marking is one.** Its row
  draws the wearer's own mesh - `geometry` equal to the body's coordinate, which is what routes the
  body's pose to the pass and derives its bounds skip - and `Entity.OverlayLayer.textureFor` reads the
  selection off `HorseMarking`, whose two columns mirror the adult and baby sheets vanilla binds each
  marking to as a record pair. It needs no gate of its own: an axis-carrying row whose ref resolves
  empty is already skipped, and the axis answers empty at `NONE`.
- A `tint_by` axis decides what colour a dye draws as, through `TintAxis.resolve`, and is not a
  multiply by the selected dye. `WOOL` takes vanilla's three-quarter floor with WHITE replaced
  outright, and a non-identity axis is a `resolve` override rather than a branch in the renderer.
- `when: {tinted: true}` is a lossy transcription of a dye comparison, so `AppearanceGate.TintedGate`
  fires when the selection resolves to something other than the row's own baked tint, not whenever a
  dye is selected. The render path evaluates the gate; there is no second copy of it.
- An overlay's `blend` is a composition and `cutout` is the absence of one: `blendTokenOf` emits it
  when a resolved pipeline declares no blend function, and `parseBlend` maps it to
  `BlendMode.REPLACE`, which differs from `NORMAL` only at partial alpha.
- **An overlay's `texture_scroll` moves where the pass SAMPLES and never where it stands.** Vanilla
  builds the offset into the texture matrix of the render type the layer submits through, so it is a
  property of the layer rather than of any mesh it draws, and the breeze's wind holds one silhouette
  across every frame while turning. It is carried as a per-tick RATE because the corpus's three sites
  are one shape - `(ageInTicks * k) % 1` on each axis - and the wrap is taken where vanilla takes it,
  into the argument, rather than at the fetch: the two part company once the authored coordinate and
  the offset are on different whole turns, which is exactly at the sheet's seam. A charged wither's
  swirl is a stated gap - its offset is a swing rather than a scroll, so the generator refuses it -
  and nothing renders it, the animated corpus drawing it uncharged.
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

## Posing an entity at a tick

`EntityOptions.PoseMode` chooses between the mesh as authored and the mesh its model puts it in, and
`engine/kit/PoseKit.posed` is the one place that answers. It nests on `EntityOptions` beside
`FitMode` because exactly one bag names it. How `entity_poses.json` is derived is
[tooling/CLAUDE.md]'s; this is what the runtime does with it.

**The roster is `BIND` / `IDLE` / `WALK`, and what separates the two moving presets is which figures
stop answering their resting value.** A pose is a function of what the caller says about the subject,
so naming a gait is naming the further figures that stop resting - it is not a second mechanism, and
every preset goes through `PoseKit` the same way. `IDLE` stops resting elapsed time alone. `WALK`
adds the two a stride is carried on, and they are one schedule rather than two inputs: vanilla
accumulates the phase BY the amplitude once a tick rather than deriving it from the clock, so the
phase is the tick times the amplitude and a caller setting one without the other has described no
gait. The amplitude is the full one, vanilla clamping what it accumulates to one. The vocabulary is
a caller's coinage rather than vanilla's - vanilla carries `walkAnimationPos` and
`walkAnimationSpeed` and no `WALK` constant - so it stays in `option/` and moves no store reach.
**`WALK` has no vanilla reference**: the animated set was drawn from a never-ticked subject, which
walks at no speed, so it renders and is not gated until the harness drives a stride too.

**`BIND` is the default and hands back the very instance it was given.** Identity, not equality: an
equal copy is still a copy, and every float in it is one the authored path never computed. The same
answer serves a subject whose model poses nothing, one whose pose could not be read, and one writing
only channels a mesh does not carry - so the authored path allocates nothing and rounds nothing, and
that is the whole of why the runtime landed at zero movers. Anything that makes the default return a
rebuilt mesh has broken the contract whether or not the bytes move that day.

**A subject is more than one posed mesh, and the class that poses one is not the class that baked
it.** A geometry coordinate is headed with the class that baked the mesh, and a model reusing its
parent's layer bakes none - a zombie's mesh is `HumanoidModel#createMesh` while the renderer hands it
a `ZombieModel`. Keying the pose off the coordinate poses it as a plain humanoid and loses the
arms-out stance every zombie stands in. `bones.pose` names the poser, written only where the two
disagree.

- **`bones.pose` names a pose KEY, which is a class name until one class poses more than one way.**
  A body reached at two resting frames is split into a row each and each body names the one it takes,
  spelled `Class@member=CONSTANT` over the members the frames disagree on - the four illagers are the
  corpus's one split, on `armPose`. `EntityIndexBuilder.poseOf` splits a coordinate at its first `#`
  and keys on what is left, so a suffix arrives verbatim and the reader needs nothing for it. **A key
  the table does not carry is SILENT** - `poses.getOrDefault(key, EntityPose.NONE)` and `NONE`'s
  refusal is empty, so `isReadable()` answers true and the mesh draws unposed and unstripped - so the
  emitter refuses a declared poser with no row rather than leaving it to be noticed in a render.
- **Every overlay pass poses its own mesh with its own class**, so `Entity.OverlayLayer` carries a
  pose and `PoseKit.posedSubject` poses the body and every pass together. Posing the body alone
  leaves a sheep's wool where the sheep no longer is. A pass drawing the body's own mesh takes the
  body's pose rather than its coordinate's, or the two part company on a subject that moves.
- The insertion is one line at the top of `EntityRenderer.renderEntity`'s `buildAtTick` lambda - the
  posed *definition* goes into `FeatureContext`, so a feature reading `ctx.definition()` gets posed
  passes without a gate of its own, and the collar redraws the posed body.
- The rebuild preserves the mesh's **own bone order**, that order being the tied-depth priority. A
  `LinkedHashMap`, never `Map.copyOf`, whose iteration is salted per JVM launch.
- Composition is channel-wise on the Euler triplet `BoneKit.applyBoneRotation` feeds to
  `Quaternionf.rotationZYX`. **Do not pre-compose a rotation as a matrix** - it reaches that call
  through different arithmetic and parts from the authored pose at a delta of zero.
- **A channel written back to what the mesh already held keeps the mesh's own degrees.** The table is
  radians throughout and a bone stores degrees, and `toDegrees(toRadians(d))` does not return `d` for
  about one float in fifty thousand - `31f` is one - so converting unconditionally walks every bone a
  bind-resolving pose touches by an ulp a render.
- Three scale axes fold onto the one a bone holds and a divergence is refused. `HappyGhastModel`
  writes one expression to all three, so the fold is exact rather than a rounding to accept.
- **The container enters as a synthetic cubeless bone** named `$container`, every top-level bone
  re-parented onto it, which reuses the chain composition instead of needing quaternion-to-Euler
  algebra a rotation above the roots would otherwise want. Top-level is read the way `BoneKit` reads
  it, which is wider than a null parent: **a bone naming a parent its mesh does not declare hangs
  from the root too**, and the breeze ships two of them.

**What a subject's RENDERER composes goes above every mesh it submits, and it is its own table keyed
by the renderer.** Vanilla runs `setupRotations` on the pose stack before it submits the body or any
layer, so `entity_poses.json`'s `renderers` member holds a step sequence per renderer and
`PoseKit.posedSubject` puts it at the FRONT of every mesh's container - the body's, each overlay
pass's, and the alternate a suppressed pass carries. A transform reaching the body alone swims a
tropical fish out from under its own pattern overlays. It is keyed by renderer rather than by model
because one renderer answers for several model classes, and `entity_models.json`'s `renderer` member
is what a subject joins on.

- **A world transform crosses a frame to reach the container.** `setupRotations` runs OUTSIDE
  vanilla's own `scale(-1, -1, 1)` where a container sits inside it, so a step is
  `M . <world step> . M` at `M = diag(-1, -1, 1)`: x and y negate and z is kept, for a translate and
  for an Euler angle alike, `M` being a half turn about z rather than a reflection. A translate also
  crosses units, a `PoseStack` moving in BLOCKS where a pivot is model pixels. Measured rather than
  read off the algebra: the cod's yaw step took it 28.17 to 0.17 and the same step un-negated gives
  57.56.
- **The base delegation emits nothing and is still required.** At the frozen pose
  `LivingEntityRenderer.setupRotations` is exactly one turn about y, which this renderer applies as
  the subject's facing, so following it would apply it twice. A body that never runs it composes
  around a different base and is refused rather than read against this one. A step emitted BEFORE the
  delegation has to be about y alone, which is what makes its position in the sequence immaterial: a
  turn about y and a translate along y both commute with the base's turn and nothing else does.
- **A leading constant turn about y is facing, not container.** It is the addend a renderer folds
  into the delegation's own body rotation - the shulker's `+ 180f` - and the base applies the body
  rotation as the subject's facing, so the index consumes it into `Entity.setupYawAddend`, which
  reaches every render mode through the facing sum where a container step never reaches BIND.
- **A shift and a transform are two spellings of one `setupRotations` and only one may answer.**
  The shift is baked into the mesh because the bounds walk reads the mesh; the transform composes
  above it at render, and both together move the subject twice. `EntityMeshShift` refuses a subject
  carrying both, where the shift is still known and the renderer's steps are already walked. Every
  renderer the shift claims is one the walk declines - the squid's turn is by a method parameter the
  grammar has no term for - which the guard keeps a fact rather than a coincidence.

**A bone the MESH does not declare is not evaluated.** A pose belongs to a model class where a mesh
belongs to a subject, so the two part company wherever a bone rests undrawn and took its subtree with
it: an illager resting with its arms crossed has no arm to hang, and vanilla's own `setupAnim` writes
those fields on parts nothing renders. `PoseEvaluator.evaluate` passes them over - without that it
throws on all four crossed-arm illagers and the armour stand. It still throws where the disagreement
is real: a bone the mesh does have, reading one it does not.

**A shipped pose names no figure the frame does not answer, and the frame answers a declared
roster.** `PoseKit.frameAt` is the one place a figure is answered, and outside that roster everything
rests: elapsed age is the tick, and a field nobody drives reads zero. Everything a subject standing
still says about itself is resolved where the table is written - which constant an enum member holds,
what a question of a reference the state holds rests at, what a figure its own render state builds it
at - so there is nothing a caller can leave out and be wrong about. That is what
`PoseEvaluator.AT_REST` is, and it is why the evaluator's arms are a literal, a figure, a bone read,
an operation and a choice, and a choice turns on a numeric comparison alone.

**The roster is elapsed age, the stride pair, and what a never-ticked subject leaves at zero.** The
last of those is the one that grows, and it grows on both sides at once: `asset/pose/IdleFigure` is a
scalar its own vanilla arithmetic bounds, moved from what it RESTS at to what it reaches across one
strip; `asset/pose/IdleState` is a one-hot over a selector, whose selected member's field answers one
while every other answers zero. They are two types rather than one interface with two arms - a figure
is a function of the tick and carries no notion of a selection, a state is the reverse - and what
they share is only that the frame resolves both by render-state field name.

- **A figure's excursion runs from rest, so tick zero is free.** Frame 0 of a strip, every authored
  render and every frozen reference answer exactly what they answered before the figure was driven.
  **A selection has no such property** and does not need one: `BIND` hands back the mesh it was given
  and every still sweep renders there, so the animated sweep is the only gate a selection reaches.
- **A boolean render-state field is a figure of this kind, not a flag.** A flag is a bone's
  visibility, which folds to a literal at generation; a boolean the walk keeps symbolic arrives as a
  number wherever the body reads it, and a body that branches on one leaves a select comparing that
  number against zero. So a dolphin's `isMoving` is one arm of a two-member selection, and adding it
  to the generator's driven set is the whole of what keeping it symbolic takes.
- **A member that drives no field is spelled with the empty token**, which is how a caller asks for a
  whole group to rest, and it is not a key: no render-state field is spelled that way. Every member
  that names a field names one no other member does, and `IdleFigureMirrorTest` pins that - a shared
  name would make the lookup a first match rather than an answer.
- **Both sides answer from the same numbers and neither can name the other's type.** The harness
  declares its own copy in `IdleFigures` and `IdleFigureMirrorTest` compares the two as text, because
  a value that moved on one side only renders happily and reports as a defect in this renderer.
`EntityPoseLoadTest` pins it, because the failure mode is a silent zero: the arm a switch ends at is
not the arm a subject stands in, and it cost the skeleton family a forty-four degree forward swing at
rest before the fold reached it. **`entity_poses.json` still carries `input_defaults`,
`rest_defaults` and `question_defaults`, and nothing reads them** - they are what the generator
resolved against, kept in the table until the emitter stops writing them.

**Nothing at render reads a flag channel.** Every flag in the corpus folds to a literal at
generation, so which bones a subject rests without is written onto the mesh it rests in - a bone
nothing can draw is gone from that mesh, and a bone a selection can draw rests `visible: false`
naming what flips it - and nothing strips a mesh at load; the loader passes the two channel tokens
over. It is why the guardian's eye and the fox's legs need neither a harness pin nor an asset answer:
vanilla writes the eye `true` unconditionally, and calls the fox's `setWalkingPose` - which sets all
four legs `true` - before any branch, leaving only `setSleepingPose` behind an `isSleeping` that
rests false.

**The ground truth for a posed subject is `animation/`, and it is a second reference set rather than
a replacement.** `entityAnimationParityVanilla` renders each subject at `ANIMATED` over the same
schedule the harness stepped - `EntityAnimationSweep`'s `START_TICK`, `FRAME_COUNT` and
`TICKS_PER_FRAME` are pinned on both sides - and diffs frame by frame. It is the only gate that can
see the pose table at all: everything else compares the mesh as authored, where both sides freeze and
agree by construction. Two thirds of the corpus lands under `0.25` there, which is the still sweep's
own bar; what the rest names is listed by the sweep and is a divergence per subject rather than a
property of the mechanism.

**The canvas is measured across every frame the schedule samples**, each through its own posed mesh
and its own tick's texture, unioned by `EntityRenderer.computeScreenBoundsAcrossFrames`. It wraps the
scope dispatch from OUTSIDE, so the group union and the frame union compose rather than one swallowing
the other. A one-frame schedule measures the one frame it draws and draws the geometry it already
built for the empty-canvas early-out; a schedule with frames to spare builds each of them, nothing
about a posed tick being carryable to its neighbour.

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

## The block index and its first variant

**`BlockIndexBuilder.resolveBlockStateModel` resolves a block through whichever apply its variant map
yields FIRST, so the hash order of that map is a rendered value rather than an implementation
detail.** The pick settles two things at once: the `model` and `textures` the index entry carries, and
whether that entry survives the `rendersNothing` filter at all.

- **The two maps that feed it are filled by iteration and put, never collected.**
  `BlockStateLoader.cleanVariants` and `BlockIndexBuilder.bakeVariants` each build a `HashMap` and put
  into it. A collector fills its table on its own terms, so substituting one re-rolls the order and
  resolves a different variant in silence - with the suite green, every sweep sum held, and the
  shipped tables byte-identical, since none of those reads this map.
- **A non-empty `default_state_key` is no protection.** Every door declares one and every door is
  still resolved this way. The default state governs what a named-state render draws; the
  first-variant pick governs the entry itself, and the two are independent.
- What moving the order costs, in the shapes it has already taken: a door resolves to its upper half
  instead of its lower, and the pitcher crop resolves to an apply that renders nothing, which drops
  the block out of the index entirely rather than drawing it wrongly. A waxed copper lantern, which
  names no default state at all, resolves hanging instead of standing.
- Nothing downstream recovers the intended variant, so this order is a contract. A pass that means to
  iterate either map differently moves rows and owes a promote.

## Menus

One arithmetic and one painter. `MenuScreen` is where a shipped container puts its cells, `MenuLayout`
is the arithmetic between that and a `Window`, and `MenuRenderer` places content on what the layout
produced - so what a caller chooses is which screen the client ships, what goes in its cells and what
paints its chrome, and none of the three is a render path of its own. Everything is in Minecraft
pixels and reaches output pixels only through `MinecraftFont.MC_PIXEL_SCALE`, which is what keeps the
chrome exact rather than resampled.

- **A screen's declared height is read off its own construction, never off how it is blitted.** A
  chest and a shulker box each declare a pixel they never draw and reach it by different routes: the
  chest's is the source row its second blit skips, the shulker box's is a whole blit of art one
  shorter than the `(176, 167)` it constructs itself with. Both position the player's label from the
  declared height, so a slack inferred from the blit count puts one of them a pixel high. The hopper,
  dispenser and crafting table declare exactly what they draw.
- **The canvas is the drawn height, never the declared one.** `ContainerScreen` composes a chest from
  two blits totalling `rows*18 + 113` against a declared `rows*18 + 114`, blit B reading source
  `v=126` while the container half ends at row 124, so the declared box's bottom scanline is never
  painted and everything below the container rows sits a pixel higher than the sheet.
- The frame is four 4x4 corner blocks and four bars of a **1 mcPx period**, and nothing about it is
  per-menu - one frame reproduces the 4 px ring of every shipped container at its own height with no
  differing pixel. A panel of any extent is those same corners and longer bars, which is what makes a
  width the client ships no sheet for renderable and testable against one it does.
- A panel is refused below the larger **per axis** of two independent floors: `Window.minimum()` is
  what the art needs to paint a frame and `MenuScreen.minimum()` is what the screen needs to hold a
  cell. Neither implies the other, and reading one refuses almost nothing - vanilla's drawn geometry
  closes at eight Minecraft pixels square, which a chest of no rows and no columns clears with
  nowhere to put a cell, while a window sliced from art can want more room than a screen full of them.
- `MinecraftFontMetrics.getAscent()` answers **output** pixels where `TextKit.drawLine` takes
  Minecraft ones. Use `getAscentMcPixels()`; never divide at a call site.
- A window carries its own ink and is handed no palette: a `Window.Theme` holds one and a
  `Window.Sliced` is already coloured, so passing one would mean the arm that cannot honour it
  ignoring the argument. Only the vanilla palette is measured against shipped art; the rest are
  authored, and none of them inks text, so a caller choosing a dark theme sets the label colour too.
- The player's section is an option a caller asks for, and it is nine cells at the margin whatever
  the panel width. Every parity subject arms it, because both gates compare against a panel that has
  one - the shipped art and the client's own screen.
- **A mark the panel would have drawn itself re-inks; anything else carries its own inks.** The
  arrow, the plus and a button's bevel are shapes in the palette's roles, so a re-inked panel carries
  re-inked marks. The anvil's hammer is a picture and the inside of its name field is an input
  widget, and a palette has nothing to say about either - the same line a button's face already sits
  on, its item being full-colour over a bevel that re-inks. The field's **outer** ring is what proves
  the line rather than breaking it: those two inks are a cell's own bit for bit, so the well sinks
  into whatever panel it is cut into while its olive stays the widget's.
- **The anvil's art cannot be its own oracle.** Where its name field goes the shipped panel holds a
  110x16 rectangle of flat red the client covers on every draw and never once shows, so a window
  sliced from that texture paints the red. That is why the field is drawn from rules - not to keep
  its ink free, which is the reason every other mark is declared, but because the art is a hole. It
  reproduces the shipped text-field sprite whole, and the panel's remaining residual is exactly that
  rectangle.
- A field is chrome and content split the way a button is: the window sinks the well, the renderer
  puts the text in it. The text is drawn plain rather than parsed for format codes, because the
  client's own field filters them out of what can be typed, and it carries the drop shadow a
  container's labels decline - it is a widget's text and not the panel's.
- **A field shows the end of what was typed rather than the start**, and its caret has two forms the
  value's length picks between: vanilla appends a `_` glyph after the text and cannot once the field
  is full, so at the cap it fills an unshadowed bar beside it instead. The anvil caps at 50, so a
  name at exactly that length is what draws the second form. The caret is a frozen instant of
  something that blinks on the wall clock, so it is a caller's choice rather than a phase.
- **Two gates answer for a menu and neither substitutes for the other.** The shipped-art oracle is
  byte-exact about chrome, costs seconds and boots no client; the harness menu sweep renders each
  screen through the client's own GUI pipeline and is the only one that sees what a composed screen
  adds - its labels, its slots through vanilla's GUI item atlas, and the composition itself.

## Porting a new entity

Always check whether the subject's renderer overrides `setupRotations` or carries a scale override,
and replicate it in the kit's transform chain.

- Only two `setupRotations` translates survive the harness rest pose; the rest sit behind gates the
  frozen animation state makes false.
- The squid's age-conditional Y shift is baked into the **mesh** by `EntityMeshShift`, because the
  renderer and the canvas-sizing bounds walk both read the mesh. The pufferfish's is an expression,
  so the walk declines it and it stays latent.
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

The probe-reading traps, the worked examples and the two version-scoped rosters are in the parity
gate's `references/diagnostics.md`.

## Decisions that stay closed

A refusal whose stated mechanism is not in the code is void - check it against source before
honouring one. Refused changes and accepted gaps share a shape and are listed together. The
generators keep their own list in [tooling/CLAUDE.md].

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
  `Villager.Profession.drawsBadge()` and `isBaby` are what suppress one.
- Do not recover the armour frame from the wearer's `body` bone - it carries no rotation member, and
  a baby's body pivot is not a mesh transform.
- Do not delete the `PoseOperator` constants no shipped row uses - thirty-three of the forty-nine
  today. The roster is the WALK's vocabulary for vanilla's own method calls rather than dead API:
  `Mth.sqrt`, `Mth.rotLerp`, `Easing.inCirc` and the rest are what a `setupAnim` body is read
  THROUGH, so dropping one turns the next version's pose into a refusal for a subject that used to
  be read. `PoseOperatorMirrorTest` compares the two copies character for character, so the
  renderer's cannot shrink without the generator's, which is where that reach lives. It buys no
  declared type either - an enum is one type whatever its constants are - and the only type behind it
  is `VanillaEase`, the bit-exact reproduction of vanilla's easing, which has a test of its own on
  each side.

[tooling/CLAUDE.md]: tooling/CLAUDE.md
