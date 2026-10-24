# Depth: the contract

Load when touching depth in `engine/raster` - `DepthMath` and the snapped depth plane,
`Rasterizer.rasterizeAll`, the translucent sort, the fill rule - or the order a mesh's bones draw
in. The texel half of the raster contract, and the corner snap's grid and tie rule, are
[TexelFetch.md](TexelFetch.md). Indexed from [`CLAUDE.md`](../../CLAUDE.md).

- The `1/256` corner snap (`Rasterizer.snapToCoverageGrid`) moves depth with coverage:
  `DepthMath.Plane` is solved from the triangle's **snapped** screen positions, the ones coverage
  reads, as GL interpolates depth over the snapped window positions. Two faces sharing an edge share
  its snapped corners, so they meet in depth on the edge both cover up to, and the face in front holds
  every sample both cover off it. A plane through the unsnapped positions meets its neighbour on a line
  the snap has moved off that edge, and hands a sample between the two lines to the face behind, its
  plane read past its own edge. What the snapped plane costs is the tie between coplanar triangles
  with different corners: each is tilted by its own corners' snap (the coplanar bullets below).
- `Projected.plane` is raster depth; `p0/p1/p2.z()` is the camera-space depth the translucent
  `quadCamDepthKey` sort reads. Do not collapse them - the plane is read at a sample point and the
  vertex depths are read per triangle, and only the second is a sort key.
- Depth is compared on vanilla's window grid: `DepthMath.onVanillaDepthGrid` rounds each
  interpolated depth through `0.5f - depth * k`, `k = scale / (2 * VANILLA_DEPTH_RANGE)` at
  `VANILLA_DEPTH_RANGE = 1000`, where a `float` step beside `0.5` is `2^-24`.
- Round the interpolated depth, never the three vertex depths - rounding at the vertices tilts each
  triangle's plane by its own vertices' rounding.
- Depth comes off the triangle's own `DepthMath.Plane`, solved once in `double` from its snapped
  corners and read at the sample point, so the only rounding between the solve and the depth test is
  the one that lands the answer in a `float`. Deriving it a second time - into three vertex depths,
  then into gradients, then from a vertex anchor - is five more roundings, and two triangles of one
  plane take them differently enough to separate by a quantum or two a pair the grid would tie.
- **Every lens reads that one plane**, perspective included: depth is affine in screen space under all
  three, and blending the three vertex depths barycentrically over the snapped corners is the same
  affine function, because each of them *is* the plane's value at its snapped position: blended with
  the integer edge values the coverage walk holds, they draw the plane's bytes at a divide per sample
  more. There is no perspective-correct depth path. `perspectiveCorrect` forks the texture
  coordinate - its interpolation and its texel read - and never depth.
- There is no depth tolerance anywhere: `depthFails` is a bare `depthVal < existingDepth`, with no
  emissive slack, no coincident-overlay clearance inflate and no trim separation.
- A coplanar pair ties only where its two triangles solve one plane - they share their corners, as an
  overlay emitted over its base's geometry does, or their snaps happen to tilt them alike. A tie is
  last-drawn-wins, as `GL_LEQUAL` is, so the draw order is the tied-depth priority, and an entity
  mesh's draw order is vanilla's bake order. `PartDefinition.children` is a `java.util.HashMap`;
  `bake` collects its entries into an `Object2ObjectArrayMap` in the order the map iterates them, and
  `ModelPart.render` draws a part's own cubes and then its children in that order, depth first - one
  map per parent.
  - A level iterates its names in `put` order bucketed by the table, except where vanilla filled it
    with a `putAll` copy: a whole-mesh scaling copies the root, a replaced part copies its old children
    into the new one, and every retain and clear surgery replaces each part it empties. On the JDK 19
    and later vanilla runs on, a copy of `n` names is sized `tableSizeFor(ceil(n / 0.75))` first - two
    to eight slots for one to six names, where a level built by `put` holds sixteen - so it iterates in
    a different order from the map it copied.
  - The tooling replays every such call a factory makes on `java.util.HashMap`s of its own, over
    vanilla's full key set before any part is dropped, and writes `entity_geometry.json`'s bones in the
    resulting order. The renderer holds no hash map: `EntityMesh.getBones()` is the priority at both of
    `EntityGeometryKit`'s bone loops, the full build and the replay alike, and at `ShellIndex.of`, so a
    mesh is drawn in the order it is read. How the replay runs is in [tooling/BakeOrder.md](../tooling/BakeOrder.md).
  - The three armour sets vanilla registers through a whole-mesh scaling - giant, husk and wither
    skeleton - are meshes of their own in the table, written in the order the scaling's copy of the root
    iterates: those three draw `body` between the arms and between the legs, where the unscaled sets
    draw it last.
  - A block-entity mesh keeps its factory's declaration order: `block_geometry.json` is written in
    `addOrReplaceChild` order and `BlockGeometryKit.buildFromBones` draws it as given. Vanilla bakes a
    block entity's parts the same way, but no block-entity render has been measured under the bake
    order, so the table keeps the order its renders are measured in.
