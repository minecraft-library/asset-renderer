# Pose, facing and canvas fit

Load when touching `Projection`, `engine/light` - `LightingFrame`, `Shading` - the entity and player
lighting fold, a renderer's `Placement`, `Rasterizer.rasterizeFitted` and `prepareFit`, the entity
canvas pass and its family-fit pre-pass, a bounds walk, or a canvas sized to its subject, a tooltip's
and chat text's included. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

Every iso subject shares `Projection.VANILLA_ISO` - `(30, 225, 0)` with `Lens.ISOMETRIC_BLOCK`,
vanilla's `display.gui` pose and scale. It is facing-neutral, presents the model's `-Z` side, and
`Projection` is its sole owner. `LightingFrame.ENTITY_IN_UI` is the separate `(210, 45, 0)` lighting
frame, decoupled from the camera pose.

**An entity and a player are each lit once, after their layers are folded.** Vanilla binds
`Lighting.ENTITY_IN_UI` once per GUI entity draw before any layer is submitted, so a wearer, its
overlays, its carried block, its wings and everything it wears light under one entry. Both renderers
do exactly that - `EntityRenderer` over the entity's folded stack through `AxisSigns.MIRROR_Y`,
`PlayerRenderer` over the player's through `AxisSigns.MIRROR_Z`. Block, fluid and portal are not in this
rule: their kits bake `Lighting.inventory` at emit time, fluid and portal keep that bake, and a
block's folded stack is re-shaded through `Shading.relightForItems3d` under vanilla's `ITEMS_3D`
entry rather than the entity's.

- **The fold owns the entity shade; no entity-side producer resolves one.** `EntityGeometryKit`,
  `EntityArmorKit.intoModelFrame` and `EntityRenderer.buildBlockOverlayTriangles` all emit
  `Shading.UNLIT`.
  A player-side producer may still carry the `BoxKit.buildBox` cardinal bake, because the
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

- Facing is per-renderer, a model-to-world `Placement` composed by `Rasterizer` as
  `pose . placement . modelTransform`: identity for block, fluid and portal, `R_Y(180)` for the player,
  `R_Z(180) = diag(-1, -1, 1)` for the entity, which also un-flips its Y-down model.
- `Rasterizer.rasterizeFitted` with `FitRequest` is the one fit path, serving player and entity;
  block, fluid and portal render a unit cube at fixed scale and never fit. Kits emit fit-neutral
  geometry, and scale and centring live only in `Rasterizer.prepareFit`, which forks on request mode
  and lens kind and measures through `Rasterizer.orient`, the exact render orientation.
- Orthographic bakes the scale in 3D, because the window-depth grid is a function of the canvas scale
  and is not scale-invariant; perspective and oblique carry a 2D post-projection fit over
  unit-normalized geometry.
- No fitted canvas is odd-width, on either side. A symmetric subject's front corner lands exactly on
  the anchor - a pixel centre at odd width, a boundary at even - and the placement is the harness's
  own convention rather than vanilla's, which is what makes rounding it legitimate.
- Both bounds walks measure a face at its opaque-texel sub-rectangle, not per texel, so a face whose
  opaque texels touch all four edges of its UV box is measured to its full corners even where those
  corner texels are transparent. The elytra's outward wing face is the case the corpus shows: its
  outline runs on the diagonal, so an adult winged canvas carries 32 blank columns beside the wing at
  256 px/block, and a baby or a small stand 16. The strip is a canvas convention both sides share, as
  the even width is, and it is kept: the renderer's `EntityGeometryKit.contributeFaceAlphaTight` and
  the harness's `EntityBoundsWalker.contributePolygonExtents` move in one commit or not at all.
- **A canvas sized to its subject ends at the subject's first pixel**, with no fixed transparent
  margin even where the client reserves one: an entity's `UNION_BOUNDS` canvas at its silhouette,
  which the bounds walk measures, and a tooltip's at what its chrome draws over the client's sprite
  rect, which `NineSliceKit.extent` answers before the draw. Chat text's canvas is what its lines draw
  over every frame, which `TextKit.inkBounds` answers before the draw, and it ends inside a Minecraft
  pixel where the first or last output pixel drawn does. `GROUP_BOUNDS`, the family fit, sizes a
  member to its group instead, and a fixed-scale or caller-sized canvas is neither.

## Decisions that stay closed

- Do not plan a light sweep on the `-Dasset.entity.L<idx>d{x,y,z}` knobs - they are inert downstream
  of `Lighting.resolveEntity` while `-Dasset.depth.range` moves the same rows.
- Do not move the fit to a top-left origin to dissolve the centre-column contest - per-column
  coverage already agrees with vanilla, so moving the alignment shifts every row off its placement.
- Do not size an entity canvas from the selected coat - vanilla's family-fit pre-pass builds a fresh
  render state whose variant is the enum's default.
