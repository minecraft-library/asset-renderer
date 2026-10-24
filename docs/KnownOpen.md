# Known open

Load when filing, picking up or closing an open question nobody owns. Indexed from
[`CLAUDE.md`](../CLAUDE.md).

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - the
*Decisions that stay closed* of the topic file under `docs/` it concerns. A measurement belongs in
the commit that made it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## Entity corners are rounded in a different order from vanilla's

Both sides apply the same transforms to an entity's corners and round them in a different order.
Vanilla composes one float matrix per bone that opens with the canvas offset and scale, then its iso
turn - `rotationXYZ(210, 45, 0)` and a half turn - then the renderer's preamble and the mesh's root
scale, and transforms each corner once. The renderer bakes the bone chain into model space, applies
`rotationXYZ(30, 225, 0)` and the facing in a second product, and centres last. So a corner lands a few
float steps from vanilla's, shifted one way per subject - vanilla's dragon corners sit `0.023` of a
`1/256` step lower on average, its elder guardian's `0.019` further right - and where a corner sits that
close to a midpoint between two steps, the card snaps the two apart. At 26.1 the harness's corners and
the renderer's snap to different steps on 155 of the 19466 distinct corners of the still and armour
sweeps, and on the card's measured choices vanilla's corner predicts 8459 of 8461 where the renderer's
predicts 8365.

What porting vanilla's order is worth, measured against the stored rows with vanilla's corners
substituted from the harness's dump, on a fleet sum of `661.266`:

- Snapped by the renderer's own rule: 97 rows better and 12 worse, `-1.655`, no loss above `0.007`.
  `1.622` of it is the charged creeper's doubled energy swirl, `1.769` to `0.147`; the giant's worn
  shell closes too, and no iron-armoured row loses.
- Snapped the card's way: 127 better and one worse by `0.0001`, `-1.795` - but that needs the GL
  viewport step and ties to even, both refused in [renderer/TexelFetch.md](renderer/TexelFetch.md).

It is taken whole or not at all - [renderer/Geometry.md](renderer/Geometry.md) refuses a partial
port, because each one measured
re-decides the coplanar contests both ways. Whole, it is the entity path: bone chains rooted at the
canvas matrix with JOML's affine rotate grouping, which `Matrix4f.rotate` does not use; every
renderer's preamble and every mesh's root scale as matrix ops, which no shipped table carries and the
tooling flattens into pivots and cube operands; the worn shell and carried blocks rebuilt on the
wearer's matrix; and depth in new units. Nobody has taken it on.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows.

`PoseStyle` names no bag at all - `AppearanceOptions.applies` asks the appearance rather than the
request carrying one - so the row type is answerable for a subject whose options are not an
entity's, and a player catalog shipping age-free rows never reaches it either way. What still spells
`EntityOptions` is the caller: `StyleCatalog.resolve` and the `byId` overload behind it take a
predicate and name no bag; `EntityRenderer` supplies it as `options.getAppearance()::applies`.

**That shape is not an `AppearanceOptions` on `PlayerOptions`.** `AppearanceOptions` stays
entity-specific by decision, so the player does not gain one. What the three bags share is an
appearance concern nothing abstracts yet, and organising that is its own job, deliberately not
attached to this entry - a knob coined by widening the player bag to look like an entity's would be
settling that question by accident.

A styled player exists on the entity path, and it is not the source the knob needs.
`author.install.PlayerRig` synthesizes a `minecraft:player` entity row - the wide-arm humanoid mesh
copied off the shipped zombie row, under `StyleCatalog.BIND_ONLY` and `EntityPose.NONE` - that takes
installed styles through `EntityRenderer`. It sits at tier 17.4 in `TierOrderTest`'s order and
`PlayerRenderer` sits at the root's 16, where an import runs only to a strictly lower tier, so
`PlayerRenderer` may not name it; and `PlayerRenderer` carries no style machinery at all, importing
nothing from `asset.pose`, `bake.pose`, `engine.pose` or `author`.

Taking the knob needs four things: a style knob on `PlayerOptions`; a player catalog source below
tier 16; style resolution the player bag can answer; and `PlayerRenderer` taught to pose. The third
is the smallest of them: `StyleCatalog.resolve` takes the style id, a predicate saying whether a row
applies to the subject, and the subject id its refusal names, so the player bag has only to supply
the predicate. Supplying it is the appearance question the three bags share, though, so taking the
knob settles the abstraction this entry keeps separate by the back door. It reaches the player
sweeps, which are LOOK gauges rather than byte gates, and the entity pose path.

## The tooltip frame's stretched side ramp reads the next texel on some exact-boundary rows

The tooltip frame sprite stretches its centre slice (`stretch_inner`), and `NineSliceKit` maps each
output row of that slice to a sprite row with a pixel-centred integer sample: row `off` of the centre
reads sprite row `border + (2 * off + 1) * centreSprite / (2 * centreDest)`, `border` being the near
border in sprite rows (10 for the frame) and the quotient rounded down. Where that quotient is a whole
number the output pixel's centre sits exactly on the edge between two texels, and the kit takes the
next one. The client draws the same sprite as a quad whose texture coordinate the GPU interpolates
in float, and on such a row it lands on either side of the edge - not always the same side, even
across the two output columns of one Minecraft pixel.

Against the 26.1 client's tooltip references, with the sprites blended as the client blends them,
this is all the tooltip sweep's box-alone rows still differ by: 18 pixels, one ramp level each, every
one on the frame's side ramp at an exact-boundary row and none near a corner. `hex_colours_chrome`
differs by 1 (80 centre sprite px over 88 output px, row 9), `item_like_chrome` by 16 (80 over 168,
rows 14, 56, 98, 140 and 161) and `narrow_chrome` by 1 (80 over 24, row 5), and the full rows of the
first two carry the same pixels. At each the renderer already shows the next ramp texel - `F5220058`
where the client still shows `F5230058`, at `item_like_chrome` row 14. Not every exact-boundary row
differs: five of the eight at `item_like_chrome` do, rows 98 and 140 on the left side only. At
`hex_colours_chrome` row 9 the client's two output columns of one Minecraft pixel disagree with each
other, column 96 matching the renderer and column 97 not.

A pixel-centred integer sample gives one answer per output row, so it cannot follow a coordinate that
lands differently per pixel. Closing it needs the rule by which the client's interpolated coordinate
falls on one side of an exact edge or the other - the split is consistent with a quad's two triangles
interpolating to values a last float bit apart, which is not measured - and nothing measured yet
states that rule. Nobody has taken it on.
