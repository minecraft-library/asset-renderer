# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

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

## A named module waits on module names in nine upstream JARs

A consumer on the classpath reads every public type, so it faces every package that holds one. Only
a JPMS `exports` hides a whole package, and only from a consumer that is itself modular. Dropping
`public` from the types nobody outside their package names hides none: every package that holds a
type keeps one another package names, bar `author.audit` and `author.install`, whose types are the
entry points a consumer audits and installs styles through. Parked by the owner.

The blocker is upstream. The nine JitPack libraries the build takes on `api` - six from
simplified-dev, one from simplified-api, two from minecraft-library - carry no `module-info` and no
`Automatic-Module-Name`, so each is an automatic module named off its file name, and a JitPack pin
is a commit sha that is not read as a version. A sha opening with a digit leaves a name segment that
is not a Java identifier, so no name derives at all; one opening with a letter derives a name that
carries the sha and changes with every pin bump. The pins in force derive no name for seven of the
nine and a sha-bearing name for the other two. A named module cannot resolve on the module path
until each of the nine publishes an `Automatic-Module-Name` or its own `module-info` - a JitPack
build and a pin bump apiece.

Past that, `module-info.java` owes more than its `exports`, whose set is a signature-closure probe
nobody has run over the packages the README's usage section names:

- `provides dev.simplified.gson.GsonContributor with
  lib.minecraft.renderer.content.json.RendererGsonContributor`, because a named module ignores
  `META-INF/services` and a modular consumer would otherwise lose the registered adapters silently;
- `opens ... to com.google.gson` for every package Gson reflects into - `asset.mesh`,
  `asset.model` and every `content` package that decodes JSON at least;
- `requires static` for the annotation processor's vocabulary, for the `@Parity` vocabulary, whose
  JAR needs a module name of its own, and for `jdk.incubator.vector`, which keeps `SimdSupport`'s
  scalar fallback and a consumer owing no flag;
- a `blindness.json` rule or `no_reach` entry covering the file, since the plan refuses a path no
  rule covers.

Nothing in the suite runs on the module path, so a missing `opens` - an
`InaccessibleObjectException` at run time - or a `provides` that drifts goes unseen without a
module-path consumer that renders something.

## A small armour stand wears the adult elytra, where vanilla draws the baby one

`WingsLayer.submit` takes the baby elytra model on the render state's `isBaby`, which
`LivingEntityRenderer.extractRenderState` copies from `LivingEntity.isBaby()`, and 26.1's
`ArmorStand.isBaby()` returns `isSmall()`. `ArmorStandRenderer` builds a `WingsLayer` and extracts
through that super call, so vanilla draws a small armour stand's wings with the half-scale
`ELYTRA_BABY` mesh, pivoted at `(+-2.5, 12.008, 2)` like any baby's. `HumanoidArmorLayer` picks the
stand's small armour set on the same flag, and the shipped armour row already records the selection
that reaches it: its second shell's `when` is `size=small`.

The renderer takes the wing age from `AppearanceOptions.isBaby()` - both canvas folds in
`EntityRenderer.renderEntity` and the `WINGS` feature (`EntityRenderer.java:308`, `:361`, `:608`) -
which answers `age == Age.BABY`. The stand's small form is its `size` axis, geometry
`ArmorStandModel#createBodyLayer@baby=HumanoidModel.BABY_TRANSFORMER`, and its `age` axis holds
`adult` alone, so a small armour stand wearing an elytra draws the adult wings. The same read hands
the baby wings to a subject whose age knob says baby and that has no baby form - a skeleton, a
full-size stand - where vanilla's `isBaby` is false for both. No reference draws a stand wearing an
elytra, so nothing measures any of it.

It settles when the wing age follows what vanilla's `isBaby` answers for the wearer rather than the
age knob alone, and a harness row for a small armour stand wearing an elytra measures it.

## A posed salmon of either non-default size throws at render

The salmon's small and large forms draw `SalmonModel#createBodyLayer@scaled=0.5` and `@scaled=1.5`,
meshes flattened at 0.5 and 1.5, under the row's own `SalmonModel` pose, whose container closes on
the ground-frame step `y: -24.016`. `PosePlayer.seatUnderContainer` refuses a non-zero container
position on a mesh flattened at any factor but one (`PosePlayer.java:671-676`), so
`minecraft:salmon` at `Size.SMALL` or `Size.LARGE` under `idle` or `stride` throws
`RendererException` - "the container of a mesh flattened at '0.5' is placed on 'y', which its seat
carries no anchor to answer", '1.5' for the large. `bind`, the default style, renders both sizes,
and `Size.MEDIUM` poses. These two are the only forms in the shipped tables that pair a flattened
mesh with a pose placing its container.

No test and no stored reference poses either size. The `idle/` and `walk/` reference sub-trees hold
the salmon at its declared medium size alone, the `entities/` sweep draws `~size=small` and
`~size=large` at bind, and `StyleRegistrarFormTest` keeps its container probe to meshes flattened at
one by design.

Vanilla places that step unscaled. `LayerDefinitions` bakes `SALMON_SMALL` and `SALMON_LARGE`
through `MeshTransformer.scaling`, which rewrites the root part's pose alone -
`scaled(F).translated(0, 24.016 * (1 - F), 0)` - and `SalmonRenderer` swaps the three baked models
at submit without scaling anything. `setupRotations` and `LivingEntityRenderer`'s
`translate(0, -1.501, 0)`, which is the ground-frame step, act on the pose stack above that root, so
vanilla applies the `-24.016` as it is at every size. The seat stands where that pose stack stands,
above top-level bones that already carry the root's factor and anchor, so a translate placed raw -
crossing neither - reproduces vanilla. The refusal answers the one alternative its javadoc weighs,
the factor alone, while the rotation arm beside it already seats a turn above the root with no
anchor; `PosePlayerTest.aFlattenedContainerPlacementRefuses` pins both.

