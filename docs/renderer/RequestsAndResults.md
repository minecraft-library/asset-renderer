# Requests, results and the vocabulary they name

Load when touching `call/**` - the `*Options` bags, `RenderResult` and the composite results, the
`LayerSlot` enums - how a render is laid down (the convention its buffer holds, or `BlendMode.ADD` for
an additive placement), the item stack an item render reads and an item definition's walk
(`ItemModelDispatch`, `ItemModelNode`), or the vocabulary under `vanilla/appearance`.
`ArmorSlot` and `ArmorForm` have their rules in [Armour.md](Armour.md).
Indexed from [`CLAUDE.md`](../../CLAUDE.md).

**`call/` holds what crosses a render call.** `call/request/` is what a caller supplies for one render
call: the `*Options` bags, whether a renderer takes one whole or another bag nests it - `OutputOptions`,
`AnimationOptions`, `ArmorOptions`, `SkinOptions`, `TextureOptions`, `DecorationOptions`,
`AppearanceOptions` - the `RenderOptions` marker every whole bag implements, and the values a caller
builds to fill one, `ArmorPiece`, `BannerLayer`, `ThemeStyle` and their like. `call/slot/` holds the
per-renderer `LayerSlot` enums a caller's `layerDecorator` splices against. `call/result/` is what a
render hands back: `RenderResult`, every renderer's answer, holding the image and a `Substitution` for
each stand-in drawn in it, and the narrower answers of the renderers that place other renders -
`AtlasResult`, which adds the sidecar placing each tile, `GridResult`, which adds where each tile was
drawn and the stand-ins it drew, and `LayoutResult` and `MenuResult`, which add where each child was
drawn and the child's own result. `Renderer` and the renderers sit in the root.

- **A type only one result names nests in that result**, as a type only one bag names nests in that
  bag: `Sidecar`, `Tile` and `Skipped` nest in `AtlasResult`, and each composite's placement record
  nests in its result. A type a renderer alone reads and never hands back nests in that renderer.
- **`RenderResult` is sealed, and it is not an image.** Its implementations sit beside it; a caller
  holding pixels drawn elsewhere wraps them with `RenderResult.of`. The image library and the frame
  compositor read animation off the image's own type, so a result is unwrapped with `image()`
  wherever an image is wanted.
- **A composite carries its children's results.** A `LayoutOptions` child and a
  `MenuOptions.MenuSlotContent` take a result, and the composite's own result places each child and
  keeps its result, so it names which child substituted what. A `GridOptions.GridTile` takes a result
  or a supplier of one, which the grid calls on the worker that draws the tile, and a grid's cell keeps
  the stand-ins its tile drew rather than its result, so an undecorated static sheet lets each tile's
  pixels go once drawn and a sheet of thousands never holds them all. The bags take a result, which is
  why `call/result` sits below `call/request`; a placement keys its child by an index, a layer slot or
  a mark, so nothing in `call/result` names a bag.
- **A composite takes its formula from the two buffers; a render is a canvas and a texture is
  straight.** Every renderer draws onto `PixelBuffer.canvas`, premultiplied as the client's
  framebuffer is, and reads textures straight, as files hold them. A canvas laid onto a canvas
  composites by the premultiplied formula, keeping a translucent pixel as it was over a transparent
  one and giving over an opaque one what drawing the subject there gives - so the grid's cells, the
  frame compositor's placements, a menu's among them, the supersampled frame's scale-down, a stack
  count's scratch and `Background.composite` lay a render as it was drawn. A straight texture
  or supplied picture laid onto a canvas composites by `NORMAL`, the GPU's blend of a straight texel,
  and the picture the frame compositor builds up from an animation's partial frames is composed in
  the convention the animation holds. Layers composed into a straight sheet - an equipment texture,
  an item's tinted layers, a banner, a slot's sprites - composite by the straight formula, which is
  what drawing each onto the framebuffer leaves. The raw writers (`Rasterizer`, `NineSliceKit`,
  `GradientKit`, `GlintKit`) hard-code the formula for a canvas and draw only onto one. `NORMAL`
  itself is not the defect: straight-alpha source-over in its place moves every translucent block and
  entity off the vanilla client's renders. A placement may instead be additive - the text field's
  highlight band, which the client fills through its additive GUI pipeline - and the frame compositor
  and the grid's static sheet add it to what lies under it with `BlendMode.ADD`.
- **What a render records into is not a result.** `SubstitutionCollector` lives in `content/index/`,
  beside the `RendererContext` that carries it.
