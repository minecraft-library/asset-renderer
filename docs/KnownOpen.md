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

Against the 26.1 client's tooltip references, with the sprites blended as the client blends them, this
is all the tooltip sweep's box-alone rows over the sprite box still differ by: 18 pixels, one ramp level
each, every one on the frame's side ramp at an exact-boundary row and none near a corner.
`hex_colours_chrome` differs by 1 (80 centre sprite px over 88 output px, row 9), `item_like_chrome` by
16 (80 over 168, rows 14, 56, 98, 140 and 161) and `narrow_chrome` by 1 (80 over 24, row 5), and the
full rows carry the same pixels. At each the renderer already shows the next ramp texel - `F5220058`
where the client still shows `F5230058`, at `item_like_chrome` row 14. Not every exact-boundary row
differs: five of the eight at `item_like_chrome` do, rows 98 and 140 on the left side only. At
`hex_colours_chrome` row 9 the client's two output columns of one Minecraft pixel disagree with each
other, column 96 matching the renderer and column 97 not.

A pixel-centred integer sample gives one answer per output row, so it cannot follow a coordinate that
lands differently per pixel - and no rule stated over the quad follows it either. The rows whose
centre slice is 24 output px tall - `long_line_chrome`, `mixed_segments_chrome`, `narrow_chrome`,
`one_line_chrome`, `unstyled_chrome` and `width_glyphs_chrome` - draw the same side quads, with the
same texture coordinates, over the same rows of a render target of the same height, and differ only in
where the right-hand quad sits across the target. Five read the texel after the edge at every visible
boundary; `narrow_chrome` reads the one before at one pixel, in that quad's upper triangle, where the
other five read the one after at the same row, column and triangle. The pick follows the quad's place
in the client's target, which is consistent with the card carrying a triangle's plane value from a
corner chosen by its position (see [Texel rules](renderer/TexelFetch.md)), applied to corner
coordinates that are the sprite's place in the stitched GUI atlas rounded to float.

The renderer's raster rule is the wrong reading here. Across the 172 visible exact-boundary pixels of
the box-alone rows over the sprite box the client reads the texel after the edge at 154 and the one
before at 18, so reading the texel below an exact boundary, as the entity raster does, would turn the 18
differing pixels into 154. Closing the 18 needs the card's interpolation setup at the client's screen
position and the GUI atlas's layout, and modelling the card is refused in
[Texel rules](renderer/TexelFetch.md). Nobody has taken it on.

## A skin, cape or armour face is read from its own crop, not from the whole sheet

The skin, the cape and the worn armour layers are cut into one buffer per box face, and each face is
laid over that buffer, where the client lays each face as its rectangle of the whole sheet. Three things
follow. The sampler the sheet's sidecar sets never reaches these faces, so a blurred or clamped sheet
draws nearest and repeating here. At a texel boundary that a pixel centre sits on, a mirrored face (an
armoured player's left arm and left leg, every mirrored shell cube), a face read bottom up and the
cape's cap strips read the texel on the other side of the edge from the client's, and every face reads
the crop's own far edge where the client reads the sheet's neighbouring texel. A fractional shell strip
is resampled to whole texels, and a strip that lies off its sheet draws clear.

The fix lays each face as its region of the whole sheet with the sheet's sampler: `FaceTextures`
answering a region, `BoxKit` laying its corners, and `HumanoidPart`, `WornBox` and the cape normalising
their rectangles by the declared layout. It is not byte-neutral at vanilla's sheet size, because a
boundary pixel then reads the sheet's texel, so it moves stored player, armour, entity and glint rows
and the player pin, each named by a before/after run ahead of its capture. It also closes the next
entry. Nobody has taken it on.

## A skin, cape or armour texture of another size is cropped at vanilla's sheet coordinates

The croppers above cut at the texel coordinates of the sheet size the model declares, so a texture of
another size - a pack's high-resolution skin or armour layer, or a strip a sidecar declares several
frames of - draws that sheet's top-left texels, where the client normalises the model's UVs by the
declared size and draws the whole file. No source in reach ships a skin, cape, armour or wing texture at
a size other than vanilla's. Laying each face as its region of the whole sheet, the previous entry's
fix, closes it. Nobody has taken it on.

## An equipment asset's layers are pasted into one picture before they are drawn

`EquipmentKit` tints a dyeable layer's texture to a byte and copies an asset's layers into one buffer,
which the raster then shades and rounds again. The client draws each layer as its own pass, its colour
the vertex colour, multiplied with the light once and read through the layer's own sampler. So a dyed or
undyed-coloured layer's colour is rounded twice - undyed and dyed leather, a horse's leather armour and
every dyeable layer take it - and a composite is read nearest whatever its layers' sidecars select. A
single flat layer is no composite and keeps its own sampler.

The fix answers each layer with its colour and draws it as its own pass, tinted once with the light.
It moves the stored rows that dress a dyeable layer - the armour sweep's leather zombie and piglin, the
glint sweep's leather-clad player, the entity sweep's horse - by a channel step where the two roundings
part. Nobody has taken it on.

## A cutout pass is blended where the client writes it whole

The client draws an entity's cutout textures, every equipment layer, the wings, a skull and the copper
golem statue through cutout pipelines that set no blend: a fragment at or above the 0.1 cutout writes its
colour whole, in submission order. Each of those passes here is the default pass, which blends a partly
transparent fragment at its alpha (`PassDeclaration.DEFAULT`). Read nearest, only a partly transparent
texel makes one - of vanilla's 85 equipment textures, three carry such a texel: the strider saddle (six,
at alpha 253 or 254) and the black and cyan llama decor (one each). Read through a texture whose sidecar
blurs, every edge between a clear texel and a drawn one makes one. No stored stack blurs such a texture,
and whether a stored strider row draws a partial texel is not measured.