It settles when the seat places a container step's position raw on a flattened mesh and the refusal
goes. A size form carrying a pose of its own would not settle it: both salmon size options name
`SalmonModel`. Raw placement is exact for the renderer's steps and the ground frame, which are all
the salmon carries. A model writing its own root's position on a scaled mesh would instead need the
anchor its assignment replaces taken off, and the container carries no mark of where the renderer's
steps end and the root's begin - but no 26.1 model is that case: `AdultTurtleModel` and
`EnderDragonModel` write their root, both on meshes flattened at one.

## A small pufferfish plays the large pufferfish's pose, so its fins never move

The pufferfish's size options each name a pose of their own - `PufferfishSmallModel` and
`PufferfishMidModel`, beside the row's `PufferfishBigModel` - and nothing reads either.
`EntityIndexBuilder.sizeForm` copies the row, pose included, onto the size's mesh
(`EntityIndexBuilder.java:1108-1110`), and `AppearanceOptions.resolve` takes only a size form's mesh
and render scale (`AppearanceOptions.java:525-528`), so every size plays the row's pose. That pose
turns `left_blue_fin` and `right_blue_fin`, which the small mesh does not declare, and
`PosePlayer.evaluate` skips a write to a bone the mesh lacks (`PosePlayer.java:179-184`). The small
pufferfish's own `left_fin` and `right_fin` therefore rest under every style, where vanilla's
`PufferfishRenderer` submits `PufferfishSmallModel` at puff state 0 and its `setupAnim` rolls
`right_fin` to `-0.2 + 0.4 * sin(0.2 * ageInTicks)` and `left_fin` to the mirror. Nothing throws.
The medium plays the row's pose too and is right only because `PufferfishMidModel`'s table is
identical to `PufferfishBigModel`'s; the container - the renderer's bob and the ground frame - is the
same in all three.

It reads against two things the code states. `Entity`'s `pose` component is "joined from the model
class the model coordinate is headed with" (`Entity.java:77-78`), and the baby arm of
`AppearanceOptions.resolve` swaps the pose with the mesh because carrying the adult's pose onto a
baby mesh "would animate bones by the names the adult happens to share"
(`AppearanceOptions.java:478-482`). No stored reference poses the small size and no test reads its
fins: `idle/` and `walk/` hold the pufferfish at its declared large size alone, the `entities/`
sweep draws `~size=small` at bind, and `StyleRegistrarFormTest.theSmallPufferfishPlaysTheWovenRow`
asserts only the probe's own turn on `body`. It is the one form in the shipped tables whose own
pose differs from the pose it plays: the large tropical fish keeps its row's pose on purpose
(`EntityIndexBuilder.java:996-998`), and `TropicalFishLargeModel` and `TropicalFishSmallModel` write
identical tables.

It settles when a size form naming a `geometry` takes the pose its option names, and the size arm of
`resolve` takes the form's pose with its mesh, as the baby arm does. The install follows: a size
form whose pose is not the row's is woven as a form of its own rather than guarded as a mesh playing
the woven row, so a strict install refuses a bone the small or medium mesh lacks, as it refuses one
a baby mesh lacks.

## A block-backed item draws the missing model at GUI_2D and HELD_3D

`Gui2D.render` and `Held3D.render` (`ItemRenderer.java:224-227`, `:359-363`) ask the item index
alone, and an id it does not carry takes `missingItem`: the flat missing square at `GUI_2D`, the
missing cube at `HELD_3D`, each logged as "Missing model for '<id>' - drawing the missing-model
cube". Only `GuiIcon.render` (`:577-583`) falls through to `findBlock` and the isometric
`BlockRenderer`. So `minecraft:stone`, `minecraft:oak_stairs` and every other id whose item
definition names a block model draw the missing model held. That is 704 of the 1506 26.1 item
definitions, whose root is a plain `minecraft:model` naming `minecraft:block/*`, and three more
(`beehive`, `bee_nest`, `test_block`) name one under a select.

The item index cannot hold them. `ItemIndexBuilder.load` (`ItemIndexBuilder.java:78-87`) builds one
item per `models/item/*.json` and drops every model `rendersNothing`, and a block item ships no
`models/item` file. The two that do, `big_dripleaf` and `small_dripleaf`, parent a block model
(`minecraft:block/big_dripleaf`, `minecraft:block/small_dripleaf_top`), and
`ResolvedModels.mergeParentChain` (`ResolvedModels.java:173-188`) resolves a parent only within the
kind being resolved, so the block parent is kept unresolved and the item model arrives with no
elements and no `layer0`, is dropped as blank, and draws the missing model too. Vanilla resolves a
parent across the whole model namespace.

The docs claim what the code does not do. The class javadoc (`ItemRenderer.java:66-69`), `Held3D`'s
(`:326-330`) and `buildTrianglesAtTick`'s (`:427-429`) say a held block item builds real cubes
through `BlockGeometryKit.buildFromElements`. Under vanilla assets the element branch at `:450-463`
is reached by one model: `item/spyglass_in_hand` is the only item model whose parent chain, resolved
within `models/item`, carries `elements`. The id `minecraft:spyglass_in_hand` reaches it, because
the index keys an entry per model file, and `minecraft:spyglass` does not, because its held case is
the `display_context` fallback the next entry covers. `AtlasRenderer.hasFlatItemIcon`
(`AtlasRenderer.java:286-300`) relies on the opposite reading, that every item-index entry is a
flat sprite, to keep a block tile out of the atlas's block pass, and the `itemRender2D` task's
description (`gradle/visual.gradle.kts:90`) already says `-Ptype=icon` is the only mode that
answers for a block-backed id.

