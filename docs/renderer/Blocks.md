# Blocks

Load when touching a block item's icon - `Block#modelIcon`, `BlockRenderer.Isometric3D`, `GuiIcon`,
`ItemModelTreeLoader.deriveBlockItemModels` - how a slot draws an item, a special model's
`SpecialTransform`, or `BlockIndexBuilder.resolveBlockStateModel`. Indexed from
[`CLAUDE.md`](../../CLAUDE.md).

## Block icons

A block item's inventory icon is the item model its `minecraft:item_model` component names, baked at
the identity model rotation, so it carries no variant model, no variant rotation, no `uvlock` and no
multipart assembly.

- `Block#modelIcon` is the whole gate, true exactly when `ItemModelTreeLoader.deriveBlockItemModels`
  has an entry. `BlockRenderer.Isometric3D` then renders `Block#model()` with a null variant when the
  caller names no state; a named state gets the full blockstate treatment.
- An item has an entry when its definition, walked at the neutral `ItemModelContext.gui()` context,
  lands on a `minecraft:model` leaf naming a block model without passing through a composite -
  `ItemModelNode.Resolution#blockModel`. A plain root naming one qualifies, and so does a dispatch
  whose neutral branch names one: `beehive`, `bee_nest` and `test_block` select on a block state and
  draw their fallback block model in a slot. A composite's icon paints every child, so it is not one
  block model. The icon's `display.gui` follows the same leaf: `BlockIndexBuilder.iconGuiFor` reads
  a plain or special root's leaf, and a dispatch root's only where it passes this test.
- A slot icon takes its item definition's tints, as `CuboidItemModelWrapper.update` calculates them
  in every display context. `GuiIcon` hands them to `BlockRenderer`'s icon build, which gives each
  face of the identity build the tint its tintindex names, white where the definition names none.
  Neither `BlockOptions.getBiome()` nor the block tint table reaches it, and a stand-in keeps the
  plain render's tint. Mangrove leaves are where the two part: `0xFF92C648` in the slot,
  `0xFF48B518` on the block and in the harness's block references.
- A block with no entry has a flat sprite, a special renderer, or (the two dripleafs) an item model
  whose geometry comes from a block parent as its vanilla icon; the 3D render is this pipeline's own
  stand-in at the default state's orientation.
- A plain slot - no stack, or one that chooses no branch - draws an item-index id's own item only
  where its definition's walk lands on that item's model or on a special, or it has no definition.
  The index builds the item from a `models/item` file, and a definition may point past it, so
  anywhere else the slot draws what the walk lands on, as a held render and a stack do: another
  model, the missing model, vanilla's missing item model, or nothing.
- An item-index id whose model declares elements and which the block index carries draws its
  inventory icon through the block branch, as the block's own icon, wherever a plain slot draws its
  own item; `GUI_2D` draws the model's elements, as a slot draws any model built from them.
- A slot draws a model built from elements as those elements, whether the item index carries it or
  an item definition's walk lands on it - a stack's branch, a composite's layer, a block-backed id's
  choice. Vanilla's geometry is the nearest one up the parent chain, so a `layer0` bound beside
  elements draws nothing. The model is posed by its own `display.gui`, unturned where it declares
  none, and lit as its `gui_light` says: `side` through `Shading.relightForItems3d`, vanilla's
  `ITEMS_3D`, and `front` through `Shading.relightForItemsFlat`, its `ITEMS_FLAT`. `GuiItemAtlas`
  binds one entry per stack, read off its first layer, so every element layer of a composite takes
  its first layer's light. A flat layer's sprites take the shade that entry gives the face of
  vanilla's generated slab pointing at the viewer - in full under `ITEMS_FLAT`, and
  `Shading.ITEMS_3D_FACING`, about half, under `ITEMS_3D` - folded into the layer's tint in one
  rounding.
- A slot `composite` holding a layer built from elements draws every layer in one depth pass, as
  vanilla's slot draws a whole stack into one depth buffer: each element layer at its own
  `display.gui`, each flat layer as its sprites on the front face of vanilla's generated slab, half
  a pixel in front of the model's centre, and either missing model as the missing cube. A
  composite of sprites alone stacks them in paint order, which is what one pass gives sprites that
  share a plane. A flat layer turned by its own `display.gui` shows that face alone, where vanilla
  would show the slab's edges too.
- `ModelData.getGuiLight` is the nearest file's up the parent chain to declare one.
  `builtin/generated` names `front`, and a chain naming none is `side`, vanilla's
  `ResolvedModel.DEFAULT_GUI_LIGHT`.
- The block's own icon keeps block lighting whatever its model's `gui_light` names, because the
  harness lights every block icon `ITEMS_3D` and its references are the ground truth -
  `calibrated_sculk_sensor` declares `front`, and both sides light it as a block.
- The harness applies the identical predicate to the same shipped `items/<name>.json`, deliberately
  not a runtime proxy, so the two repos cannot drift on which blocks are icons. It walks the file the
  same way - a condition to `on_false`, where the neutral context sends every condition vanilla
  ships, a select to its `gui` display-context case, its overworld dimension case or its fallback, a
  range dispatch at value `0`, a composite refused - and takes the quads vanilla's own
  `ItemModelResolver` lands on for a GUI stack.
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

## Decisions that stay closed

- Do not wire `SpecialTransform` - a block entity's `presentation()` transform already carries that
  pose, so applying it again double-applies.
