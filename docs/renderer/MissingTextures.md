# What a block or item draws when the pack has not got it

Load when touching a texture, model or subject lookup that can miss - `withMissingTexture`,
`Substitutions`, `MissingMesh`, the missing model and the missing item model - or a driver's
`-PhideTextures`. Indexed from [`CLAUDE.md`](../../CLAUDE.md).

A **block or item face** whose texture no pack supplies draws the generated checkerboard and reports
the id once. A face whose texture a pack ships but which cannot be read - zero bytes, a body the image
reader refuses, a `.png.mcmeta` sidecar that does not parse or carries an animation or texture section
the client's codec refuses, or an animation whose frame size does not divide the strip on both axes -
draws it the same way, and is reported as unreadable rather than missing, because vanilla draws its
missing sprite for all of them and a texture has no lower pack to show through. A sidecar that does not
parse loses only its own texture: the index captures it as there and yielding nothing rather than
failing the context load; a refused section loses the texture too, its sidecar's other sections
standing. A sidecar whose `gui` section the client's codec refuses loses its texture only where a GUI
sprite is read - a tooltip's background or frame, a menu's cell sprite or the anvil's name field or
cross or the recipe-book button - as the client's GUI atlas alone reads that section; read as a block
or item texture it draws.
The same holds for every texture an entity, a player or an item names beyond its own faces - an
entity's base, overlay, carried-block and group-member textures, the equipment and armour layers and
their pack-rule tiles, the elytra wings, the cape and the skin, the banner patterns, the trims and the
glint - and for the textures that are no block's or item's: a fluid's still and flow faces, the two
textures the portal shader samples, a menu's named chrome art, which is the checkerboard read across
the sheet the client composes a shipped screen's panel from, and resized like any other art for a
screen it ships no sheet for, rather than falling back to the vanilla panel, and a tooltip's
background and frame, a menu's cell sprite and the anvil's name field or cross or the recipe-book
button, each stretched over the client's whole sprite rect rather than falling back to a default. No
option turns it off: a broken asset is drawn as broken, so a viewer can see it, rather than leaving a
hole or dropping the subject - and that holds for a batch as well, so an atlas tile shows the
checkerboard where a single render would.

- **The seam is each texture read.** Each reads through the context's `withMissingTexture()`
  wrapper, which draws the checkerboard and reports the id, while the context's own `resolveTexture`
  keeps its three states - absent for a texture no pack ships, empty for one a pack ships that cannot
  be read - for whoever reads it bare. The wrapper answers every id with pixels, so a reader behind it
  never meets a value-less answer. The kits that take a context - the trim, banner, glint, equipment
  and elytra kits, and the nine-slice kit's GUI-sprite read - wrap whatever they are handed, so a kit
  draws the checkerboard whoever calls it.
- **A GUI sprite's stand-in stretches.** The client's missing sprite carries no `gui.scaling`, so it
  stretches over the rect whatever the sprite it stands for would have been sliced as, and a file that
  cannot be read never reaches its atlas, sidecar and all. The context keeps such a file's sidecar, so
  `NineSliceKit`'s GUI-sprite read takes the scaling from the sidecar only for a sprite that was read.
  A tooltip style naming art no pack ships draws a box filling the whole rect, the text plus twelve
  Minecraft pixels a side, and that is the canvas. A sprite whose `gui` section the client refuses is
  not in the client's GUI atlas either, so `NineSliceKit`'s GUI-sprite read serves it with no pixels:
  it is the stand-in, stretched, and reported unreadable.
- **A report has two halves.** A line on stderr, which vanilla's missing item model alone does not
  print, and a `Substitution` in the result of every render that drew the stand-in - a second render
  of the same id records it again even where the log stays quiet, and a composite carries its
  children's. A read that only sizes the render reports on neither half: the entity canvas pass reads
  sibling members, variant coats and the default coat's block overlays through
  `RendererContext.measuring()`, under which a missing texture is still the checkerboard, so the
  canvas is sized by what the draw would draw, and nothing is logged or recorded. A texture both
  measured and drawn is reported by the draw.
- **A cache above the seam carries its stand-ins.** A cache that keeps a value across renders, built
  from a texture or model read, stores the stand-ins drawn into it with the value and adds them to the
  render's collector on every hit.
- **Three lookups besides the textures draw a missing picture**: the block or item subject lookup, an
  entity's carried block, and the model an item definition's leaf names, which draws vanilla's missing
  model where no pack ships it and reports the model id once through `Substitutions.leafModel`.
  `BlockRenderer.missingBlock` and `ItemRenderer.missingItem` are where the subject lookups report, and
  `EntityRenderer.buildBlockOverlayTriangles` is where the carried block does: an id the block index
  does not know is the missing cube on the carrying part, placed as the block would be, and a block
  that draws nothing, such as air, builds nothing and reports nothing. An entity's subject lookup is
  not one of them: an id the index does not know is refused. A definition the loader refused is no lookup
  either, and neither is a `select` or `range_dispatch` that falls back to nothing it declares, nor a
  node whose type sits in a mod's namespace: each draws vanilla's missing item model, unglinted,
  prints nothing, and is recorded in the render's result as an `ITEM_MODEL` stand-in naming the item -
  empty for a refused definition, which a definition rooted at a mod's node type is, and absent for a
  fallback the definition does not declare and a mod's node type below its root. A refused definition
  shadows every lower pack's copy.
