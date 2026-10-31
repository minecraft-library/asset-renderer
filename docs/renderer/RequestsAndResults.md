# Requests, results and the vocabulary they name

Load when touching `call/**` - the `*Options` bags, `RenderResult` and the composite results, the
`LayerSlot` enums - how a render is laid down (the convention its buffer holds), the item stack an
item render reads and an item definition's walk (`ItemModelDispatch`, `ItemModelNode`), or the
vocabulary under `vanilla/appearance`.
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
  frame compositor's placements, a menu's static composite, the supersampled frame's scale-down, a
  stack count's scratch and `Background.composite` lay a render as it was drawn. A straight texture
  or supplied picture laid onto a canvas composites by `NORMAL`, the GPU's blend of a straight texel,
  and the picture the frame compositor builds up from an animation's partial frames is composed in
  the convention the animation holds. Layers composed into a straight sheet - an equipment texture,
  an item's tinted layers, a banner, a slot's sprites - composite by the straight formula, which is
  what drawing each onto the framebuffer leaves. The raw writers (`Rasterizer`, `NineSliceKit`,
  `GradientKit`, `GlintKit`) hard-code the formula for a canvas and draw only onto one. `NORMAL`
  itself is not the defect: straight-alpha source-over in its place moves every translucent block and
  entity off the vanilla client's renders.
- **What a render records into is not a result.** `SubstitutionCollector` lives in `content/index/`,
  beside the `RendererContext` that carries it.

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
  the tooltip and the `minecraft:dye` tint all read it. The walk takes the stack's patch, and its
  item id, wherever its `ItemModelContext` carries none of its own, so a context supplied for
  another input still walks the stack; `ItemOptions.components()` is that one patch, and the dye
  tint reads it too, after an explicit `DecorationOptions.leatherColor`. The item id is the
  `minecraft:item_model` every 26.1 item holds by default, the one default component the walk
  knows. A walk the stack does not steer proceeds at the context without it, the baked fast path
  included. No other stack shape is mapped: a pre-1.20.5 stack carries none of the components a
  definition tests but the item model its id stands for.
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
- Do not model an unset villager level as "no badge" - `VillagerData` raises any level to one, so
  `VillagerProfession.drawsBadge()` and `isBaby` are what suppress one.