What vanilla draws for a held block item is the block model the item definition names, under that
model's `thirdperson_righthand`: `block/block`'s `[75, 45, 0]`, translation `[0, 2.5, 0]`, scale
`0.375` for most, with `thin_block`, `end_rod`, `heavy_core`, `template_lightning_rod` and
`template_shelf_inventory` authoring their own, and an item model like `big_dripleaf`'s overriding
the parent's. No stored artifact renders `HELD_3D` and the harness renders no third-person pose,
so no gate sees any of this, and the check that settles it is looking at the render.

It settles in one of two ways. `Held3D` routes a block-backed id the way `GuiIcon` does - the
block's `model()` where `modelIcon` holds, posed by the item model's `thirdperson_righthand` falling
back to the block model's - with the dripleaf pair needing their item model's parent resolved
across kinds; or the docs are corrected to say `HELD_3D` and `GUI_2D` draw item-index ids only and
a block-backed id is `GUI_ICON`'s.

## A baby flattened at a different factor from its adult reads the adult's offset value

A held bone offset - `LimbStance.offset`, neither rebased nor carried - lowers to a field whose
driver holds the authored pixels divided by the compiling mesh's flattened factor
(`PoseCompiler.java:1091`). `boneField` spells a field per row only for a rebased turn, a seat carry
or a scale (`PoseCompiler.java:1793-1798`), so an offset is spelled alike on every compile of an
install. `StyleRegistrar.woven` compiles the row first, and each later compile's drivers join the
appended row first-wins (`StyleRegistrar.java:445`), so a woven baby reads its adult's value and
lands the offset at the ratio of the two factors instead of at the authored pixels - and so does
each coat's baby, which takes the row baby's weave.

Eight of the 41 baby families differ: cat (0.8 adult, 1.0 baby), donkey (0.87 / 1.0), happy_ghast
(4.0 / 0.95), horse (1.1 / 1.0), husk (1.0625 / 1.0), mule (0.92 / 1.0), polar_bear (1.2 / 1.0) and
villager (0.9375 / 1.0). A baby cat moves 1.25 times what was authored and a baby happy ghast 0.2375
times. A baby is woven only for a style whose age admits one, and a built style is adult unless it
says otherwise. A rebased turn, a seat carry and a scale read per-form fields, and an additive turn
crosses no factor, so each is right on every form; a container offset refuses on all eight adult
rows already. No coat mesh and no distinct overlay pass in the shipped tables differs in factor from
its row, so the babies are the live case, and no test poses one of them under an offset -
`SeatInstallParityTest` installs every offset at the adult age, the horse's `rear` on a row
flattened at 1.1 among them.

It settles when an offset's field is spelled per form wherever the compile carries a coordinate, as
a rebased turn's is: the value a position delta holds depends on the compiling mesh's factor, which
makes it per-row data. The row keeps the shared spelling, so nothing a row compile spells moves.

## PoseAuditor's unreached list is not what a strict install refuses over

`PoseAuditor.validate` says what it reports unreached is what a strict install would refuse over on
the row's body and its overlay passes (`PoseAuditor.java:91-98`). It compiles the row
(`PoseAuditor.java:107`), and `unreached` adds the drops of a `compileLayer` over every entry of
`row.overlays()` (`PoseAuditor.java:189-201`). `StyleRegistrar.woven` compiles a different set
(`StyleRegistrar.java:370-407`), and the two part in both directions.

The audit compiles none of the forms the install weaves: the baby, for a style whose age admits
one; each coat drawing a mesh of its own, and each coat's baby; and the passes of the large tropical
fish's shape form. A strict install refuses over the drops of every one. Twenty-one of the 41
shipped baby meshes lack a bone their adult declares - the wolf's `upper_body`, the horse's `mane` -
so an every-age style writing one reports nothing unreached and a strict install then refuses it.
No coat mesh lacks a bone its row declares, so that arm adds nothing today. Nor does the audit run
`guardSize` (`StyleRegistrar.java:557-569`), which refuses a raw read a size mesh lacks and a scale
a shipped clip already writes there. Its javadoc names the baby and the coats as unpredicted, and
neither the shape passes nor the sizes.

The other way, `unreached` compiles passes a strict install never refuses over. A pass sharing the
body's pose instance is re-pointed and never compiled (`StyleRegistrar.java:470-473`), and the
breeze's wind pass and the slime's outer pass do that over meshes lacking body bones - so a turn on
the breeze's `head`, which `StyleRegistrarWeaveTest.breezeWindFollowsByInstanceAndFilters` installs
strictly, audits with `head` unreached. A distinct pass no written bone lands on is skipped with a
`weave-skip` warning (`StyleRegistrar.java:508-516`), and `unreached`'s own javadoc keeps reporting
it on purpose. `PoseAuditorTest.aLayerOnlyMissIsReported` pins that reading while its comment says
a strict install refuses: its humanoid body and wings-only pass share `EntityPose.NONE`, so the
install re-points the pass, and given a pose of its own the pass is skipped instead -
`StyleRegistrarWeaveTest.disjointLayerSkipsWhole` installs a style strictly over that very shape.

It settles when the audit walks the install's own sites - the same forms, in the same order,
compiled against the evidence the install uses - and its two javadocs agree on what counts: the
refusals `validate` promises, or every address some drawn mesh answers with nothing, as `unreached`
has it. The walk is `StyleRegistrar.woven`'s, which `author.audit` cannot call where it stands:
`TierOrderTest` orders `author.audit` at 17.3, below `author.install` at 17.4.

## Boxes built upright from a vanilla cube strip lay the DOWN face's rows reversed front to back