- **One picture is one depth pass.** Every `Rasterizer.rasterize` call starts a depth buffer of its
  own, so two calls over one buffer paint the second over the first wherever it covers. A picture
  made of several meshes, each at its own model transform - a held `composite`'s layers, each posed
  by its own model's display, and a slot `composite` holding a layer built from elements - goes
  through `Rasterizer.rasterizeAll`, which depth-tests every part against the parts before it, gives
  a coplanar tie to the later part, and sorts the translucent triangles of every part together.
- **A worn shell's emission order is `ShellIndex.of`, not the geometry kit's bone loop.** Its triangles
  come from `ArmorKit.buildArmor3D` walking `ShellIndex.parts`, so a probe that reorders
  `EntityGeometryKit` reaches the base mesh alone and answers nothing about a shell - it will report
  no armour row moving, which is the probe missing them rather than a result.
- The grid quantum is coarser than two solutions of one plane differ by, so a contest between
  triangles that share their corners falls to draw order. That is a property of the arithmetic and not
  a given: a longer `float` chain between the solve and the test can put two solutions of one plane
  12 ULP apart, which is one to three quanta and decides them outright. Two coplanar triangles with
  different corners do not solve one plane: each is tilted by its own corners' snap, up to `1/512` px
  per corner times the face's depth gradient, which is several quanta - six between the coplanar
  `north` faces of two of a villager's `arms` cubes at one pixel - so the snap decides their contest,
  as the GPU's snap decides it. Measure the gap against the quantum before believing a contest ties.
- On an additive pass a coplanar tie is a doubling rather than a tie-break: both fragments accumulate,
  as vanilla's do.
- `emissive`, `writesDepth` and `sorted` are three independent declarations carried as one
  `PassDeclaration`, and one entity can split them across its own two passes. A self-occluding pass
  needs the write and the sort together.
- The sort is by quad centroid, so a later quad still loses over the part of its area behind an
  earlier one, and `quadCamDepthKey` identifies the shared diagonal in camera space, never on screen.
- The fill rule is the GPU's top-left rule, and the GPU applies it in GL's window frame, where y runs
  up. Image space runs y down, so here it reads bottom-left: a sample exactly on a horizontal edge two
  faces share belongs to the face above it, and one exactly on any other shared edge to the face on
  its right. It is classified on the sign-normalized edge direction: `EdgeCoefficients.of` negates the
  coefficients when `denom < 0`, so with `e >= 0` marking the interior a left edge goes up and the
  horizontal edge that owns its samples goes left. Reading either arm the other way round still hands
  a shared sample to exactly one face, but to the opposite face from the GPU - the horizontal arm read
  that way is the top-left rule taken in image space.
- How a covered sample's texture coordinate becomes a texel - the boundary rule, the face and sheet
  bounds, the scrolled pass, and what the snapped corners decide for it - is [TexelFetch.md](TexelFetch.md), beside
  what the reference GPU does to the coordinate and what the renderer does not reproduce of it.
- The reference set's own depth range is part of the contract, and every harness `FrameRenderer` is
  at `1000` - the depth-quantum probe, which drives two ranges and refreshes no reference, is not one.
  A change emulating the reference's rounding cannot be evaluated against a coarser reference - fix
  the ground truth first.

## Decisions that stay closed

- Do not reorder bones to chase a tied-depth seam. An entity mesh's bone order is vanilla's
  `PartDefinition.children` bake order, replayed by the tooling over vanilla's own map calls, so a seam
  it leaves is not an order the renderer chose, and moving a bone to win it there is a fitted constant.
- Do not add a depth tolerance - a depth buffer stores a quantised value and compares it exactly, so
  slack is a fitted constant.
- Do not replace the per-triangle depth plane with per-pixel barycentrics under an orthographic lens.
- Do not switch the tie to first-drawn-wins - vanilla's test is `GL_LEQUAL`.
- Do not emit inflated degenerate plane faces - flatness is judged on the authored size while the
  corners come from the inflated bounds, and sub-pixel barycentric coverage does not match a GPU's
  edge functions.

The corner snap's grid and its tie rule are refused in
[TexelFetch.md](TexelFetch.md#decisions-that-stay-closed), and moving the fit's origin in
[ProjectionAndFit.md](ProjectionAndFit.md#decisions-that-stay-closed).