- **A bag one renderer reads in several styles says which style reads what.** `TextOptions` carries a
  tooltip's and chat text's `lines` and a book's `page`, and each style reads its own and ignores the
  other's, as chat text ignores the tooltip's chrome, padding and alphas; each `TextOptions.Style`
  constant's javadoc names what it reads and what it does not, and the `lines` and `page` fields name the
  styles that read them. A book's page goes in whole, one text component with its section-sign codes
  still in its text, because the client wraps a page before it reads its codes: lines read before the
  wrap - `toLines`, `fromLegacy` - cannot be wrapped as the client wraps them, so the page is never
  handed in as lines.

**A request is drawn under ceilings, and a request past one is refused with a `RenderException`.**
A bag is a few numbers naming a far larger thing, so each number that sizes an allocation is held to a
ceiling, tested in `long` arithmetic before anything is allocated. The ceilings are the constants of
`engine/frame/RenderLimits`, where each is given with the figure it was set from.

- **A shared bag holds its own ceiling, on the accessor.** `OutputOptions.getCanvasSize()` refuses a
  canvas over `MAX_EDGE`, 16,384 pixels, and `getSupersample()` a factor that takes the raster's edge
  over it; `AnimationOptions.getFrameCount()` refuses a count over `MAX_FRAMES`, 4,096. Six renderers
  and the kits under them read those bags, so the rule sits with the field, as the sub-1 supersample
  clamp does, and a reader added later cannot be left without it.
- **What a render bakes is held together.** `Timeline.bake` refuses frames that, at the size the pass
  rasterizes them, come to more than `MAX_PIXELS`, 268,435,456 - `MAX_EDGE` squared - so a large
  canvas is bounded in frames and a long strip in size. It reads the pass rather than the bags, so a
  canvas sized from an entity's bounds, a sub-tick loop's multiplied frames and any size computed past
  an `int` are held by it too.
- **A renderer that sizes its own canvas refuses where it sizes it.** A menu's chest reads at most
  `MenuOptions.MAX_GRID_SIDE` rows and columns, 256, tested in `MenuOptions.screen()` before a cell is
  laid out; the text renderer holds the canvas its lines lay out, over its frames, to `MAX_PIXELS` and
  to no edge of its own, a long column of lines being a narrow canvas; an atlas tile is a render and is
  held to `MAX_EDGE`.
- **A sheet's ceiling is its buffer's.** A grid, and an atlas rendered whole, is one buffer as large as
  the tiles laid out in it, so its width, its height and its pixel count are each held to what an
  `int` holds and to nothing smaller - the vanilla atlas, 3,531 tiles, is 232 megapixels at a 256
  pixel tile and 927 at 512. It is refused before any tile is asked for. An atlas written to a file is
  held a band at a time, and it is the band that has to fit.
- **Compressed NBT is inflated under `ItemContext.MAX_NBT_BYTES`**, 104,857,600 bytes, the quota the
  26.1 client reads compressed NBT under, and read no further than that; the refusal is the
  `NbtException` a payload that is not NBT raises. Bytes handed over uncompressed are read as they are.
- **A fetched texture is cached under what was fetched.** `PlayerRenderer` keeps a URL-sourced skin,
  cape or elytra sheet under the kind it is read as and the texture hash its URL names, which is all
  of the URL the fetch reads, and holds the 1,024 read most recently.

The ceilings are constants, not options, and each is set for the largest render the library's own
drivers have a use for, with room above it, rather than for the heap any one caller runs in. A caller
that forwards sizes from its own users holds them to its own budget first.

**What a bag names is not a bag.** The vocabulary a selection is drawn from is domain data whichever
side supplies it, and the pipeline reads it too, so it lives below `call/request` - a vanilla fact
under `vanilla`, a decoded record under `asset` - and `call/request` points down at it, never the
reverse.
`vanilla/appearance/` holds the entity axes (`Age`, `Size`, `Flag`, `TintAxis`, `TextureAxis`,
`HorseMarking`, `IronGolemCrackiness`, `CopperWeathering`, `TropicalFishPattern`, and the villager
rosters under `villager/`), `Axis`, the face the gateable ones share, and `AppearanceGate`, the
parsed `when` a selection is tested against. `vanilla/equipment/` holds the worn-armour vocabulary -
`ArmorSlot`, `ArmorForm`, `LayerType`, `ArmorMaterial` - `vanilla/DyeColor` is the palette, and
`vanilla/Biome` the biomes. Each of those two is one sealed type holding the vanilla rows as a
nested `Vanilla` enum and the caller's own as a `Custom` record, since both arms answer the same
accessors.

- A value type exactly one bag names **nests inside that bag** rather than sitting beside it -
  `MenuOptions.MenuSlotContent`, `GridOptions.GridTile`, `FluidOptions.CornerHeights`,
  `LayoutOptions.Layout`, `AnimationOptions.Schedule`.