The player's six parts on both layers, the skull scope's head, the armour a player wears, the shells
an entity wears and the shield are built upright through `CornerPhase.BAKERY`: `BoxKit.buildBox`
lays each face's whole-strip crop with it (`BoxKit.java:111-125`), and `ShieldKit.addBox` pairs the
shield's UV corners with it. Each strip is addressed in vanilla's Y-down model frame and read
through `AxisSigns.HALF_X` - in `HumanoidPart.unwrap` (`HumanoidPart.java:252-264`),
`WornBox.Mesh.textures` (`WornBox.java:128-131`) and `ShieldKit.addBox` (`ShieldKit.java:292-303`).
A face map picks the right strip for every face and cannot turn one in its own plane, which DOWN
needs.

`BAKERY`'s DOWN walk (`CornerPhase.java:54`) puts the crop's top row on the box's max-Z edge, the
front of the upright body. Upright DOWN reads the cube's UP strip, and the 26.1 `ModelPart$Cube`
builds that polygon on corners 2, 3, 7 and 6 with its v arguments in inverted order;
`ModelPart$Polygon` gives vertices 0 to 3 the UVs `(u2, v1)`, `(u1, v1)`, `(u1, v2)` and
`(u2, v2)`, so the strip's top row lands on corners 7 and 6 - the cube's max-Z edge, which `HALF_X`
turns to the upright back. Walked corner by corner the way `CapeFrameTest` walks the cape, every
part disagrees at all four DOWN corners, rows reversed and columns agreeing, and agrees on the other
five faces, UP included. A mirrored cube - the 64x32 left-limb fallback in `HumanoidPart.crop`, or a
shell's mirrored cube - reverses columns only, so it keeps the same row reversal.

The entity cube path is exact. `EntityGeometryKit` walks each cube with `CornerPhase.POLYGON` in the
model frame and pairs its UVs through `BoneKit.resolvePolygonUv` (`EntityGeometryKit.java:222-238`,
`BoneKit.java:376-386`), which is vanilla's own pairing with no turn between. So an entity's body is
right and the armour it wears is not: `EntityArmorKit` builds the shell upright and turns it back
(`EntityArmorKit.java:260-269`), and the reversal survives the turn. The cape reads through `HALF_Z`
and is exact, its hem only because that strip is one texel tall; the shield's plate is exact for the
same reason, and its 2x6 handle underside is not.

No comparison against vanilla can see it. The harness references, the player and armour sweeps, the
raw pairs in `manifest.player-raw` and `pin.player-crc` all look from above, where no DOWN face is
drawn. The only stored views from below that draw an upright-built box are the skull's two flipped
portraits in `manifest.player-sheets`, `facing/portrait_-F.png` and `facing/portrait_MF.png`
(`PlayerRenderDriver.java:175-180`): `ViewMirror.FLIPPED` negates the pitch
(`Projection.java:221-236`), and the head's underside shows as a sliver under the chin. Steve's
head-bottom strip is not symmetric front to back - its last row carries the four-texel `492510` run
that continues the face's bottom row - and those two cells draw that run at the back of the head.
The zombie's four flipped `entity-projections` cells in `manifest.visual` also look from below, but
draw only the exact entity cube path.

It settles when the upright builds hand DOWN its strip with the rows reversed - after the crop in
`HumanoidPart.textures` and `WornBox.Mesh.textures`, and on DOWN's UV corners in
`ShieldKit.addBox` - under a test that walks every part, both layers, the fallback and a shell cube
the way `CapeFrameTest` walks the cape. It does not settle in `BAKERY` or `BoxKit`: `BAKERY` is
vanilla's `FaceBakery` walk, and the same builder lays blocks, items, portals and the missing mesh.
The two flipped portraits and `facing.png`, the sheet that holds them, are the only stored bytes it
can move.

## ModelTransform's javadoc states an order both compositions can claim

`ModelTransform`'s class javadoc (`ModelTransform.java:11-13`) says a transform "is applied in the
order translation, then rotation (XYZ Euler), then scale", and its field docs say the translation
applies before the rotation (`:39-40`) and the scale after it (`:44-45`). Read as `PoseStack` call
order that is vanilla's: `ItemTransform.apply` calls `translate(t)`, `rotate(rotationXYZ)`,
`scale(s)`, then `translate(-0.5, -0.5, -0.5)`, so the matrix is `T * R * S` and a vertex is
scaled, then rotated, then translated. Read as the order a vertex meets them, it is `S * R * T`,
the reverse. Both readers in the tree compose vanilla's order - `Held3D.displayMatrix`
(`ItemRenderer.java:505-518`) and `Camera.fromTransform` (`Camera.java:71-92`), whose isotropic
scale rides the lens - and each says so beside its code, so the class they read is the one place
that does not pick a reading.

The rotation field (`:33-34`) has the same ambiguity: "applied about X, Y, Z in that order". Both
readers build `Quaternionf.rotationXYZ(x, y, z)`, which is `Rx * Ry * Rz`, so a vertex turns about
Z first.

It settles by rewording the three docs in the vertex's order, naming the product: scaled, then
rotated about Z, Y and X, then translated, the `T * R * S` of `rotationXYZ` that
`ItemTransform.apply` builds. It is a javadoc-only edit, and the plan prices it as a code edit
anyway, because the graph is class-granular: `ModelTransform` plans fourteen artifacts, the dump
pair and every renderer that reads a display slot among them.

## HELD_3D resolves a display_context select at the gui case

`ItemOptions.itemModel` defaults to `ItemModelContext.gui()` (`ItemOptions.java:142`) whatever the
type, and `Held3D` passes it to `ItemModelDispatch.frameItems` unchanged. At the neutral context
with no CIT model override `resolveRenderItem` returns the baked item (`ItemModelDispatch.java:124`),
and the item index bakes every entry at the gui case, so a held render draws what the inventory
draws. The visual driver adds nothing: `ItemRenderDriver.callerItemModel`
(`ItemRenderDriver.java:151-160`) copies `neutral.displayContext()` into the context it builds, so
`-Ptype=held` renders at gui too.