The fix is a cutout pass kind - written whole at or above the cutout, no blend - for those passes. It
changes how the rasterizer composites them, so it wants its own measurement. Nobody has taken it on.

## A custom_model_data flag condition and string select never read the stack

A `condition` node on `custom_model_data` drops its `index` when it is read, and the walk answers
`false` for it and absent for a `custom_model_data` select (`ItemModelContext`), where the 26.1 client
answers `flags[index] == true` and `strings[index]`. With no stack both agree. A stack that sets `flags`
or `strings` - which is how ItemsAdder packs switch a composite's layers - draws those layers in the
client and never here.

Reading them is two small walk arms and the kept index, but it widens the set of component tests the
walk answers, which [RequestsAndResults](renderer/RequestsAndResults.md)'s *Decisions that stay closed*
refuses ("Do not widen the component tests an item definition's walk answers"). That refusal rests on
the set being every component test the definitions on hand use; a user's ItemsAdder pack uses the flag
condition 2016 times, and these two choose how an item looks, from the same component the range dispatch
already reads. Whether that reopens the decision is the owner's call. Nobody has taken it on.

## A special reached through a dispatch root takes the default pose when no stack picks it

`BlockIndexBuilder.iconGuiFor` reads a special model's base for its icon pose only where the item
definition's root is itself a `model` or `special` node; under a `select`, `range_dispatch` or
`condition` root it gives the default iso pose (`[30, 225, 0]` at 0.625), reads the base from `item/`
models only and falls back to the block model's gui transform. The client poses the picked special by
its base under any root and falls back to no transform. The harness's `BlockGuiTransform` mirrors the
renderer, so no parity row sees it. In vanilla it decides `chest`, `trapped_chest` and the eight copper
golem statues: `chest` and `copper_chest`, which the client poses identically, are drawn a half turn
apart here and in the references, and which of the two a real inventory shows is not measured. A pack
that wraps the player head in a `range_dispatch` draws its plain head a half turn round and smaller.

The fix reads the neutral branch's base under any root, from every model, with no transform where it
declares none, and the harness reads the picked layer's properties to match. It moves ten vanilla
references, which are re-rendered and promoted, and the chest family's turn wants a client inventory
capture to settle. Nobody has taken it on.

## A container sheet is composed at one texel per Minecraft pixel

A shipped screen's sheet is resized to 256 texels before its blits are copied into the panel
(`Window.Sliced`'s `compose` and `paintPanel`), so a sheet of another size draws at one texel per
Minecraft pixel where the client draws it at full resolution, and the panel is laid nearest whatever
the sheet's sidecar selects, where the client filters or clamps each blit off the sheet. The fix keeps
the blits the panel is composed from and lays each off the sheet through the client's region blit, at
the sheet's own resolution and sampler. A 256-texel vanilla sheet laid blit by blit with nearest
sampling is the copy's texels, so no stored menu moves; no stored stack ships a sheet of another size or
with a texture section. Nobody has taken it on.
