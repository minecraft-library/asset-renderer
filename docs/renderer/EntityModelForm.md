# Entity model form

Load when touching how `entity_models.json` is loaded - `EntityModelLoader`, `EntityIndexBuilder`,
the axes, the overlay rows, `AppearanceGate`, carried blocks and the body pose a carried block or a
filled slot swaps in - or porting a new entity. Posing one is [pose/Catalog.md](pose/Catalog.md).
Indexed from [`CLAUDE.md`](../../CLAUDE.md).

`entity_models.json` is the normalized model form: one entry per base entity under a top-level
`models` map, carrying its `renderer`, its `axes` and its overlay rows once. How the generators
derive each member is [tooling/MeshDerivation.md](../tooling/MeshDerivation.md)'s and
[tooling/RestingSubject.md](../tooling/RestingSubject.md)'s; this is what the loader reads.

- **Geometry is named per option, never on the entry.** The member is `geometry`, and it sits at
  `axes.<axis>.options.<option>.geometry` and on each overlay row - there is no `geometry_ref`
  anywhere in the shipped bytes. Its value is a **factory coordinate**,
  `AllayModel#createBodyLayer`, resolved against `entity_geometry.json`, and the `@grow=`,
  `@scaled=`, `@fparam=`, `@baby=`, `@pose=`, `@iparam=`, `@rest=`, `@inflate=` and `@cleared=`
  suffixes name a derivation of that factory's mesh rather than a second factory.
- Axes are orthogonal dimensions, all option-encoded, resolved at render from `AppearanceOptions`. A
  `size` axis's default is the option-less domain member taken last-first, so a one-option axis
  answers the larger form.
- The index is keyed by plain entity id and nothing synthesises a `minecraft:<id>_<option>` key. Do
  not revive id-encoding as a convenience API - the keyspace is the vanilla entity registry, so a
  synthesised key and a declared one are indistinguishable. A family's coats ride the base row's
  `variant` axis in memory and are keyed by no id of their own.
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
  mule's saddle rows and the horse's, the skeleton horse's and the zombie horse's body rows, and
  nothing else in the corpus. Reading the baking class instead answers the
  wearer's `chest` gate for a mesh whose gated bones are reins. The layer poses under the wearer's
  style and tick, as vanilla runs every equipment model's own animation with its wearer's render
  state. The body pose its wearer takes while the slot is filled is a form of the wearer's rather
  than a member of the row: `axes.equipment.options.<slot>.pose`, which the tooling derives from the
  layer's own item getter - the happy ghast's harness, whose getter reads the very field the body
  asks `isEmpty` of.

**A selected carried block takes its wearer's arms with it.** The body pose its wearer takes while a
caller selects a block - the enderman's raised arms, the iron golem's offered flower, whose offer
counts down on the age clock from the moment it is selected - is a form on the `carried` axis,
`axes.carried.options.block.pose`, and the appearance swaps that form's pose and passes in for the
body's, as a filled equipment slot swaps in its `equipment` form, the carried one answering first.
Neither swaps where a shape form has already swapped its passes in or a size form a pose of its own.
Only a posed render moves; a bind render poses nothing, so a still render and the still references
draw the arms at rest.

- **A filled slot can hide a bone of its wearer's, and that is a toggle rather than a pose.** A row's
  `wearer_toggle` names the bone toggle the wearer's mesh takes while the slot is filled, and
  `AppearanceOptions.resolve` adds it to the selection for any selected slot, whatever its material,
  because vanilla's model asks only whether the stack is empty. The warm zombie nautilus is the one:
  its coat's corals and every part below them name `body_armor_item`, rest drawn, and hide under body
  armour. A baby wears no equipment, so it selects none.
- **A layer's toggles take the same selection the wearer's do.** One flip serves both, so an equipped
  saddle draws its reins for a `ridden` subject and its chest panniers for a `chest` one, and the
  layer's mesh takes the resting strip the body's already did.