Nine 26.1 item definitions select on `minecraft:display_context` with a held model different from
their gui one. The seven spears name `item/<material>_spear` for `gui`, `ground`, `fixed` and
`on_shelf` and fall back to `item/<material>_spear_in_hand`, whose parent `item/spear_in_hand`
carries the only non-uniform `thirdperson_righthand` scale in vanilla, `[1.7, 1.7, 0.85]`, at
rotation `[5, 270, -40]`. The spyglass falls back to `item/spyglass_in_hand`, the one vanilla item
model declaring `elements` itself, so a held spyglass is also the one vanilla route to `Held3D`'s
element branch. Held today, each draws its gui sprite under `item/generated`'s slot. The trident's
held case is a `minecraft:special` leaf, which `resolveRenderItem` answers with the baked item
(`ItemModelDispatch.java:132-135`), so it draws `item/trident` under any context until a special
trident mesh exists. The seventeen bundles select on `gui` alone and resolve their own
`item/<colour>_bundle` (`item/bundle` for the undyed one) either way.

A caller reaches the in-hand models only by building an `ItemModelContext` whose display context is
`thirdperson_righthand`. It settles when `HELD_3D` resolves the tree at the context it draws - the
held type supplying `thirdperson_righthand` where the caller left the default - or when the
`itemModel` javadoc and `Type.HELD_3D`'s say the caller owes it.

## Degrees convert to radians by a different float route than vanilla's

`EulerRotation.toRadians` (`EulerRotation.java:78-80`) is `(float) Math.toRadians(value)`: a double
multiply narrowed once. Vanilla multiplies in float by `0.017453292f`, the value of
`Mth.DEG_TO_RAD`, in `ItemTransform.apply`, both `CuboidRotation` element forms and
`Axis.rotationDegrees`. The two routes differ by one ULP for 66 of the 721 integer degrees in
`[-360, 360]`, the set symmetric about zero and opening at 27, 51, 54, 57, 67, 73 and 79, and never
by more than one.

Where `EulerRotation`'s radians feed, and what vanilla does there:

- Display transforms: `Held3D.displayMatrix` (`ItemRenderer.java:516`) and the block icon camera,
  `Camera.fromTransform` and `buildGuiDisplayTransform` (`Camera.java:89`, `:127`). Vanilla is
  `ItemTransform.apply`'s float multiply, and the harness's `BlockGuiTransform` copies it. The GUI
  lighting frame tracks the same angles (`Shading.java:358-360`, `Lighting.java:226-246`).
- Entity bones and cubes: `BoneKit.java:249` and `:281`, `PosePlayer.java:500-502` and `:549-551`,
  `Seats.java:339-341`. Vanilla converts nothing here, because `ModelPart` holds the radians its
  `PartPose` literal carries. The table carries degrees because the tooling's `GeometryParser`
  (`GeometryParser.java:2185-2187`, `:2202-2204`) writes `(float) Math.toDegrees(r)`, so the
  renderer's angle is a round trip that is not always exact. A posed channel takes the same round
  trip at render time: `PosePlayer.degrees` (`PosePlayer.java:600-607`) folds a written radian back
  to `(float) Math.toDegrees(value)`.
- A caller's model rotation: `Rasterizer.buildModelRotation` (`Rasterizer.java:1095-1097`), which
  has no vanilla counterpart.

Other sites take the same `Math.toRadians` route without going through `EulerRotation`. Element
rotation is one (`BlockGeometryKit.java:352`, where vanilla's `SingleAxisRotation` multiplies in
float). Blockstate variant `x`/`y` is another (`BlockGeometryKit.java:638-641`,
`EntityRenderer.java:937-938`), and vanilla builds no trig there at all: `BlockModelRotation`
holds an `OctahedralGroup`, an exact signed-permutation matrix. The rest are
`entity_models.json`'s `rotate_*` (`EntityIndexBuilder.java:751-753`), `Block.java:430` and
`:451`, `BlockRenderer.java:477`, `PortalBake.java:160`, and the oblique lens angles
(`Projection.java:93-111`).

Of the angles the 26.1 assets and the shipped tables carry, none that goes through a conversion
vanilla also runs lands in the differing set. The display rotations across every `models/block`
and `models/item` file take 40 distinct values, the element angles are 0, 22.5 and 45 either
sign, and the integer angles in `block_geometry.json`, `block_models.json` and
`entity_models.json` are clear too. The renderer's own `Projection.DIMETRIC` pitch, 26.565, does
land in the set, and has no vanilla counterpart. A pack's display rotation can land anywhere in
it.

The bone round trip is where a vanilla value is actually missed. `entity_geometry.json` carries 99
distinct non-zero angles. For 95 of them the recovered radian is a float constant in the client's
model and renderer classes; for one, the `SquidModel` `tentacle7` yaw of `-225`, it is the value
the model's tentacle loop computes in double and narrows; and for three it is one ULP off a
constant. `WitherBossModel`'s tail `xRot` of `0.83252203f` is stored as `47.7` and recovers
`0.8325221`, in both its geometries. The `AdultArmadilloModel` ear cubes' `zRot` of `0.0718f`
(negated on the right ear) is stored as `4.1138372` and recovers `0.07180001`. Switching to
vanilla's float multiply recovers those three and misses three others (`-47.4982`, `-39.99818`,
`54.99822`), so the route is not what loses them: degrees do.

Nothing here is shown to move a pixel. It settles on a measurement - whether a one-ULP radian moves
any byte a gate holds - and then either a recorded decision that it does not, or a change to the
route at the sites where it does.

## A table that parses and then fails to bind throws Gson's exception, not ContentException