- **A definition the client cannot bake is refused too.** The client bakes every `minecraft:model`
  leaf of every definition as it loads, whichever branch a stack would take, and a leaf whose quads
  draw sprites from more than one atlas fails the whole definition: every stack of the item draws the
  missing item model. A model's texture is an items-atlas sprite where the items atlas holds it - the
  sources of every pack's `atlases/items.json`, concatenated lowest priority first - and a blocks-atlas
  one otherwise, an unresolved reference included, because the client looks a model texture up in the
  items atlas first and falls back to the blocks atlas, whose missing sprite stands in for a texture in
  neither. `ItemModelTreeLoader.refuseUnbakeable` holds such a definition as a rejected tree, so every
  rule above about a refused definition holds for it. Vanilla's own definitions all bake. A pack
  stitching its own textures into the blocks atlas, and drawing one of them beside a vanilla `item/`
  sprite in one model, is what meets it.
- **A model names a sprite, which reads the texture its atlas stitched it from.** A pack's
  `atlases/items.json` or `atlases/blocks.json` can stitch a texture under a sprite id of its own -
  ItemsAdder packs stitch `omc_foods:items/seeds/salad_seed` as `_omc_foods:items/seeds/salad_seed` -
  and its models then name the sprite, which is no texture file. `IndexedRendererContext` reads every
  texture id as a sprite of the items atlas first and the blocks atlas second, as the client looks a
  model texture up, and reads the texture a `single` or `directory` source stitched it from, its
  sidecar included; an id neither atlas holds reads as itself. An `unstitch` region and a paletted
  permutation read their own id too, as neither is a whole texture file. Every vanilla sprite is
  stitched from the texture of its own id, so on a vanilla stack every id reads itself and no gate can
  see this: `AtlasSpriteResolutionTest` is the gate.
- **A chain picks by what is named, then reads once.** Where a render chooses among textures - an
  entity's baby, weathered, selected-state and declared textures, a player's cape before the elytra
  source before the static wings, a pack-rule wing tile before the equipment wing - the first
  candidate that names a texture is read and no other. A named baby texture no pack ships draws the
  checkerboard rather than the adult texture, and a named cape draws it on the cape and on the wings,
  as vanilla picks the texture from the subject's state and draws its missing sprite rather than
  another candidate. A candidate that names nothing still passes to the next, and a row that names no
  texture at all draws an empty frame.
- **A trim with a missing input is the checkerboard as a whole.** Vanilla's paletted permutation
  draws its missing sprite for a permutation one of whose inputs it cannot read, so the trim overlay
  is the stand-in sprite itself rather than a permutation of it.
- **A subject the game registers that draws nothing is no miss.** Air, a light block or a barrier
  block is known and holds nothing, and so is a definition rooted at `minecraft:empty` or a model that
  declares nothing to draw: each draws an empty frame, in the shape its missing picture would have
  taken, carrying only the decorations a slot's request names, and reports nothing. A fluid or a
  portal is not one of them. Its block model is blank because another renderer draws it, so the block
  and item renderers answer it as an id the index does not know - the missing picture and the report.
- **A reference that never became a texture draws as a lookup that fails.** A face whose `#variable`
  chain resolves to no texture is looked up by its raw reference, which no pack supplies, so it draws
  the checkerboard and reports that reference once as a missing texture - as vanilla draws its missing
  sprite on that face rather than leaving a hole. `ModelData#loadElementFaceTextures` hands such a
  face to the caller's second function, and the block, item and carried-block walks pass their one
  resolver for both. A flat face render reads the face its block's first element declares for the
  direction the same way, rather than falling through to the model's other sprites.
- **A face's resolver answers a buffer, never an empty.** A model's element walk *drops* a face whose
  resolver answers empty, so an empty there would hand back a subject with a hole in it where vanilla
  draws its missing sprite. The same holds for a layer, a pass or a foil: an empty there would draw
  the subject without it.
- **A texture miss never substitutes geometry.** A model that resolves keeps its own shape and
  substitutes only the texels of the face that failed - stairs with no plank texture are still stairs.
  Only an id neither index knows, a leaf naming a model no pack ships, and vanilla's missing item
  model lose their geometry, and each draws the unit cube.
- **The inventory slot shows that cube square-on**, a flat square of two colours, because a slot
  applies no rotation to it. An explicitly posed render answers at the pose the caller asked for, so
  the posed cube shows three faces at three shades and carries four colours where the slot carries
  two. That count is the cheapest way to tell the two pictures apart.
- **The slot lights the face it shows.** The missing model names no `gui_light`, so a slot draws it
  under `Lighting.ITEMS_3D`, and the face pointing at the viewer takes `Shading.ITEMS_3D_FACING`: the
  checkerboard's magenta `(248, 0, 248)` reaches the slot as `(127, 0, 127)`, which is what the client
  writes. `MissingMesh.icon` is that lit square; `MissingMesh.face` is the unlit one a single flat face
  render draws, a face render lighting nothing it shows.
- **The sprite is generated, not stamped.** It is the client's own selector,
  `(y < height / 2) ^ (x < width / 2)` with both halves integer division, so an odd dimension splits
  unevenly - seventeen texels is eight then nine. A literal that happens to equal it at even sizes is
  not it.
- **Nothing a subject names misses on a vanilla-only stack**, which is why no gate can see most of
  this and why a texture cannot be made missing by deleting the file - the renderer re-extracts it.
  Forcing the id to answer empty is the only way in, and the visual drivers take `-PhideTextures` for
  exactly that. A tooltip style is the exception: it names its own sprite ids, so a style naming a
  namespace no selected pack ships misses on any stack, and the tooltip sweep's styled vanilla row
  compares the stretched checkerboard against the client's own. A capture therefore sees the missing
  picture only where a driver is told to hide a texture or a tooltip style names art no pack ships,
  and `AtlasRenderer` reaches **zero** artifacts, so `AtlasRendererMissingTextureTest` is not a
  supplement to a gate there - it is the gate.