- **The bag answers for its selection.** `AppearanceOptions` reads the axes and answers every
  appearance question itself: `resolve(Entity)` folds a definition into what this appearance
  draws, and `passes(AppearanceGate)`, `applies(PoseStyle)`, `admits(String)`, `selects(Axis)`,
  `tint(TintAxis)` and `texture(TextureAxis, ...)` say what it selects. A gate, an axis, a style
  row and an entity definition are tested or folded against a bag without depending on one, so
  neither the vocabulary nor the `Entity` record names a request. The two contexts an item render
  hands down ride on `ItemOptions` and live in `call/request/` with it: `ItemModelContext`, which walks
  an item-definition tree to the branch that renders with `resolve(ItemModelTree)`, and
  `ItemContext`, which answers whether a pack's CIT rule applies with `matches(CitRule)`.
- **`ItemContext` is the one item stack a render reads**, a Minecraft 26.1 stack
  (`{id, count, components}`, `ItemContext.ofStack`), and CIT, the dispatch walk's component tests,
  the tooltip and the `minecraft:dye` and `minecraft:custom_model_data` tints all read it. The walk
  takes the stack's patch, and its item id, wherever its `ItemModelContext` carries none of its own,
  so a context supplied for another input still walks the stack; `ItemOptions.components()` is that
  one patch, and both tints read it too - the dye after an explicit `DecorationOptions.leatherColor`,
  the custom-model-data tint its `colors` entry at the tint's index. The item id is the
  `minecraft:item_model` every 26.1 item holds by default, the one default component the walk
  knows. A walk the stack does not steer proceeds at the context without it, the baked fast path
  included. No other stack shape is mapped: a pre-1.20.5 stack carries none of the components a
  definition tests but the item model its id stands for.
- **An atlas draws a `custom_model_data` branch as its stack**, through that same `ItemContext`. After
  each id's plain tile, `AtlasRenderer` lists every distinct float that reaches an entry of a
  `range_dispatch` on `custom_model_data` in the id's definition and draws the stack carrying it, so
  the tile is the slot the client draws for that stack - a pack whose custom items are thresholds of
  vanilla definitions shows them all. The row's `id` is the stack in the client's command syntax and
  its `model` the model the walk lands on, which `AtlasOptions.filter` tests in place of the id; the
  floats are listed by index and then by value, and one that draws what the plain icon or an earlier
  one draws adds no tile. The item index is untouched: a branch is no item, and `knownItemIds()`
  answers only ids `findItem` does.
- An `asset` type that takes a bag or a context imports uphill, because `call/request` sits above
  `asset` and `vanilla` in the tier order, and `TierOrderTest` fails on any such edge its ledger
  does not hold. The question goes on the bag or the context instead, as above, or on the index
  that asks it - the pack rules' glint and connected-texture lookups live in `content/index/`; do
  not clear the edge by moving the bag or the context out of `call/request`.
- **A type moved between `call/**` and `asset/**` carries its own reach with it.** Both claims over
  those trees are `derived`, so each answers the reference graph for the changed FILE and where the
  file sits decides nothing. What the move owes is the regeneration: the claim on its new package
  derives a different trigger path, and `python parity/scripts/parity triggers` writes it.

## Decisions that stay closed

- **Do not widen the component tests an item definition's walk answers.** It answers a
  `custom_data` test, a presence test, `has_component`, and a select on `custom_name`, `dyed_color`,
  `lore` or `item_model`; of an item's default components it knows only `item_model`, its own id.
  The fourteen other predicate types load and fail, so the walk takes `on_false`; a select on any
  other component takes its fallback; and a special model's required field must be present, with its
  value left undecoded. That set is every component test the definitions on hand use: the 26.1
  client's 1506 item definitions test `has_component` twice, on `dyed_color` and
  `lodestone_tracker`, and Hypixel+ 0.23.4's 328 test `custom_data` in 4000 nodes, select on
  `dyed_color`, `custom_name` and `lore`, and test `has_component` on `dyed_color` and
  `lodestone_tracker`. The rest test gameplay
  state rather than how an item looks, and an item's other defaults bind only when the game loads its
  registries, so answering either means shipping and versioning vanilla data that changes no icon.
  An undecoded special-model field changes only what a malformed definition does, which loads here
  where vanilla refuses it.

  Two tests sit outside that set, as a single exception that does not reopen it: a `condition` on
  `custom_model_data` reads the stack's `flags` entry at its `index`, and a `select` on it the
  `strings` entry at its `index`, as the 26.1 client does. A user's ItemsAdder pack uses the flag
  condition 2016 times to switch a composite's layers, and answering either without the stack draws a
  stack that sets a flag or a string with the layers the client draws for one that does not. The
  exception covers those two reads of `custom_model_data` and nothing else; any further test is held to
  the rule above.
- Do not model an unset villager level as "no badge" - `VillagerData` raises any level to one, so
  `VillagerProfession.drawsBadge()` and `isBaby` are what suppress one.