`ResourceDocument.open` wraps a parse failure in `ContentException`
(`content/read/ResourceDocument.java:75-79`), but `ResourceDocument.as` (`:104-106`) hands the
parsed tree to Gson with no catch, and `JsonTree.as(Class)` in gson-extras does the same. So a
document that parses and then fails its typed bind leaves the loader as Gson's `JsonSyntaxException`
(a member of the wrong type) or `JsonIOException` (a cube refused at its strict tree read, which
`CubeGrowFactoryTest.nonFiniteMemberIsRefused` and `nonFiniteScalarGrowIsRefused` pin), past a
contract that promises `ContentException`:

- through `ResourceDocument.as`, every bundled table: `BlockGeometryReader.load`
  (`content/table/BlockGeometryReader.java:37`) and `BlockModelReader.load`
  (`BlockModelReader.java:40`), both documenting "missing or malformed"; `EntityTables.read`
  (`EntityTables.java:58`, `:77`, `:83`), "malformed"; `BlockTintsLoader.load` (`:50`), "cannot be
  parsed"; `GlintItemsLoader.load` (`:43`) and `PotionColorLoader.load` (`:47`), "missing or
  malformed"; and `BlockDefaultsLoader.load` (`:62`) and `BlockItemsLoader.load` (`:38`), whose
  `@throws ContentException` names only a missing resource or a missing top-level object;
- through `JsonTree.as`, the pack override channel: each `renderer/block_geometry.json` entry
  (`BlockGeometryReader.java:39`) and each `renderer/block_models.json` entry
  (`BlockModelReader.java:42`). `BlockRendererOverrides.gather` validates each override file's
  envelope and names the pack when it refuses one
  (`content/read/BlockRendererOverrides.java:104-110`), then merges the entries into one tree, so
  the typed bind comes after the pack is forgotten;
- and `BlockModelLoader.load` (`content/index/BlockModelLoader.java:108-114`) and
  `EntityModelLoader.load` (`EntityModelLoader.java:39-42`), which call those readers and repeat
  the contract without a catch.

The override channel is the one input a user authors, so it is where the failure is reachable in
practice; the bundled tables fail only if a generator ships a bad one. `CubeGrowFactory` is the one
adapter that binds through `fromJsonTree`, so `JsonIOException` can come only from a geometry table,
and `JsonSyntaxException` from any of them. A caller holding the documented contract and catching
`ContentException` or its parent `RendererException` catches neither. The binds under
`content/pack` are outside this: all but two catch `JsonSyntaxException` or wider, none
binds a cube, and the two that do not catch - `ResolvedModels.resolveModel`
(`content/pack/ResolvedModels.java:123`) and `BannerPatternLoader.parsePattern`
(`BannerPatternLoader.java:80`) - sit under no `ContentException` contract.

It settles when both bind paths wrap `JsonParseException` in `ContentException`, the override bind
naming the entry it refused, and a fast-suite case feeds each channel a document that parses and
does not bind.

## Ten places say the entity sweep equips nothing and draws no baby

`sweep.entity` holds 403 rows, and 46 of them are babies. Fourteen are adults in iron armour
(`armor=iron` on the armour stand, bogged, drowned, giant, husk, parched, piglin, piglin brute,
skeleton, stray, wither skeleton, zombie, zombie villager and zombified piglin), eleven are
saddled, and the rest of its `equip=` rows put a body item on a horse, a wolf, a llama and others,
with a horse's and a wolf's dyed (`equipment_color=blue`, `equipment_color=red`). What only
`ArmorSweep` draws is armour on a baby, which reaches vanilla's separate baby armour mesh, and dyed
humanoid leather - `ArmorSweep`'s own class javadoc says exactly that at `ArmorSweep.java:34-38`.
Nine places say otherwise, and a tenth gives the wrong cause:

- `harness/CLAUDE.md:14` - the entity sweep "equips nothing and ages nothing";
- `harness/CLAUDE.md:173` - the age-model pick is "invisible to the main sweep (every subject is
  an adult and the field starts adult)", where the main sweep's 46 baby rows are what the pick
  keeps order-independent;
- B57's `reason` in `src/test/resources/lib/minecraft/renderer/parity/blindness.json:1582`, and
  the rendered copy of it at `.claude/skills/parity-gate/references/blindness.md:713`;
- `HarnessConfig.java:107`, on `ARMOR_ONLY` - "equips nothing and renders no babies";
- `ArmorParitySweep.java:41-44` - the same, in a sentence that also says this pipeline stretches
  the adult `humanoid` sheet over a baby body, where `ArmorForm.BABY` draws a baby's four slots
  from `humanoid_baby` (`ArmorForm.java:73`);
- `EntityModelLoaderTest.java:332` and `:356` - "the parity harness renders no babies", where
  `trader_llama_creamy~age=baby` draws the baby caparison (vanilla's `LlamaDecorLayer` picks
  `TRADER_LLAMA_BABY` for a baby trader llama with no carpet) and five `villager~age=baby` rows and
  `zombie_villager~age=baby` draw the baby type pass;
- `EntityModelLoaderTest.java:477` - "the parity harness equips nothing", where eleven saddle rows
  sample the table the test guards;
- `EntityModelLoaderTest.java:511` - "the parity harness saddles nothing", where the donkey, mule,
  skeleton horse and zombie horse saddle rows draw the four saddles the test pins.

`ArmorSweep.java:44-46` gets the outcome right and the cause wrong. It says the bounds walker
measures the body only because the armour layer "holds an armor model set rather than a plain model
field, so the layer walk finds no mesh". `EntityBoundsWalker.findLayerModels` reads the set's
record components (`EntityBoundsWalker.java:944-961`); what keeps the shell unmeasured is the
request gate in `isLayerActiveForState` (`:764-772`), which admits an `ArmorLayer` only when
`AppearanceRequest.selectsAny(EQUIPMENT_AXES)`, and `ArmorSweep` never sets a request. The
parenthetical closing `harness/CLAUDE.md:14` draws the opposite conclusion from the same fact -
that the walker "can see the shell now", so `ArmorSweep`'s reserved margin is "belt-and-braces" -
and is wrong in its turn: on `ArmorSweep`'s subjects the margin is still the only thing that keeps
the shell in frame.