- A bone name is never a raw Java field name; a miss falls back to `StringUtil.toSnakeCase`.
- **A `texture_by` axis answers for itself on an overlay pass, and the horse marking is one.** Its row
  draws the wearer's own mesh - `geometry` equal to the body's coordinate, which is what routes the
  body's pose to the pass and derives its bounds skip - and `AppearanceOptions.texture`, on its
  `MARKINGS` arm, reads the selection off `HorseMarking`, whose two columns mirror the adult and
  baby sheets vanilla binds each marking to as a record pair. It needs no gate of its own: an
  axis-carrying row whose ref resolves empty is already skipped, and the axis answers empty at `NONE`.
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
  are one shape - `(ageInTicks * k) % 1` on each axis - and the offset's own wrap is taken where
  vanilla takes it, into the argument, before the fetch wraps the texel: the coordinate a pass
  samples is the float sum of the authored coordinate and that offset, and an offset still carrying
  its whole turns would round the sum at a coarser step than vanilla's does. A charged wither's
  swirl is a stated gap - its offset is a swing rather than a scroll, so the generator refuses it -
  and nothing renders it, the animated corpus drawing it uncharged.
- A block an entity holds is tinted at the no-world-context point and never at a biome:
  `Biome.INVENTORY_DEFAULT`, which `EntityRenderer.buildBlockOverlayTriangles` passes directly. A
  plain block render resolves against `BlockOptions.getBiome()`, which defaults to
  `Biome.Vanilla.PLAINS`. A block-backed slot icon does not: see [Block icons](Blocks.md#block-icons).
- The carried-block path applies blockstate variant rotation and the icon path must not, because a
  carried block resolves a blockstate whose variant rotation is baked in.
  `EntityRenderer.buildBlockOverlayTriangles` appends it after the translate, so it applies first to
  the still-origin-centred cube, with both angles negated.
- **A carried block stands on the seated container, then the attached part's own step, and on no
  ancestor of the part's.** `MushroomCowMushroomLayer`, `SnowGolemHeadLayer` and
  `IronGolemFlowerLayer` take the part off the model and call its own `translateAndRotate` on the
  stack they were handed, which holds what the renderer composed and neither the root's step nor any
  intermediate part's. `EntityGeometryKit.resolveBoneAnchorMatrix` composes that: the chain of the
  step `PosePlayer.seat` names, then the part re-hung from it; a block attached to no part stands on
  the seat alone. Every shipped attached part hangs from no bone of its mesh, so today the anchor is
  the part's whole chain bit for bit, which `EntityGeometryKitTest` holds against the shipped table.
- A block drawn with no world position draws a fixed entry of its weighted variant list, seeded `42`,
  and `nextInt(size)` is the index because no shipped array carries a weight.
- That draw is resolved at index build - `BlockIndexBuilder.drawWithoutPosition` onto
  `Block.Variant.noPosition` - because a variants array is shipped data a pack can edit, where
  bytecode can be frozen into a table.
- A baby render draws the baby overlay form and skips block overlays. An overlay reaches a baby only
  when it declares a `baby` node, and a delta naming its own `geometry` does not inherit the row's
  `grow`, because the tooling already baked that mesh's deformation.
- `EntityModelLoader` is a thin orchestrator - the three `ResourceDocument.as` reads
  `EntityTables.read()` answers, handed to `EntityIndexBuilder.assemble`, which owns the geometry join,
  the mesh surgery, the axes pivot, the per-variant fold, the grouping and every leaf decode.

## Porting a new entity

Always check whether the subject's renderer overrides `setupRotations` or carries a scale override,
and replicate it in the kit's transform chain. How a renderer's steps reach the mesh, and why a
shift and a transform may not both answer, is [pose/Container.md](pose/Container.md).

- Only two `setupRotations` translates survive the harness rest pose; the rest sit behind gates the
  frozen animation state makes false.
- The squid's age-conditional Y shift is baked into the **mesh** by `EntityMeshShift`, because the
  renderer and the canvas-sizing bounds walk both read the mesh. The pufferfish's is an expression,
  so the walk declines it and it stays latent.
- A `setupRotations` shift is invisible unless the canvas is group-unioned, because the fit centres
  the bounds it was handed.
- `MeshTransformer.scaling(F)` is baked by the tooling and is exact - do not look for a runtime
  workaround. It rewrites only the `MeshDefinition` root, so it collapses to a root pivot and a root
  scale. The geometry key spells that factor as `@scaled=F` only where `LayerDefinitions.createRoots`
  applies it as it registers the layer; a factory that scales its own result leaves the key without
  one - the polar bear, the ghasts and the elder guardian - so read the factor off the bones.
- `PartPose.scaled(F)` scales the pivot as well as the three scale components where `withScale(F)`
  scales only the three; the asymmetry is vanilla's.
- `BabyMeshTransform` is the second whole-mesh transformer and is not a scale: it rewrites a pose per
  top-level bone through one of two operators chosen by name, and it translates **before** it scales.
- A vanilla field spelled `BABY_TRANSFORMER` is not always one.