The four test comments also misplace what the canaries are for. The sweep does draw what they
guard, but only a parity capture compares it, so the fast suite would stay green over a lost baby
decor node or an inert material table - which is still why the tests earn their place.

It settles when each place says what the sweep draws and what only `ArmorSweep` draws: armour on
a baby, and dyed humanoid leather.

## harness/CLAUDE.md says humanoid armour has no LayerType and falls back to the body texture

The *Unworn equipment layers must pad no bounds* bullet at `harness/CLAUDE.md:176` lists humanoid
armour with wool and the mooshroom body as a layer with no `LayerType`, one that "genuinely has no
equipment texture and still falls back to the body texture". Neither half holds for the armour.
`EntityBoundsWalker.reflectLayerType` answers `HUMANOID_BABY` for a baby and `HUMANOID` otherwise
for any `HumanoidArmorLayer` (`EntityBoundsWalker.java:1210-1218`), so `equipmentTexture` resolves
the worn piece's own sheet through `EquipmentClientInfo` (`:1138-1160`) and the shell is measured
through it. When no sheet resolves, the non-null `LayerType` makes `walkLayerExtents` skip the
layer outright (`:294`) - the unworn-layer rule the same bullet opens with - and the body-texture
fallback is never reached.

The bullet misleads whoever is chasing an armoured reference's canvas: it sends them to the body
texture, where the measurement reads the armour sheet. It settles when the bullet drops humanoid
armour from the no-`LayerType` list and names it beside the wings as a layer whose type the walker
supplies itself - by age for the armour, from `HARDCODED_LAYER_TYPES` for the wings.

## EntityOverlayFitTest fits wings on a villager, which no vanilla renderer draws

`EntityOverlayFitTest.babyElytraFitsItsCanvas` and `adultElytraFitsItsCanvas` each put the elytra on
`minecraft:villager` (`EntityOverlayFitTest.java:115`, `:126`) and assert the canvas leaves no slack
around the wings. Vanilla constructs a `WingsLayer` in three renderers only - `HumanoidMobRenderer`,
`ArmorStandRenderer` and `AvatarRenderer` - and `VillagerRenderer`, an `AgeableMobRenderer`, adds
`CustomHeadLayer`, `VillagerProfessionLayer` and `CrossedArmsItemLayer` and no wings. So the two
villager cases hold the fit of an appearance no client produces. The class javadoc already says the
villager has no reference "because vanilla draws no wings on one", and keeps it anyway.

The renderer does draw it: the `WINGS` feature in `EntityRenderer` (`EntityRenderer.java:608-617`)
gates on `AppearanceOptions.isElytra()` alone and seats the wings on any `body` bone, while
`AppearanceOptions`' own field javadoc calls the knob "only meaningful for the humanoid roster that
can equip a chest item". The test therefore pins behaviour on an input outside the documented
domain, and a villager case failing would say nothing about any render vanilla can be compared to.

It settles when the villager cases give way to a wearer vanilla draws wings on - a zombie villager
keeps a villager-shaped head and has a baby form - so every elytra case is one a harness reference
could be taken for.

## The harness pixel dump walks layers the bounds walk places or skips differently

`EntityBoundsWalker.dumpTrianglesIfRequested` - the `[PX] TRI` trace armed by `-PentityPixelDump`
(`entity.pixel.dump`) - says it walks "every active layer (matching the bounds walker's
coverage)" (`EntityBoundsWalker.java:1300-1301`). Its layer loop (`:1340-1350`) runs each active
layer's models straight through the body's pose stack, and the bounds walk in `walkLayerExtents`
does two things it does not:

- **The wings' back shift.** Vanilla's `WingsLayer.submit` translates by `(0, 0, 0.125)` before it
  submits the mesh, and `walkLayerExtents` takes that shift with it (`:297-303`, `:324`,
  `WINGS_BACK_SHIFT` at `:807`). The dump does not, so on an elytra reference it emits the wings an
  eighth of a block forward of where they are measured and drawn - and the asset side's `[PX]` trace
  carries the shift in the mesh itself (`ElytraMesh.BACK_OFFSET`), so the two traces disagree about
  the wings before either renderer is at fault.
- **The unworn-layer skip.** `walkLayerExtents` skips a layer that declares a `LayerType` and
  resolves no equipment texture (`:283-296`) - the saddle layer on a horse wearing body armour. The
  dump emits that layer's mesh, which vanilla does not draw.

Neither moves a reference: the dump returns at `:1314` when the property is unset, and only a run
that sets it reads either path. What it costs is the trace's one job - telling chain drift from
rasterizer coverage - on exactly the subjects whose layers are placed or skipped conditionally.
It settles when the dump and the bounds walk share one layer loop, so the pose a layer is walked in
and whether it is walked at all are decided once.

## The panel probe crops a canvas-mismatched pair where the sweep pads it

`panel stats` promises to be the shipped metric computed a second time, and on a pair whose
canvases differ it is not. The sweeps that reconcile two canvas sizes - block, entity, entity
animation and walk, item - pad both renders onto the union canvas, each CENTRED at
`((cw - w) / 2, (ch - h) / 2)` (`ParityMetrics.padToCanvas`,
`src/visual/java/lib/minecraft/renderer/store/diff/ParityMetrics.java:124-136`), and take the mean
over that whole union (`EntityParitySweep.java:226-240`). They write the UNPADDED renders as
`vanilla.png` and `java.png` (`:231-232`). `panel._crop` (`parity/scripts/parity/panel.py:45-49`)
reads those two files and crops both to the top-left `min(w) x min(h)`, so it compares a different
pixel set over a different count; its docstring's "exactly as `ParityMetrics.compareImages`" is true
of `compareImages` alone and not of what every padding sweep hands it.

Over the entity tree the cache holds at HEAD (405 subjects), the probe agrees with the table's
`mean_argb_delta` on the 404 equal-canvas subjects and disagrees on the one mismatched one,
`minecraft__zombie~age=baby~elytra=true` (java 190x267, vanilla 200x259): 128.1885 against the
table's 109.4416. Padding both sides centred onto 200x267 in numpy reproduces 109.4416, and all 405.
The bbox the probe reports is taken on the same crop, so on a mismatched pair it clips the larger
side - the case its docstring names it the back-solve for.

`AgreesWithTheJavaOnRealRenders` (`parity/scripts/parity/tests/test_panel.py:108-137`) checks the
first ten subjects, in sorted name order, that the cached table has a row for. On a full run those
are ten equal-canvas subjects from `allay` to `axolotl_cyan`, so it passes and never exercises a
mismatch; after a scoped run, whose table holds only the scoped rows, the ten can include a
mismatched subject and `paritySelfTest` - which `check` and every parity task run - fails on a
correct Java value.

It settles when `panel.stats` pads the way the sweeps do, and the agreement test deliberately
includes the table's mismatched rows rather than whichever ten sort first.

## No test walks the item-texture override with a CIT rule in the stack

`IndexedRendererContext.resolveItemTextureOverride`
(`src/main/java/lib/minecraft/renderer/content/index/IndexedRendererContext.java:236-246`) walks the
stack's CIT rules, skipping every rule whose type is not `CitType.ITEM` (`:241`), and returns the
first match's output with the glint grafted on. `ItemRenderer` is the one renderer that calls it
(`ItemRenderer.java:245`, `:406`), and no test calls it directly. Every item render in the suite
runs against a vanilla stack, whose rule set is empty - the client jar carries no `optifine/` tree -
so the loop body never runs. The one test that builds a context with CIT rules,
`IndexedRendererContextArmorOverrideTest`, calls only `resolveArmorTextureOverride`;
`CitRuleMatchTest` calls `ItemContext.matches` and `RuleLookupGlintTest` the glint lookup, neither
through the item walk. So an edit to the walk - its type test, its first-match order, its glint
graft - fails no case in `./gradlew test` or `slowTest`.

B64 in `blindness.json` says the opposite in one place. Its `source` records that the 18 fast-suite
cases that went red under an inverted `ItemContext.matches` walk a CIT rule "through the item
override, the armour override, the glint lookup or the match itself"; no committed case walks the
first of those. The same `source` later says the item walk was reached only by a probe case outside
the committed suite, and its `reason` lists `IndexedRendererContextArmorOverrideTest` and
`RuleLookupGlintTest` as the grammar's own gate beside the four parser and match tests, neither of
which reaches the item walk.

It settles when a fast-suite case resolves an item override against a stack carrying an item rule
and a rule of another type, and B64's `source` and `reason` name the cases that walk it.

## Toolkit comments state counts and examples the tree does not bear

Several comments in `parity/scripts/parity` argue from a number, a path or a column order that the
tree does not bear. The arguments hold; the evidence they cite does not.

- `reach.py:59-62`, above `ROOTS`: "`test` runs 1325 tests to write four self-captured rows".
  `test` writes eight - `digest.shipped-tables` and the seven pins
  (`src/visual/java/lib/minecraft/renderer/store/ParityArtifacts.java:154`, `:159-165`) - and the
  fast suite is past 2000 tests.
- `blindness.py:9-12`: a derived rule's one glob answering per class is illustrated with "under
  `engine/**` a pose kit reaches the entity sweeps where a model engine reaches every render". No
  rule fires on `engine/**`: B19 carries nine engine sub-package globs, none of them `engine/pose`,
  and names `bake/pose`'s `ClipPlayer` and `PosePlayer` verbatim; no type in either package is a
  kit. A glob that does answer two ways is B19's `src/main/java/lib/minecraft/renderer/*`, where
  `FluidRenderer` plans 2 artifacts and `Renderer` 17. `:15-16`'s "the two dump manifests still
  fall off an engine change" does not hold for `engine/pose`, where eight of the eleven types plan
  both dumps.
- `blindness.py:60-70`, the worked examples of the removal passes. B37 does not fire on
  `BlindnessMapTest.java` (its triggers are the `store/**` and `dump/**` trees; the file is under
  `guard/`), so that path's empty `sees` comes from B39 alone. No `GeometryKit.java` exists; a
  file where B10 claims `sweep.block` blind and B19 selects it is `engine/mesh/BoxKit.java`. On
  `PlayerRenderer.java` B9 does not merely claim `sweep.player` - it is in B9's `sees`, and the plan
  selects it; `bake/texture/TrimKit.java`, where B23 claims `sweep.block` and no fired rule selects
  it, is the example the bullet describes.
- `sweep.py:3-6`: "`mean_argb_delta` is column 3 in `sweep.glint` because column 2 is `frames`".
  `GlintParitySweep` writes `subject, mean_argb_delta, status, frames, ...`
  (`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:193-194`); the shape with
  `frames` second exists only in the fixture `tests/data/sweep-glint.tsv`, which is what keeps the
  by-name reader honest.
- "the six sweeps" at `sweep.py:172`, `norm.py:15` and `ids.py:4`: `sweep.SWEEPS` holds nine, and
  nine `*-parity-vanilla` trees write a `parity-report.tsv`.
- `promote.py:414-418`: the count-member rule "reproduces all fourteen promoted rows exactly", and
  `manifest.tooling-tables` "still reads 10 where the gate joins 18". The index holds 29 artifact
  rows, and the tooling-tables payload carries 11 `files` and 8 `logs`, so its row reads 11.

It settles when each comment either states what the tree holds or drops the number, preferring the
second wherever the number is one the next change moves.
