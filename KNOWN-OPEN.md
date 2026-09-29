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

## The item-definition grass and map-colour tints parse to white, so six items draw a layer untinted

`LayerTintDeserializer.deserialize` reads `dye`, `potion`, `firework` and `constant`, and every
other type falls to a white `LayerTint.Constant`
(`src/main/java/lib/minecraft/renderer/content/json/LayerTintDeserializer.java:32-38`). Two types
26.1 ships take that arm. `minecraft:grass`, on six definitions, is `GrassColorSource`, whose
`calculate` answers `GrassColor.get(temperature, downfall)`, and all six declare `0.5` and `1.0`.
`minecraft:map_color`, on `filled_map`'s second layer, is `MapColor`, which answers the stack's
`MAP_COLOR` component and else `ARGB.opaque(default)`. The declared default is `4603950`, and
`Items.FILLED_MAP` registers `MapItemColor.DEFAULT`, the same `4603950`, as its default component,
so a default stack answers `0xFF46402E` by either route (javap, 26.1). Neither needs a guess: the
colour or its fallback is in the definition.

`grass_block` is the one the item index does not carry, and its held block takes the block's `GRASS`
tint, which `Biome.INVENTORY_DEFAULT` samples at the same point. The other five - `bush`, `fern`,
`large_fern`, `short_grass` and `tall_grass` - are item-index entries whose `layer0` is a grey block
sprite, so `ItemTint.resolveLayerTint`
(`src/main/java/lib/minecraft/renderer/bake/texture/ItemTint.java:66-78`) answers that white and the
sprite draws as it is: grey at `GUI_2D`, at `HELD_3D` and at `GUI_ICON`, which hands an item-index
id to `Gui2D` (`src/main/java/lib/minecraft/renderer/ItemRenderer.java:660-661`). None is swept: the
harness item sweep renders only non-block items, and its 479 references hold none of the five.
`filled_map` is swept, and its markings layer draws white where vanilla draws `0xFF46402E`: the
stored `sweep.item` row reads 43.9141 over 21504 differing pixels, the largest of its 479 rows.

`MissingTextureTintRosterTest.shortGrassRendersUntinted`
(`src/test/java/lib/minecraft/renderer/MissingTextureTintRosterTest.java:108-115`) pins the untinted
product and calls it deliberate, and the javadocs of `Item.LayerTint`
(`src/main/java/lib/minecraft/renderer/asset/Item.java:50-52`) and the deserializer
(`LayerTintDeserializer.java:15-18`) give white as the alternative to guessing a colour.

`LayerTint` is sealed over four variants (`Item.java:54-55`), and `resolveLayerTint` and
`PipelineParityDump.tint`
(`src/visual/java/lib/minecraft/renderer/dump/PipelineParityDump.java:1172`) switch over it
exhaustively, so a variant for either type owes each an arm.
`ItemModelTreeProjectionCorpusTest.parseTint`
(`src/test/java/lib/minecraft/renderer/content/pack/ItemModelTreeProjectionCorpusTest.java:134-143`)
keeps its own copy of the white default, and `tintCaptureParity` holds the loader to it, so it owes
the same arms. The dumps serialize every definition's tint list (`PipelineParityDump.java:791-792`),
the `filled_map` row moves in `sweep.item`, and the short-grass case owes the tinted product.
`parity/reach.json` derives no artifact for the deserializer, whose `@Parity` declares the block,
entity, item and menu subjects instead (`LayerTintDeserializer.java:23-24`); `Item` resolves to both
dump manifests, `manifest.visual` and the glint, item and menu sweeps.

It settles when both types resolve to vanilla's colour - the grass colormap at the declared
temperature and downfall, the map colour from its component or its default - and a fern icon and a
filled map's markings draw what vanilla draws.

## A baby camel under sit_pose throws on an identity root scale

`PosePlayer.seatUnderContainer`
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:665-701`) refuses any scale
channel on a container step whatever it holds (`:669-674`): "the container writes '%s', which
reaches no bone below it". `CamelBabyAnimation#CAMEL_BABY_SIT_POSE` keys its `root`'s scale at
`[0, 0, 0]` (`src/main/resources/lib/minecraft/renderer/entity_poses.json:24114-24127`), and the
baby camel's mesh declares no bone of that name, so `ClipPlayer.target`
(`src/main/java/lib/minecraft/renderer/bake/pose/ClipPlayer.java:229-237`) answers the channel with
the container, `accumulate` merges all three axes, zeros included (`:203-210`), and the seat throws
on `x_scale`. The camel row's `sit_pose` names no age
(`src/main/resources/lib/minecraft/renderer/entity_models.json:899-913`), so the baby's in-force
catalog lists it, and a render of `minecraft:camel` at `Age.BABY` under `sit_pose` throws
`RendererException`.

In vanilla the keyframe changes nothing (javap, 26.1). `KeyframeAnimations.scaleVec(1, 1, 1)`
returns each axis less one, the `SCALE` target is `ModelPart.offsetScale`, a `+=` on the part's
scale, and `createPartLookup` answers `root` with the model's own root, so the root's scale stays at
one - and `translateAndRotate` scales the stack only where an axis stands away from one. The chain
here composes a scale the same way: a step is a cubeless `EntityMesh.Bone`, and
`BoneKit.applyBonePose` (`src/main/java/lib/minecraft/renderer/bake/mesh/BoneKit.java:212-219`)
puts a bone's pose scale onto every descendant and skips it at one. `PosePlayer.posedScale`
(`PosePlayer.java:458-474`) already carries a clip's scale on a bone that way, as one plus the
displacement, so a scale on a step would reach every bone below it.

`BabyAxolotlAnimation#BABY_AXOLOTL_PLAY_DEAD` keys `root`'s scale at zero too, and the axolotl's
baby `play_dead` row plays it (`entity_models.json:403-416`), but `BabyAxolotlModel`'s mesh declares
a bone named `root`, which takes the channel from the container as vanilla's lookup does, and
`posedScale` hands that bone back untouched at a zero displacement (`PosePlayer.java:462-465`). The
two are the only shipped clips keying `root`'s scale and no shipped pose writes a container scale,
so the baby camel is the one subject that throws.

`PosePlayerStyleTest.everyFormPosesUnderEveryListedStyle` holds its walk's failures equal to
`KNOWN_REFUSALS` (`src/test/java/lib/minecraft/renderer/bake/pose/PosePlayerStyleTest.java:55-63`,
`:213`), the camel's two lines at ticks 0 and 7, so a fix turns it red until the list is emptied;
`PoseStatesBlindnessTest.outcome`
(`src/test/java/lib/minecraft/renderer/bake/pose/PoseStatesBlindnessTest.java:84-98`) catches the
same refusal and compares its message across both sides. No stored reference poses a camel under
`sit_pose` - the reference tree holds the baby as `entities/minecraft__camel~age=baby.png` and its
`idle/` and `walk/` strips - and no visual driver names the style. `parity/reach.json` records five
artifacts for `PosePlayer`: `manifest.player-raw`, `manifest.visual`, `sweep.armor`, `sweep.entity`
and `sweep.entity-animation`.

It settles when the seat answers a clip's container scale as vanilla answers the root's - one plus
the displacement, carried on the step and composed onto every bone below it - so a baby camel poses
under `sit_pose`, `KNOWN_REFUSALS` is empty, and neither the seat's `@throws`
(`PosePlayer.java:663`) nor `PoseStatesBlindnessTest`'s note names a container-scale refusal.

## The small armour stand's mesh has no arms and neither toggle reaches it, where vanilla's has both

The mesh the armour stand's `small` size option names,
`ArmorStandModel#createBodyLayer@baby=HumanoidModel.BABY_TRANSFORMER`
(`src/main/resources/lib/minecraft/renderer/entity_geometry.json:1038-1259`), carries no `left_arm`
and no `right_arm`, where the full-size key (`:789-1037`) carries both hidden under `toggle: arms`;
and its `base_plate` names no toggle, where the full-size one names `base_plate`.
`EntityIndexBuilder.sizeForm`
(`src/main/java/lib/minecraft/renderer/content/index/EntityIndexBuilder.java:1114-1129`) takes that
mesh as it stands. Nor would a toggled bone there move: `AppearanceOptions.resolve` flips the
selected toggles on `definition.model()`
(`src/main/java/lib/minecraft/renderer/request/AppearanceOptions.java:518-527`) and the size swap
then replaces the model with the form's own (`:549-553`), so no toggle reaches a size form's mesh -
the declared `Size.LARGE` form included, which is the row as built before any flip
(`EntityIndexBuilder.java:1091`). So `minecraft:armor_stand` at `Size.SMALL` draws no arms under
`arms` and keeps its plate under `base_plate`, and at an explicitly named `Size.LARGE` neither
toggle moves a bone. The stand is the only one of the five size-axis rows whose mesh names a toggle.

The arms go at generation. `EntityMeshMarking.sitesOf` reads a size option's rest state off the
option alone
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/EntityMeshMarking.java:264-270`).
`PoseFlow.mergeRestingUndrawn` writes that option an `undrawn` list
(`tooling/src/main/java/lib/minecraft/renderer/tooling/animation/PoseFlow.java:831-840`) and nothing
writes it `toggles` - `EntityBoneResolver` puts those on the family's and each equipment row's
`bones` node - so `mark` finds both arms resting hidden with no selection to reach them and removes
them (`EntityMeshMarking.java:156-158`), and leaves the plate unmarked.

Vanilla keeps both. `LayerDefinitions` bakes `ARMOR_STAND_SMALL` as
`ArmorStandModel.createBodyLayer()` under `HumanoidModel.BABY_TRANSFORMER`, whose
`BabyModelTransform.apply` re-adds every root child of the source mesh; `createBodyLayer` builds
both arms at the root; `ArmorStandRenderer` submits that model while `isSmall` holds; and
`ArmorStandModel.setupAnim` sets each arm's `visible` from `showArms` and the plate's from
`showBasePlate` (javap, 26.1).

Nothing selects a toggle beside a size. The entity sweep's stand rows beside the bare one are
`~size=small`, `~elytra=true~size=small`, `~toggle=arms`, `~toggle=base_plate` and `~armor=iron`,
none pairing a toggle with a size, and `BoneToggleRestTest` asserts the stand's toggles with no
size named.

A fix is two edits: the size site owes the toggles its own model class declares, expanded against
its own mesh, and the resolve owes the flip on the mesh the size swap selects. The regenerated
`entity_geometry.json` is hashed by `manifest.tooling-tables`
(`src/test/resources/lib/minecraft/renderer/parity/manifests/tooling-tables.json:30`) and owes the
tooling-flow-gate; the two stored small-stand rows select no toggle, and the gate over them says
whether the hidden arms move a byte.
`src/test/java/lib/minecraft/renderer/content/index/EntityModelLoaderTest.java:600-603` gives the
small mesh's missing arms as the reason its form keeps the row's pose instance, and owes a rewrite.

It settles when a small stand draws both arms under `arms` and no plate under `base_plate`, a stand
named at `Size.LARGE` keeps both flips, and a test selects each toggle at each size.

## A model's display is inherited whole, where vanilla inherits it per slot

`ResolvedModels.mergeParentChain`
(`src/main/java/lib/minecraft/renderer/content/pack/ResolvedModels.java:173-208`) deep-merges
`textures` alone and lays every other key the child declares over the parent's whole (`:194-205`),
so a model declaring any `display` slot drops every slot it leaves to its ancestors. Vanilla walks
the chain per slot: `ResolvedModel.findTopTransform` climbs parents until one answers the asked slot
with something other than `ItemTransform.NO_TRANSFORM`, which `ItemTransforms$Deserializer` gives an
absent key (javap, 26.1). `Held3D.heldDisplay`
(`src/main/java/lib/minecraft/renderer/ItemRenderer.java:565-570`) reads `thirdperson_righthand`
off the merged map and draws the identity where it is absent.

Of the 704 26.1 item definitions whose root is a plain `minecraft:model` naming a block model, 140
lose `block/block`'s held pose - `[75, 45, 0]`, `[0, 2.5, 0]`, `0.375` - and draw at the identity:
58 stairs, 26 walls, 16 glazed terracotta, 13 fences, 12 fence gates, the 8 blocks under
`orientable_with_bottom` (furnace, smoker, blast furnace, dispenser, dropper, loom, carved pumpkin,
jack o'lantern), 3 anvils, and the calibrated sculk sensor, dried ghast, lectern and pumpkin. Each
reaches `heldBlockOf` (`ItemRenderer.java:474-490`), none being carried by the item index or a
block entity; the walls, the fences and the four waxed cut copper stairs ship no `block/<id>` model
and are indexed from their blockstates. The item path does the same to 66 item models that declare a
slot of their own and no `thirdperson_righthand`: the 21 music discs and `template_music_disc`, the
34 open bundle halves and their two templates, the amethyst cluster and its three buds, and `bone`,
`cod`, `feather` and `lead`.

The `gui` slot is lost the same way on 110 block models, 86 of them named by a block-backed item
definition, and there it draws what vanilla draws. `BlockIndexBuilder.iconGuiFor`
(`src/main/java/lib/minecraft/renderer/content/index/BlockIndexBuilder.java:396-419`) finds no gui
on them, and `BlockRenderer.resolveIconView`
(`src/main/java/lib/minecraft/renderer/BlockRenderer.java:253-267`) falls back to the output's
projection, `Projection.VANILLA_ISO` by default, which its javadoc (`:239-241`) says `block/block`'s
gui collapses to bit for bit.

Nothing gates a lost slot. No stored artifact renders `HELD_3D`. The tests that pin a held pose
read models with no ancestor slot to lose: `HeldBlockItemTest` pins stone, which declares no
`display`, and the end rod, which declares its own `thirdperson_righthand`, and
`HeldDisplayContextTest` pins the spear, whose `item/spear_in_hand` does the same.
`HeldBlockItemTest.stairsDrawHeld` asserts only that the stairs draw, and `ResolvedModelsTest`
asserts nothing about `display`.

`parity/reach.json` resolves `ResolvedModels` to `digest.colormap-lut` and the two dump manifests,
which serialize every resolved model's `display`
(`src/visual/java/lib/minecraft/renderer/dump/PipelineParityDump.java:207-208`, `:1798`); a plain
per-slot union changes that map on 398 of the 3676 26.1 models, 256 block and 142 item. The 110
gui slots move those icons off the projection fallback onto `Camera.fromTransform`, which a
block-sweep capture holds or refutes. Vanilla's deserializer fills an absent left-hand slot from the
same file's right-hand one before any walk, so a per-slot merge owes that per file too.

It settles when `mergeParentChain` merges `display` per slot, as it merges `textures`, and a held
oak stairs draws at `block/block`'s `thirdperson_righthand`.

## The WINGS feature draws an elytra on entities no vanilla renderer gives wings

The `WINGS` feature (`src/main/java/lib/minecraft/renderer/EntityRenderer.java:606-614`) returns
early only when the appearance selects no elytra (`:609`), and the canvas folds the wings in on the
same test (`:231-233`, `:309-312`, `:362-364`), so `AppearanceOptions.elytra` draws the wings on
every entity row - a villager, a cow, a giant. The field's javadoc
(`src/main/java/lib/minecraft/renderer/request/AppearanceOptions.java:221-228`) says of the knob
"Only meaningful for the humanoid roster that can equip a chest item", so a caller trusting it
expects a no-op where the renderer draws wings.

Vanilla constructs a `WingsLayer` in three renderers - `HumanoidMobRenderer`, `ArmorStandRenderer`
and `AvatarRenderer` - and builds it unconditionally in each constructor (javap, 26.1). Walking each
row's `renderer` up its superclass chain, 13 of the 90 rows in `entity_models.json` reach one: the
armour stand, skeleton, stray, wither_skeleton, bogged, parched, zombie, husk, drowned,
zombie_villager, piglin, piglin_brute and zombified_piglin. The other 77 draw wings no client draws.
Nothing gated reaches them: the store's three elytra rows are the zombie at both ages and the small
armour stand, and every elytra wearer `EntityOverlayFitTest` fits is on the 13.

The table carries no wings fact to decline them by. Its `armor` member is not one: it marks 14 rows,
those 13 and the giant, whose `GiantMobRenderer` builds a `HumanoidArmorLayer` and no `WingsLayer`.
The tooling's layer walk, `EntityLayersResolver.resolve`
(`tooling/src/main/java/lib/minecraft/renderer/tooling/entity/EntityLayersResolver.java:98-132`),
visits every `addLayer` site of a row's renderer and emits the `armor` member off the
`HumanoidArmorLayer` one; nothing is emitted for a `WingsLayer` site. A member added there moves
`entity_models.json`, which `manifest.tooling-tables` hashes, and owes the tooling-flow-gate. The
`minecraft:player` row `PlayerRig` synthesizes
(`src/main/java/lib/minecraft/renderer/author/install/PlayerRig.java:99`) carries empty layers and
owes the fact by hand, since `AvatarRenderer` builds wings.

It settles when the wings draw, and fold into the canvas, only on a row whose vanilla renderer
builds a `WingsLayer`, read off a fact the model table carries, and the field javadoc names that
roster.

## PoseCompiler's container refusals give a reason the render seat does not bear

`PoseCompiler.Lowering.lowerStep`
(`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:1169-1188`) divides a
container position by the compiling mesh's flattened factor (`:1176`) and then refuses it on any
mesh flattened at a factor but one (`:1179-1181`); `lowerHover` refuses a lift or a bob the same way
(`:1197-1199`). Both give one reason - "the step seats parentless, which that factor alone does not
answer" - that the seat lacks an anchor the factor cannot supply. A rotation passes and a zero
unwaved delta returns first, so the divided value is only ever used at a factor of one. The refusal
is an `IllegalArgumentException` the weave does not catch
(`src/main/java/lib/minecraft/renderer/author/install/StyleRegistrar.java:462-475`), so the install
refuses, strict or not.

The seat that reason describes is not the one in the tree. `PosePlayer.seatUnderContainer`
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:665-701`) builds every step at a
factor of one (`:695-700`), so `placed` hands back the value written (`:588`) and a step lands at
that number whatever the mesh is flattened at, which
`PosePlayerTest.aFlattenedContainerSeatsAtWhatThePoseWrote` pins on a mesh flattened at 2. That is
vanilla's: `MeshTransformer.scaling` rewrites the root's `PartPose` alone and
`PartDefinition.transformed` copies the children unchanged (javap, 26.1), so the factor and the feet
anchor ride the root and a step above it crosses neither. A flattened mesh's pivots are already in
surface pixels - a bone offset lowers to the authored pixels over the factor
(`PoseCompiler.java:1090`) and `placed` multiplies it back - so a container position lowered
undivided lands the authored pixels on every form, as an offset does.

Nineteen shipped rows draw their adult body flattened at a factor other than one - among them the
horse at 1.1, the cat at 0.8, the ghast at 4.5, the giant at 6, the elder guardian at 2.35, and the
evoker, illusioner, pillager, vindicator, villager, wandering trader and witch at 0.9375 - and the
happy ghast's baby sits at 0.95. A style writing a container position or a hover refuses on every
one. The salmon's two sizes, flattened at 0.5 and 1.5, are guarded rather than compiled
(`src/main/java/lib/minecraft/renderer/author/compile/FormWalker.java:245-254`), so the same step
installs on the salmon and its small and large forms draw it at the number written.
`PoseCompilerRefusalTest.containerPositionOnFlattenedMeshRefuses`
(`src/test/java/lib/minecraft/renderer/author/install/PoseCompilerRefusalTest.java:179-191`) pins
both refusals on a fixture flattened at 2, and `StyleRegistrarFormTest`'s class javadoc keeps its
container probe to meshes flattened at one to take it
(`src/test/java/lib/minecraft/renderer/author/install/StyleRegistrarFormTest.java:42-46`).
`parity/reach.json` records no artifact for `PoseCompiler`, so the fast suite is the gate.

It settles when `lowerStep` lowers a container position at the pixels written and `lowerHover` its
lift and bob the same, both refusals go, and the refusal pin becomes one that a step and a hover on
a flattened mesh pose the authored pixels.

## A seat carry adds a leader's offset in surface pixels to a rest in model units

`PoseCompiler.Lowering.held`
(`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:984-1005`) places a leader
under the style's held stance at `atRest + folded.additive` on a position channel (`:997`). `atRest`
is the leader's resting pivot as `Seats` reads it, in the model's own units with the flattened
factor and the feet anchor taken off
(`src/main/java/lib/minecraft/renderer/author/mesh/Seats.java:325-342`); `folded.additive` is the
offset as authored, in surface pixels, which the leader's own splice divides by the factor
(`PoseCompiler.java:1090`). Each follower's carry is solved against that held placement
(`PoseCompiler.java:960-964`), so on a mesh flattened at F the leader is taken
`additive * (1 - 1/F)` model units from where its own splice puts it, and every follower seated on
it, down a chain of seats, lands `additive * (F - 1)` pixels of mesh off the leader's frame. At a
factor of one the two agree.

Two shipped rows seat a follower on a flattened mesh. `SeatsRosterTest` prints five subjects
carrying seats; the ender dragon, the ocelot and the wolf are flattened at one, and the other two
are the cat - `tail1` on `body` and `tail2` on `tail1`, over
`AdultFelineModel#createBodyMesh@scaled=0.8` - and the polar bear - `head` on `body`, over
`PolarBearModel#createBodyLayer` at 1.2. An offset on the cat's body leaves both tail segments a
fifth of it short, and one on the polar bear's body carries the head a fifth past.

Four tests reach it on the cat, and none pins where a tail segment lands.
`PoseCookbookCreatureTest.bodySettleLandsOnTheFlattenedFeline`
(`src/test/java/lib/minecraft/renderer/author/install/PoseCookbookCreatureTest.java:263-280`) and
`StyleRegistrarFormTest.aBabysOffsetLandsTheAuthoredPixels`
(`src/test/java/lib/minecraft/renderer/author/install/StyleRegistrarFormTest.java:183-214`) offset
the body by `(0, 2, 0)` and check the body's field and pivot alone. `oneValueServesTwoRosters`
(`PoseCookbookCreatureTest.java:201-222`) installs the beg, body offset `(0, 4, -2)`, and checks
rotations alone, its tail segments sitting `(0, -0.8, 0.4)` px from where the body carries them.
`PoseAuditorTest.begAuditsOnTheCookbooksCatReuse`
(`src/test/java/lib/minecraft/renderer/author/audit/PoseAuditorTest.java:84-97`) audits that beg
and asks for no finding on `body` and `tail1` or `tail1` and `tail2`, against an envelope margin
floored at 1.5 px (`src/main/java/lib/minecraft/renderer/author/audit/PoseAuditor.java:74`), wider
than the 0.89 px that displacement measures.
`PoseCompilerCouplingTest.carriesAFlattenedParentlessFollowerAcrossTheFactorOnce` turns its leader
and offsets nothing, and `SeatInstallParityTest` asks only that the shipped styles keep their bits,
which read every carry field at zero. No shipped style, showcase or parity producer offsets a seat
leader on a flattened mesh: `PoseShowcaseDriver`'s `beg` offsets the wolf's body, a leader on a
mesh flattened at one.

A fix crosses the position arm of `held` by `this.flattened`, the way the lowering does, and pins a
follower's pivot on the cat under a body offset. `PoseCompiler` reads `"artifacts": []` in
`parity/reach.json`, so the edit plans nothing and the fast suite is the gate.

It settles when a follower seated on an offset leader lands where the leader's frame carries it on
a flattened mesh as it does at a factor of one: the cat's tail segments ride a body offset by the
body's own authored pixels.

## The fast suite clones font-generator over the network on a tree with no font cache

`MinecraftFontsExtension.beforeAll`
(`src/test/java/lib/minecraft/renderer/support/MinecraftFontsExtension.java:71-89`) skips the
generator only when `build/resources/test/fonts` or `cache/fonts` holds `Minecraft-Regular.otf`,
both resolved against the test JVM's working directory, the project directory (`:47`, `:50`), and
otherwise calls `ToolingFonts.main` (`:84`). In the `text` version `build.gradle.kts:189` pins, that
makes a shallow `git clone` of `https://github.com/minecraft-library/font-generator.git` into
`cache/font-generator`, builds a venv off the host's Python, pip-installs the clone into it and runs
the generator (javap). `cache/` is gitignored, so a fresh clone or worktree does all of that on its
first `./gradlew test`, and a host offline or without git or Python fails the class in `beforeAll`.

Three classes under `src/test/java/lib/minecraft/renderer/` install it at class level -
`TextRendererTest.java:65`, `TextRendererGradientTest.java:24` and
`showcase/ReadmeShowcaseTest.java:102` - and `screen/GradientKitTest.java` names it on three methods
(`:162`, `:187`, `:198`), where JUnit Jupiter 5.11.4 never calls it: `ClassBasedTestDescriptor`
runs a `BeforeAllCallback` from the class's registry, and a method's registry serves only the
per-test callbacks (javap). None of the four carries `@Tag("slow")`. The GradientKitTest methods
read `MinecraftFont.Vanilla` regardless, and the pinned library resolves each constant itself - the
classpath, then a per-user cache at `%LOCALAPPDATA%/minecraft-library/fonts/26.1`
(`~/.cache/minecraft-library` off Windows), then `ToolingFonts.generate` into that per-user root
(javap). `screen/MenuFieldTextTest.java`, untagged and installing nothing, takes that route too,
through `TextKit.measureLineMcPixels`. So the extension's premise, that the pinned build predates a
bootstrap on a classpath miss and exposes `main` alone (`MinecraftFontsExtension.java:26-31`), does
not hold of that jar, where `generate` and `DEFAULT_VERSION` (`:40-42` calls it package-private) are
public. What the extension adds is a per-tree cache, so a fresh worktree clones even where the
per-user cache is warm.

Nothing gates it. The root `CLAUDE.md` says under Gates that what the tag separates is the network
and that a fast run cannot download (`CLAUDE.md:92-95`), arguing from `ClientAssetsExtension`
alone. `SlowTagRuleTest` (`src/test/java/lib/minecraft/renderer/guard/SlowTagRuleTest.java:115-117`)
fires on two markers, the client acquisition's two download members and the `ClientAssetsExtension`
accessors reached ungated, so neither the font extension nor `ToolingFonts` is one.

Every class named here and the extension reach no artifact (`[]` in `parity/reach.json`). Tagging
`ReadmeShowcaseTest` slow takes the showcase images' gate out of the fast suite, while
`src/test/resources/lib/minecraft/renderer/parity/blindness.json:45-46`, the `docs/images/**` entry
of `no_reach`, says `./gradlew test` catches a bad image and that an orphaned one and a missing one
"each fail the fast suite"; that entry and the skill's rendered blindness view move with it. B15's
`source` cites `CLAUDE.md 'Gates'`, a heading `BlindnessMapTest` holds live, so the correction stays
under it.

It settles when no untagged class can reach the font generator - each class that reads
`MinecraftFont.Vanilla` either carries `@Tag("slow")` or abandons on a missing font the way
`ClientAssetsExtension` abandons on a missing client - `SlowTagRuleTest` carries a marker for it,
and the Gates sentence is true of the whole fast suite.

## paritySelfTest reads compiled classes without depending on any compile task

`paritySelfTest` (`gradle/parity.gradle.kts:1188-1193`) runs the toolkit's whole unittest suite and
declares no dependency. One class of that suite, `OverTheRealTree`
(`parity/scripts/parity/tests/test_reach.py:403-539`, 16 cases), derives the reach graph from the
class files under the four `CLASS_ROOTS` (`parity/scripts/parity/reach.py:51-52`), and it skips only
when `build/classes/java/main` is absent (`test_reach.py:403-404`). That is the one root of the four
holding no producer root: the 40 root entries `parity/reach.json` records, 37 distinct types, sit 23
in the visual set, 9 in the test set and 8 in the generators. `reach._edges` passes over a missing
class root and refuses only a tree with no class file at all (`reach.py:402-405`, `:428-429`), so on
a partly compiled tree the 16 cases run over a graph missing every edge out of the absent roots.

A fresh clone or worktree that has run `./gradlew :test` is that tree: main, test and visual are
compiled, nothing in that task's graph reaches `:tooling`, and `tooling/build/classes` is never
written. `test_no_library_type_reaches_nothing_without_saying_so` (`test_reach.py:505-512`) then
fails naming `TableEnvelope` and `Diagnostics`, the two `src/main/java` types whose only artifact in
`parity/reach.json` is `manifest.tooling-tables`; each carries a claim declaration, which
`reach.unexplained` does not read as a reach. A tree compiled short of the test or visual set fails
more of the 16. Neither failure is a toolkit defect.

Every parity entry point depends on the task - `parityExpect` (`parity.gradle.kts:1292`),
`parityCapture` (`:1378`), `parityCompare` (`:1444`), `parityPromote` (`:1490`) and `parityPlan`
(`:1538`) - and none orders it after a compile: four depend on nothing else, and `parityCapture`'s
other edges, the erase, the producers and their capture steps, sit beside the self-test with no
order between them. So on that tree the gate stops at its prerequisite, although `parityPlan` reads
the committed graph and needs no class file. `check` (`:1245-1247`) schedules the task beside
`parityReachCheck`, whose `dependsOn` on `compileJava`, `compileTestJava` and `:tooling:compileJava`
(`:1239`) writes the classes, and no edge orders the self-test after that check. Compiling
`:tooling` clears the fresh-worktree case.

An edit to `parity.gradle.kts` or `test_reach.py` plans no artifact: `gradle/**` is B31 and
`parity/scripts/parity/**` is B30, both `sees: []`, B31 naming the tasks' run and argv as the gate
and B30 naming `paritySelfTest`.

It settles when the real-tree cases run over a tree compiled in all four roots or not at all -
`paritySelfTest` carrying the compiles `parityReachCheck` carries, or the class's guard asking for
every entry of `CLASS_ROOTS` - so a fresh worktree reaches `parityPlan` after `:test` alone.

## A salmon size form reads the row's shared offset field over its own factor

The salmon's small and large forms draw `SalmonModel#createBodyLayer@scaled=0.5` and `@scaled=1.5`,
meshes flattened at 0.5 and 1.5 where the row's is flattened at one, under the row's own
`SalmonModel` pose instance. `FormWalker.woven` weaves a size form as a form of its own only where
it carries a pose other than its row's
(`src/main/java/lib/minecraft/renderer/author/compile/FormWalker.java:245-254`); one sharing the
row's pose is checked by `guardSize` (`:402-413`), which compiles nothing, and then plays the row's
woven pose and reads the row's fields. A position delta's field holds the authored pixels over the
compiling mesh's factor
(`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:1090`), and a written
position lands at the drawing mesh's factor times the value, the feet anchor put back on a
top-level y (`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:582-591`). So a 2 px
offset on a salmon bone lands 1 px of mesh on the small form and 3 px on the large, where the row
lands 2.

These two are the only size forms in the shipped tables pairing the row's pose with a mesh
flattened at a factor the row's is not: of the nine size options across five rows, the pufferfish's
two carry poses of their own, the magma cube's and the slime's four name no geometry of their own,
and the armour stand's small mesh is aged down and answers no single factor. No test poses a salmon
size under a custom offset.

Which landing is right is not settled. Vanilla's `SalmonRenderer` holds three `SalmonModel` bakes,
of `SALMON_SMALL`, `SALMON` and `SALMON_LARGE`, and assigns one to `model` at submit by variant, and
`LayerDefinitions` builds both size layers from the one `SalmonModel.createBodyLayer` through
`MeshTransformer.scaling(0.5)` and `scaling(1.5)` (javap, 26.1): what the model writes into a part
lands scaled with the bake, so a size is the row's model scaled whole. The happy ghast's baby has
the same vanilla shape, `HAPPY_GHAST_BABY` baked through `scaling(0.2375)` into a second
`HappyGhastModel`, and here it is a woven form compiled against its own mesh wherever a style's age
admits a baby, at 0.95 against the adult's 4.0, so an offset lands the authored pixels on it.
`FormWalker`'s class javadoc (`FormWalker.java:57-62`) says a size form drawing its row's pose
lends that pose its mesh and is guarded rather than compiled against; nothing says which landing
that gives an offset.

It settles when one reading is chosen and the walk follows it: a size form flattened at a factor its
row's is not compiles against its own mesh under its `$size:<option>` coordinate, as the baby does;
or the scaled-whole reading goes into `RENDERER-RULES.md`'s *Decisions that stay closed*, with why a
size form lands an offset scaled where a baby lands the authored pixels. `FormWalker` and
`PoseCompiler` both read `"artifacts": []` in `parity/reach.json`, so the code change plans nothing
and the fast suite is its gate.

## SeatInstallParityTest says a parentless bone of a flattened mesh cannot be displaced

`everyCarryingRowRestsAtItsBitsUnderAProbe`
(`src/test/java/lib/minecraft/renderer/author/install/SeatInstallParityTest.java:81-108`) promises
in its display name "a probe turning and shifting each leader" (`:82`), and the comment at `:95-96`
takes the shift back for a flattened mesh: "A parentless bone of a flattened mesh cannot be
displaced, so such a row is probed by a turn alone". `placeable` (`:97`) drops the
`offset(1, 2, 3)` (`:100`) for every mesh whose factor is not one.

The premise is false. An offset on a top-level bone lowers to the authored pixels over the factor
(`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:1090`), and the write
crosses the factor and the feet anchor back
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:582-591`), landing the authored
pixels: `PoseCompilerTest.parentlessOffsetOnAFlattenedMeshLandsWholePixels` pins it on a fixture and
`PoseCookbookCreatureTest.bodySettleLandsOnTheFlattenedFeline` on the shipped cat. Of the five rows
`SeatsRosterTest` finds seats on, the cat (0.8) and the polar bear (1.2) are flattened, so the probe
never offsets a leader on either.

It settles when the comment and the `placeable` fork go and every seat-carrying row takes the same
turn and shift. The test reads `"artifacts": []` in `parity/reach.json`, so the edit plans nothing.

## PoseShowcaseDriver audits every showcase outside the try that guards each render

`src/visual/java/lib/minecraft/renderer/driver/PoseShowcaseDriver.java:104-108` calls
`PoseAuditor.validate` for every showcase in a loop of its own, ahead of the render loop and under
no `try`. The render loop wraps each showcase's install and render in a `try` (`:117-140`) that
prints the failure and goes on to the next. `validate` throws `IllegalArgumentException` wherever a
tolerant install's weave of the style onto the row would refuse
(`src/main/java/lib/minecraft/renderer/author/audit/PoseAuditor.java:111-115`), so one showcase
whose audit throws ends `main` at `:107`, before the `Rendering` line at `:110`, and nothing in the
roster renders. The strict `StyleRegistrar.add` inside the `try` (`:119`) refuses everything a
tolerant weave refuses and more, so that showcase fails to render either way; what the order of the
two loops costs is every other showcase. A run narrowed by `-Ppose=<id>` holds one showcase and
loses nothing.

The throw is reachable on a shipped row: `StyleRegistrarAuditTest.aRawReadASizeFormLacksRefusesBoth`
(`src/test/java/lib/minecraft/renderer/author/install/StyleRegistrarAuditTest.java:90-105`) has the
audit refuse a custom style reading the pufferfish's `left_blue_fin`, a bone its small size form
lacks. The roster holds twelve showcases (`PoseShowcaseDriver.java:150-210`). `PoseAuditorTest`
audits four of them at the driver's own spelling - `rear`, `flutter`, `levitate`, and the horse's
`standAnimation=1` silhouette through the same splice under another id - and no test runs the
driver. It is a visual-set `main` behind the `poseShowcase` task (`gradle/visual.gradle.kts:140`),
the one caller of `validate` outside the tests, and `parity/reach.json` maps it to no artifact.

It settles when a showcase whose audit throws costs its own render and no other: the audit runs
inside the per-showcase `try`, or inside one of its own that reports the refusal and moves on.

## A held mangrove leaves block takes the foliage colour, not its own constant

`Held3D.heldBlockOf` tints a block-backed id with the block's no-world colour
(`src/main/java/lib/minecraft/renderer/ItemRenderer.java:479`): for `minecraft:mangrove_leaves` the
`FOLIAGE` row (`src/main/resources/lib/minecraft/renderer/block_tints.json:64-67`), which
`Biome.INVENTORY_DEFAULT` answers with `0xFF48B518`. Vanilla's held item takes its item definition's
tints (`CuboidItemModelWrapper.update`, javap, 26.1), and `items/mangrove_leaves.json` carries the
constant `0xFF92C648`. The row is right as a block colour - `BlockColors` gives mangrove leaves
`BlockTintSources.foliage()`, whose `color(state)` is `0xFF48B518` - and of the eight tinted
block-backed definitions this is the one where the two differ.

The definitions' tint lists are derived at
`src/main/java/lib/minecraft/renderer/content/index/AssetContent.java:72` and handed to
`ItemIndexBuilder.load` alone (`:93-94`), so they reach item-index entries only and `heldBlockOf`
has none to ask. No stored artifact renders `HELD_3D`. The carried-block overlay makes the same call
(`src/main/java/lib/minecraft/renderer/EntityRenderer.java:903`) and matches vanilla there: a
carried block resolves through `BlockModelSet`, whose `BlockStateModelWrapper` reads
`BlockTintSource.color(state)` (javap, 26.1). Reading the lists on the held path reads
`grass_block`'s white too, until "The item-definition grass and map-colour tints parse to white, so
six items draw a layer untinted" settles.

It settles when a held block-backed id takes its item definition's tints and held mangrove leaves
draw `0xFF92C648`.

## ItemOptions' class javadoc counts two output flavours where the type offers three

`ItemOptions`' class javadoc (`src/main/java/lib/minecraft/renderer/request/ItemOptions.java:24-34`)
opens "Covers two output flavours" and lists the 2D GUI icon and the 3D held-item view, where
`ItemOptions.Type` declares `HELD_3D`, `GUI_2D` and `GUI_ICON` (`:185-200`). The `type` field says
"2D GUI icon or 3D held-item view" (`:57`), and `layerDecorator` "Only consulted for
{@link Type#GUI_2D} renders" (`:158`), though a `GUI_ICON` of an item-index id hands its options to
`Gui2D.render` unchanged (`src/main/java/lib/minecraft/renderer/ItemRenderer.java:660-661`), which
folds the decorator (`:284-286`).

`GUI_ICON` is what the menu and the atlas build for their icons
(`src/main/java/lib/minecraft/renderer/MenuRenderer.java:313`, `:357`,
`src/main/java/lib/minecraft/renderer/AtlasRenderer.java:353`), and its block branch
(`adaptToBlock`, `ItemRenderer.java:684-698`) carries none of the item overlays the GUI bullet
lists. The file sits under B24's `request/**` trigger, so a javadoc-only edit plans four artifacts:
`manifest.visual`, `sweep.glint`, `sweep.item` and `sweep.menu`.

It settles when the class javadoc lists `GUI_ICON` as the third flavour and the `type` and
`layerDecorator` docs name the types they cover.

## BlockModelLoader.load(PackStack)'s @throws omits the refusals a pack override entry raises

`BlockModelLoader.load(PackStack)`'s `@throws ContentException`
(`src/main/java/lib/minecraft/renderer/content/index/BlockModelLoader.java:78-79`) names a missing
or unparseable resource and a failed format-2 envelope. Its call into `load(BlockRendererOverrides)`
(`:82`) reaches three more refusals a pack's entry raises: a model or geometry entry that parses and
does not bind (`src/main/java/lib/minecraft/renderer/content/table/BlockModelReader.java:44-50`,
`src/main/java/lib/minecraft/renderer/content/table/BlockGeometryReader.java:41-47`), and a model
entry with no `geometry` coordinate or a dangling one
(`src/main/java/lib/minecraft/renderer/content/index/BlockEntityAssembler.java:99-104`). The
siblings' `@throws` (`BlockModelLoader.java:92`, `:108`) name a dangling coordinate and not a
missing one, and `load(BlockRendererOverrides)`'s names no override entry.

All three are `ContentException`, so a caller catching the documented type catches them.
`BlockRendererOverridesTest` pins the two entries that do not bind and the missing coordinate
through `load(BlockRendererOverrides)`, and no test pins the dangling one. The class claims
`pack-resolution` (`BlockModelLoader.java:40`), so B4 fires on it by name beside B5 and B20, and a
javadoc-only edit plans nine artifacts, `digest.colormap-lut` and `sweep.item` among them.

It settles when each overload's `@throws` names every refusal its call reaches.

## CitType's javadoc says armour and elytra rules are parsed and held

`CitType`'s class javadoc (`src/main/java/lib/minecraft/renderer/asset/rule/CitType.java:10-12`)
says `ARMOR` and `ELYTRA` "are parse-and-hold until pack-aware equipment rendering lands", and the
constants say the same (`:22`, `:24`). `IndexedRendererContext.resolveArmorTextureOverride`
(`src/main/java/lib/minecraft/renderer/content/index/IndexedRendererContext.java:257-268`) walks
both, `ELYTRA` for the wings layer and `ARMOR` for every other. Armour reaches a render: the entity
and player armour paths hand it each slot's `ArmorOptions.items` entry
(`src/main/java/lib/minecraft/renderer/EntityRenderer.java:648-649`,
`src/main/java/lib/minecraft/renderer/bake/armor/PlayerArmorKit.java:117`). The wings do not:
`ElytraKit` asks only when handed an item
(`src/main/java/lib/minecraft/renderer/bake/armor/ElytraKit.java:259-265`, pinned by
`ElytraKitCitTest`), and every renderer hands it `Optional.empty()` (`EntityRenderer.java:232`,
`:611-612`, `src/main/java/lib/minecraft/renderer/PlayerRenderer.java:235`). So the javadoc is
wrong about armour outright, and about elytra in its reason.

`CitType.java` fires B2, B25 and B64, so a javadoc-only edit plans four artifacts,
`digest.colormap-lut`, `digest.shipped-tables` and both dump manifests.

It settles when the docs say `ARMOR` rules retexture worn armour, and `ELYTRA` rules the wings only
where a caller hands `ElytraKit` an item, which no renderer does.

## A bone holds its rotation in float degrees, and a radian no float degree reaches is lost

`EntityMesh.Bone`'s rotation
(`src/main/java/lib/minecraft/renderer/asset/mesh/EntityMesh.java:201-211`) is an `EulerRotation`,
which carries degrees
(`src/main/java/lib/minecraft/renderer/engine/geometry/EulerRotation.java:8-10`) and answers
`(float) Math.toRadians(value)` (`:78-80`) to `BoneKit`
(`src/main/java/lib/minecraft/renderer/bake/mesh/BoneKit.java:248-250`). Vanilla's
`ModelPart` holds `xRot`, `yRot` and `zRot` as the float radians a `PartPose` literal or a
`setupAnim` write puts there, and converts nothing. A radian enters a degree float in two places.
At rest, `GeometryParser` writes `(float) Math.toDegrees(r)` for every `PartPose.rotation` and
`offsetAndRotation` it walks
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:2184-2188`,
`:2201-2205`), and `src/main/resources/lib/minecraft/renderer/entity_geometry.json` ships those
degrees. Posed, `PosePlayer.degrees`
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:601-608`) folds each written
rotation channel to `(float) Math.toDegrees(value)`, except one written back to the radian the bone
already reads; a pose's write, a clip's displacement and each container step all reach it through
`posedBone` (`:433`, `:444`, `:697`).

The table's 155 geometries carry 99 distinct non-zero angles. Converted back, 95 land on a float
constant in the client's `net/minecraft/client/model` and `net/minecraft/client/renderer` classes
(javap, 26.1), and the squid's `tentacle7` yaw of `-225` lands on the value its loop computes in
double and narrows. Three land one ULP off vanilla's literal: `WitherBossModel`'s tail `xRot`
`0.83252203f`, stored as `47.7` in both its geometries, recovers `0.8325221`, and
`AdultArmadilloModel`'s `right_ear_cube` and `left_ear_cube` `zRot` of `-0.0718f` and `0.0718f`,
stored as `-4.1138372` and `4.1138372`, recover `-0.07180001` and `0.07180001`. The tail's rest
value shows only where the bind pose draws, the `WitherBossModel` pose row writing its `x_rot` as
vanilla's `setupAnim` does every frame; nothing in `entity_poses.json` writes either ear cube, so
every adult armadillo style draws them at the lost value. A posed radian meets the same round trip:
`(float) Math.toRadians((float) Math.toDegrees(r))` misses 775,914 of the 8,388,608 floats in each
binade from 2^-126 to 2^121, and each of the 98,939,836 positive floats it misses up to
`(float) Math.PI` is one no float degree converts to. No stored degree recovers any of them; only a
bone holding the radian does.

Nothing measured moves a byte on it at 26.1. `RENDERER-RULES.md`'s refusal of vanilla's float
multiply, under *Decisions that stay closed*, records that forcing the three rest radians and every
written channel's exact radian together moved no byte of the still, idle and walk sweeps of
eighteen entities, and the rest radians alone none of six 3D renders; the same entry records that
one ULP does reach raw bytes, and that a version bump re-opens the measurement.

A bone that holds radians changes `EntityMesh`, which the reach graph answers with eighteen
artifacts, or `EulerRotation`, degrees by contract for every display transform and camera too and
answered with twenty-three, the two dumps among them demoted by B19 and B26, and `PosePlayer`,
answered with five. The rest half also moves the table: the generator edit owes the
`tooling-flow-gate` loop, and a regenerated `entity_geometry.json` selects thirteen artifacts
through B35.

It settles when a bone's rotation reaches `BoneKit` as the radian vanilla's part field holds - the
table carrying rest radians and `posedBone` handing a written radian through unconverted - or when
carrying degrees is recorded in *Decisions that stay closed* beside the float-multiply refusal,
whose measurement already covers both halves.

## FaceTextures' javadoc calls every supplier trivial

`FaceTextures`' class javadoc
(`src/main/java/lib/minecraft/renderer/engine/geometry/FaceTextures.java:17-19`) says suppliers
"come in three shapes and all three are trivial". `HumanoidPart.textures` and
`WornBox.Mesh.textures` hand `DOWN` with its rows reversed
(`src/main/java/lib/minecraft/renderer/vanilla/mesh/HumanoidPart.java:246-253`,
`src/main/java/lib/minecraft/renderer/bake/armor/WornBox.java:136-143`), and
`PlayerAssembly.capeTextures` turns the cape's cap strips half a turn
(`src/main/java/lib/minecraft/renderer/bake/mesh/PlayerAssembly.java:243-249`).
`RENDERER-RULES.md:241-247` states both turns and each site's javadoc its own, so the interface a
new box builder's author reads first is the one place saying none is owed. The `uniform` doc
(`FaceTextures.java:34-35`) also omits `MissingMesh.cube`, one of its four production callers.

The edit is javadoc-only and priced as code, the reach graph being class-granular:
`parity/reach.json` answers `FaceTextures` with eighteen artifacts, and B19 and B27, the rules its
path triggers, demote only the two dumps, which are not among them.

It settles when the class javadoc says what each supplier does - one buffer on every face, a crop
with `DOWN`'s rows reversed, a crop with its caps half-turned - and `uniform`'s doc names the
missing cube.

## The DOWN reversal flips its crop in place, which is safe only while every crop is a fresh buffer

`HumanoidPart.textures`
(`src/main/java/lib/minecraft/renderer/vanilla/mesh/HumanoidPart.java:246-253`) and
`WornBox.Mesh.textures` (`src/main/java/lib/minecraft/renderer/bake/armor/WornBox.java:136-143`)
reverse `DOWN`'s rows by calling `flipVertical()` on the buffer their crop returned, and the pinned
image library's `PixelBuffer.flipVertical` swaps rows in place. It is the one write a `FaceTextures`
supplier makes into what it hands out: the cape's half turn is `rotate180()`, which returns a copy
(`src/main/java/lib/minecraft/renderer/bake/mesh/PlayerAssembly.java:247`).

It is safe because both crops allocate. `HumanoidPart.cropRect` (`HumanoidPart.java:285-301`) and
`Unwrap.Atlas.crop` (`src/main/java/lib/minecraft/renderer/engine/geometry/Unwrap.java:146-168`)
build a new array per call, and both crops' `@return` promise a new buffer (`HumanoidPart.java:212`,
`Unwrap.java:143-144`). `FaceTextures.byFace` says nothing about who owns what it returns, and the
tree's texture convention runs the other way: `MissingSprite.sprite()` hands out one shared buffer
"the way a resolved pack texture is handed out, so a caller reads it and never writes to it"
(`src/main/java/lib/minecraft/renderer/engine/texture/MissingSprite.java:31-32`). A cache under the
flip - a memoised crop, or a crop handing out a shared buffer the way `sprite()` does - reverses
the shared strip for every holder, and the next `DOWN` request reverses it back, so successive
builds against one sheet alternate. A cache over the supplier is harmless: it holds the strip
already reversed. Nothing pins it: `HumanoidFrameTest` builds one box per supplier, so no test asks
one supplier for `DOWN` twice.

It settles when the reversal writes into a buffer of its own - a reversed copy, or a crop that reads
`DOWN`'s rows bottom-up - so no supplier writes into a buffer another caller can hold. The change is
meant to move no byte, and `HumanoidPart` and `WornBox` each plan the same nine artifacts.

## GeometryParser's FEET_ANCHOR javadoc names a renderer constant that does not exist

`GeometryParser.FEET_ANCHOR`'s javadoc
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:96`) says the
renderer names the same number `Shell.FEET_ANCHOR`; `Shell` declares no such member. The constant
is `EntityMesh.FEET_ANCHOR`
(`src/main/java/lib/minecraft/renderer/asset/mesh/EntityMesh.java:101`), expanded in
`EntityMesh.flattenedShift` (`:112-114`), which `Shell.meshOffset` and `ElytraKit.wingPivot` both
read.

The paragraph's premise at `GeometryParser.java:98`, "one contract written in two builds", does
not hold either. `:tooling` is a subproject of the renderer's build taking `project(":")` on
`implementation` (`tooling/build.gradle.kts:26`), the parser already imports `VanillaMth` and
`Diagnostics` from it, and `src/test/java/lib/minecraft/renderer/guard/TierOrderTest.java` lets a
generator name `asset.mesh` (`:66`, `:80`), so the parser can read the renderer's constant.
`EntityMesh.java:98-99` and the comment at
`src/test/java/lib/minecraft/renderer/bake/armor/ArmorKitTest.java:160-164` repeat the premise.

An edit to `GeometryParser.java` fires B13 and B14 and plans `manifest.tooling-tables` and
`report.diagnostics-log` alone, so the flow re-run is its measurement. `parity/reach.json` answers
18 artifacts for `EntityMesh`, and the plan prices a javadoc edit there as it would a code edit.

It settles when the three places name `EntityMesh.FEET_ANCHOR` and one build, or when the parser
reads the renderer's constant and the paragraph goes with the duplicate.

## adultElytraFitsItsCanvas cannot fail on either wing mismeasure its class names

`EntityOverlayFitTest.adultElytraFitsItsCanvas`
(`src/test/java/lib/minecraft/renderer/EntityOverlayFitTest.java:135-144`) fits the winged zombie
villager, zombie and skeleton adults to an unpadded square canvas and asserts `unusedSlack`, which
is the smaller of the two axes' margins (`:195-209`) - so a mismeasure shows only where it leaves
slack on both axes at once. The two the class javadoc names (`:38-43`), wings bounded by their mesh
and wings measured through another mesh, both stay inside the vertical span a standing adult's body
already fills: through the default iso camera the adult wing boxes, inflate included, project to
screen y -18.66 to 5.27 model px and the half-scale pair to -19.38 to -7.41, where the bodies span
-22.87 to 10.54 (zombie), -22.20 to 10.54 (skeleton) and -22.91 to 12.28 (zombie villager). Nor
does either widen the union past its height: the adult boxes take it to 22.17, 21.46 and at most
26.41 model px across, against spans of 33.41, 32.74 and 35.18. The store agrees on the zombie,
drawn at 228x523 unwinged and 356x523 winged. So height stays the fitted axis, the vertical margin
stays 0 and the loop pins the body fit alone, while its assertion message says the fit "must
reserve no room for unauthored wing space" (`:141`).

The zombie's `minecraft__zombie~elytra=true` row compares the winged canvas against vanilla's; the
zombie villager and skeleton hold no wings reference, and the class javadoc (`:33-36`) names these
assertions as the only check for them.

It settles when the adult loop measures on an axis the wings bound, and either named mismeasure
turns it red - or when it is named the body-fit control it is and drops the wing claim.

## ArmorParitySweep says every baby has an adult twin; the leather piglin baby has none

`ArmorParitySweep`'s class javadoc
(`src/visual/java/lib/minecraft/renderer/sweep/ArmorParitySweep.java:46-49`) pairs each baby
subject with the same entity in the same armour as an adult and reads the baby-model gap off that
twin, and the console note at `:181` says to read each baby row against its adult twin. The roster
(`:129-136`) and the harness roster it mirrors
(`harness/src/client/java/lib/minecraft/refharness/sweep/ArmorSweep.java:123-130`) hold seven
subjects, and the leather piglin is a baby with no adult. Its `sweep.armor` row,
`minecraft__piglin_leather-dyeb04030_baby` (2.0291), has none to be read against: the piglin adult
wears iron (2.4764) and the leather adult is a zombie (2.4943). The rows are a LOOK gauge, so
nothing gates the pairing.

`ArmorParitySweep.java` is line-pinned: `roster.armor-subjects` in
`src/test/resources/lib/minecraft/renderer/parity/index.json` cites lines `129-136` on the anchor
`SUBJECTS = List.of(`, and `ParityIndexTest` fails a range that does not open on its anchor, so a
javadoc edit that changes the line count moves that pointer in the same commit. Adding the adult
moves the range, owes the harness roster the same subject (the row's `re_derive` requires the two
to match), and adds rows that `sweep.armor`, `manifest.player-raw` and `manifest.references`
report as added until a capture of all three is promoted.

It settles when every baby in the two rosters has its adult twin, or when the javadoc and the note
say which baby has none and what it is read against instead.

## EQUIPMENT_AXES' javadoc says the armour and glint sweeps sit outside the reference tree

`EntityBoundsWalker.EQUIPMENT_AXES`
(`harness/src/client/java/lib/minecraft/refharness/frame/EntityBoundsWalker.java:836-844`) scopes
the equipment walk (`:813-815`) to a request selecting `equip` or `armor`, "because two sweeps
outside the reference tree equip theirs deliberately and frame them with a reserved margin instead
of a measurement". The two are `ArmorSweep` and `GlintSweep`, whose worn-leather subjects stand on
an armour stand, and both write sub-trees of the reference tree beside `entities/`:
`manifest.references` hashes the 7 `armor/` files and the 120 worn-leather frames under `glint/`.
What they sit outside is the entity sub-tree. The phrase names something else in this repo - B60
in `src/test/resources/lib/minecraft/renderer/parity/blindness.json`, "A probe writes outside the
reference tree, so nothing this store holds can see one".

The margin half holds for `ArmorSweep` alone, which fits the measured body at `BODY_FILL` 0.8
(`harness/src/client/java/lib/minecraft/refharness/sweep/ArmorSweep.java:65`, applied at `:172`).
`GlintSweep` hands the renderer a square canvas with no fit
(`harness/src/client/java/lib/minecraft/refharness/sweep/GlintSweep.java:174-176`), which scales
the measured body to fill it
(`harness/src/client/java/lib/minecraft/refharness/frame/EntityFrameRenderer.java:203-215`): row 0
of the reference `glint/minecraft__leather_helmet/frame_000.png` is 234 opaque pixels of helmet,
cut at the canvas edge, where the boots' frame opens on the stand's 2-pixel apex. The four armour
subjects are diagnostic by `GlintSweep`'s own javadoc.

An edit to `EntityBoundsWalker.java` fires B29 and B56, which plan the seven artifacts B56 sees,
`manifest.references` among them.

It settles when the javadoc names the entity sub-tree as what the two sweeps sit outside, and
gives the reserved margin to `ArmorSweep` alone.

## The parity-gate skill says glint puts mean_argb_delta in column 3 across six sweeps

The first reason under *Why not just diff* (`.claude/skills/parity-gate/SKILL.md:353-354`) reads
"**Five subject-id spellings across six sweeps**, and glint puts `mean_argb_delta` in column 3. The
canonical `awk '{s+=$2}'` is silently wrong there." `GlintParitySweep` writes the delta second
(`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:188-194`), and `SWEEPS`
(`parity/scripts/parity/sweep.py:19-20`) holds nine sweeps. The five spellings hold, as
`ids.Spelling` enumerates them. The skill counts six once more:
`.claude/skills/parity-gate/references/determinism.md:32` opens its reproducibility list with
"**All six parity sweeps.** Exactly reproducible", a count naming none of them, so which three of
the nine the claim leaves out is not stated.

All nine tables the cache holds under `cache/visual` carry `mean_argb_delta` in column 2. On the
glint table's 11 rows `awk '{s+=$2}'` sums 528.0750, the `summary.sum` the store holds for
`sweep.glint`, and column 3 is `status`, which sums to 0.0000 - an agent taking the skill at its
word reads a glint fleet of zero. No writer produces the frames-second shape; the fixture
`parity/scripts/parity/tests/data/sweep-glint.tsv` holds it, as `sweep.py:3-6` says. The toolkit
reads the delta by header name (`sweep.py:78-90`), so the claim reaches only a hand-rolled read,
the one the paragraph argues against.

It settles when the bullet keeps the five subject-id spellings, counts the sweeps as `SWEEPS` does
or not at all, and drops the column-3 claim along with the `awk '{s+=$2}'` sentence resting on it;
and when `determinism.md` names the sweeps its measurement covers, or all nine once each is shown to
reproduce.

## The toolkit and SweepReport count the sweep writers as six, where nine tables are written

`_sweep_of`'s docstring (`parity/scripts/parity/sweep.py:155-160`) keeps `subject` out of the
key-column fallback because "all six write it now". Six also stands in `_wanted_sweeps`
(`parity/scripts/parity/cli.py:369`, `:378`), `parity/scripts/parity/tests/test_sweep.py:44`,
`:117-118`, `:200`, `:262`, `parity/scripts/parity/tests/test_cli.py:213`, `:220`, `:228`,
`parity/scripts/parity/tests/data/.gitattributes:1` and `SweepReport`'s class and `KEY_COLUMN`
javadocs (`src/visual/java/lib/minecraft/renderer/sweep/SweepReport.java:16`, `:36`). `SWEEPS`
(`sweep.py:19-20`) holds nine: eight classes write through `SweepReport.write`,
`EntityAnimationParitySweep` once per gait, and all nine tables under `cache/visual` open on
`subject`.

`test_sweep.py:44`, `sweep.py:70`, `parity/scripts/parity/norm.py:11`, `:15` and
`.gitattributes:1-2` credit the writers with CRLF rows. `SweepReport.write` writes LF
(`SweepReport.java:109-122`) and no table under `cache/visual` holds a CR; the mixed form is one the
reader accepts and no writer in the tree produces. The toolkit sites fire B30 alone, whose `sees` is
empty; `SweepReport.java` fires B32, which selects fourteen artifacts, all nine sweeps among them.

It settles when nothing in the toolkit or `SweepReport` counts the sweeps apart from `SWEEPS` or
dates their shared shape, and no line-ending claim credits the mixed form to the writers.

## compare.py says a sweep row carries five to nine columns beside its key, where two carry ten

`_registered`'s docstring (`parity/scripts/parity/compare.py:296-300`) argues that a registration
key carries a set of values because a row is one key and every column beside it, and puts that
count at "from five to nine depending on the sweep" (`:297`). The nine tables under `cache/visual`
carry 5 beside `subject` (armour, menu, player), 6 (glint), 9 (block, entity, item) and 10 (entity
animation and entity walk, the two tables `EntityAnimationParitySweep` writes). The argument holds
at any count: a row moving two of its values still needs two registrations.

`compare.py` fires B30 alone, whose `sees` is empty, so the edit plans no artifact.

It settles when the docstring drops the range, or states the one the tables bear.

## test_sweep's glint docstring states numbers its fixture does not bear

`test_six_column_glint_shape_has_the_delta_in_column_three`
(`parity/scripts/parity/tests/test_sweep.py:29-41`) says `awk '{s+=$2}'` "returns 30 x 11 =
330.0000 on this shape, and 67 recorded uses never caught it, because column 2 is `frames`"
(`:32-33`).

The shape it reads is `parity/scripts/parity/tests/data/sweep-glint.tsv`: three rows at 30 frames,
so column 2 sums to 90.0, which the case asserts itself at `:39-40` against the delta's 131.7813 at
`:38`. The 330.0000 is the positional sum of an 11-row glint table in the frames-second order, which
no writer in the tree produces. `GlintParitySweep` writes the same 11 rows with `mean_argb_delta`
second (`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:193-194`), so there
`awk '{s+=$2}'` sums the delta. The 67 counts a corpus of hand-run sums the tree does not hold;
`parity/scripts/parity/README.md:3-5` cites the same number as the toolkit's motivation. The file
fires B30 alone, whose `sees` is empty.

It settles when the docstring states what the fixture bears - a positional sum of 90.0 that is the
frame count, against the delta's 131.7813 - and names the fixture's column order as one no sweep
writes.

## reach.py says PoseExpr and PosePredicate are declared twice

The comment above `ROOTS` in `parity/scripts/parity/reach.py:69-71` says that scanning the
generators leaves two top-level names declared twice, `PoseExpr` and `PosePredicate`, whose renderer
copies win the tie by sorting first. Each is declared once, under
`src/main/java/lib/minecraft/renderer/engine/pose/`, and nothing under `tooling/src/main/java`
declares either.

`declared_types` (`reach.py:338-343`) takes one binary name per `.java` file under the four
`SOURCE_ROOTS`, `package-info.java` aside, and `_resolve_roots` (`:371-385`) keys them by simple
name, first in sorted order - the mechanism the comment states, which holds. Over those four roots
808 files declare a type and no two share a file name, and the 808 types `parity/reach.json`
carries repeat no simple name. No root resolves through a tie, and `:72-73`, which warns that a root
named in two trees would take whichever sorts first, is the part that states the rule.

`reach.py` fires B30 alone, whose `sees` is empty, so the edit plans no artifact.

It settles when the comment states the tie-break and the naming rule without the pair.

## blindness.py's lead-in says one change set above two, and the view keeps refuted examples

The module docstring's lead-in to its worked examples, `parity/scripts/parity/blindness.py:57-58`,
says one pair of rules "answers both ways over one change set", and its first two bullets resolve
two: `ParityReferencesTest.java` alone (`:60-62`), where B39 demotes B37's selection on the one path
and `sees` comes back empty, and `BlindnessMapTest.java` beside `SelfCapture.java` (`:63-65`), where
`sees` holds B37's 11 artifacts and each of B39's 11 blind rows reads `selected_by=['B37']`. Each
answers as it says. No one set prints both: `ParityReferencesTest.java` beside `SelfCapture.java`
resolves to exactly the second answer, and the first path's demotion shows nowhere in it.

The rendered view carries the same lead-in at
`.claude/skills/parity-gate/references/blindness.md:34-37`, written by
`src/visual/java/lib/minecraft/renderer/store/view/ParityReferences.java:209-212`, and the examples
under it (`blindness.md:39-49`, `ParityReferences.java:214-224`) are not the module's:
`BlindnessMapTest.java` alone fires B33 and B39 and not B37, and on `PlayerRenderer.java`
`sweep.player` is in `sees`, B9 selecting it and claiming nothing blind.

`blindness.py` fires B30 alone; `ParityReferences.java` fires B37 and B39, whose demotion empties
`sees`. Neither edit plans an artifact, and `ParityReferencesTest` holds the view to its renderer,
so the second owes the view's regeneration.

It settles when both lead-ins say the pair answers one way on each of two change sets, and the
view's examples resolve as the module's do.

## B19's reason names a pose kit no type is, and argues from a selection the rule does not author

B19's `reason` (`src/test/resources/lib/minecraft/renderer/parity/blindness.json:530`, rendered at
`.claude/skills/parity-gate/references/blindness.md:293`) says "a pose kit is an entity render where
a model engine is every render". No type in a pose package - `asset/pose`, `author`, `bake/pose`,
`engine/pose` - is a kit, and no other tracked file says "pose kit". Its closing sentence, "The
glob answering for whichever file in it reaches furthest is what made a pose change cost a fluid
manifest", speaks for a glob-wide selection B19 does not make: it is `derived`, its `sees` empty.

The argument holds under the tree's own names. B19 fires on no `engine/pose` file but names
`src/main/java/lib/minecraft/renderer/bake/pose/ClipPlayer.java` and `PosePlayer.java`, which
`parity/reach.json` answers with the same five artifacts and no `manifest.fluid`; `Rasterizer`, "a
model engine" in both its constructor javadocs
(`src/main/java/lib/minecraft/renderer/engine/raster/Rasterizer.java:154`, `:167`), answers 17.
Resolved one path at a time, B19 carries both dump `BLIND` lines, which
`parity/scripts/parity/cli.py:905` prints with the reason, on all 119 tracked paths its triggers
match.

A fix is store text: `blindness.json` fires B33 and B34, which select nothing, and
`ParityReferencesTest` fails until `blindness.md` is regenerated with the
`-Dasset.parity.regenerateViews=true` rerun that `ParityReferences.REGEN_COMMAND` spells.

It settles when B19's reason argues from types the tree holds - a pose player against `Rasterizer`,
or `FluidRenderer` against `Renderer` as `parity/scripts/parity/blindness.py:9-12` does - and from
the graph's per-file answer rather than a glob's, and `blindness.md` is regenerated from it.

## B27's blindness reason repeats a clause

B27's `reason` (`src/test/resources/lib/minecraft/renderer/parity/blindness.json:791`) reads "what
it answers for the rest of the package is narrower, and what it answers for the rest of the package
is narrower - the corner phase is under the fluid and the portal, the unwrap is not", and the
generated view carries it at `.claude/skills/parity-gate/references/blindness.md:389`. The claim
after the repeat holds: `parity/reach.json` answers `CornerPhase` with `manifest.fluid` and
`manifest.portal`, and `Unwrap` with neither.

`parity/scripts/parity/cli.py:905` prints one `BLIND` line per row of a plan's blind list, and
`parity/scripts/parity/blindness.py:409-416` gives an uncontested claim a single row, the first
fired rule's. B19, earlier in the file, claims the same two dumps and fires on six of B27's seven
trigger files, so resolved one path at a time B27's reason prints only on
`src/main/java/lib/minecraft/renderer/vanilla/mesh/HumanoidPart.java`, where B9 and B27 fire. A
change set where another path selects a dump contests the claim, and each claiming rule keeps a row:
`CornerPhase.java` beside `content/read/BlockRendererOverrides.java`, which fires B20, prints the
repeat on two of its four dump lines.

A fix is store text: `blindness.json` fires B33 and B34, which select nothing, and
`ParityReferencesTest` fails until `blindness.md` is regenerated with the
`-Dasset.parity.regenerateViews=true` rerun that `ParityReferences.REGEN_COMMAND` spells.

It settles when B27's reason states the clause once and `blindness.md` is regenerated from it.

## Toolkit comments and ParityArtifacts cite a working-note design spine and narrate history

Five tracked files cite a working note's design spine, which resolves for nobody who clones the
repo: `parity/scripts/parity/sweep.py:18` and `parity/scripts/parity/ids.py:85` by section number,
`parity/scripts/parity/provenance.py:25` and `parity/scripts/parity/tests/test_compare.py:201` by
name, and `ALL`'s javadoc at `src/visual/java/lib/minecraft/renderer/store/ParityArtifacts.java:98`
by its order. The section `sweep.py` cites lists six sweep ids where `SWEEPS` holds nine.

`provenance.py:24-28` tells the one-sha rule as a before and after, `ParityArtifacts.java:18-19`
argues from "a superseded manifest be cited as current for three phases", and six toolkit comments
explain a present shape by what an earlier one did: `parity/scripts/parity/capture.py:74` and
`:339-340`, `parity/scripts/parity/cli.py:901`, `:959` and `:1337`, and
`parity/scripts/parity/tests/test_promote.py:232`.

The toolkit paths fire B30 alone, whose `sees` is empty, so they plan no artifact;
`ParityArtifacts.java` fires B37 alone, so a javadoc edit there plans B37's 11 artifacts.

It settles when no tracked file names the spine, each site stating the fact it cites the note for,
and those comments give their reasons against the tree as it stands.

## DiagnosticsTest.ErrorPlacement's stripper javadoc sits on the wrong method

In `src/test/java/lib/minecraft/renderer/diagnostic/DiagnosticsTest.java`, the javadoc describing
the stripper - "One file's code, with the comments and the javadoc stripped" (`:257-263`) - stands
directly above `records`' own one-line javadoc (`:264`) and the `records` declaration (`:265`).
javac attaches only the doc comment nearest a declaration, so that paragraph documents nothing, and
`code` (`:273`), the method it describes, carries no javadoc.

The same class's `builderBodies` javadoc says "A signature wraps over three lines in both files"
(`:300`), where `REFUSING` (`:183-186`) names three files - `FormWalker`, `PoseCompiler` and
`StyleRegistrar` - and of the four builders they declare, `PoseCompiler`'s instance `refuse` wraps
over two (`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:1822-1823`). The
conclusion it draws holds for all four: the brace opening the body arrives after the line the
builder is recognised by. No build step reads either doc: the one `javadoc` task documents `main`
alone (`build.gradle.kts:123-126`), and `parity/reach.json` maps `DiagnosticsTest` to no artifact.

It settles when the stripper's javadoc sits on `code`, and `builderBodies`' drops the file count and
the line count, neither of which holds for every builder `REFUSING` names.

## Fifty-three imports in the tracked Java are used by neither the code nor a javadoc reference

`src/test/java/lib/minecraft/renderer/author/audit/PoseAuditorTest.java:19` imports
`engine.pose.PoseOperator`, a name the file mentions nowhere else. Four more tests under
`src/test/java/lib/minecraft/renderer/` carry the same import unused:
`author/install/PoseCompilerCouplingTest.java:19`, `bake/pose/ClipPlayerTest.java:17`,
`bake/pose/PoseEvaluatorTest.java:13` and `content/table/EntityPosesTableStatesTest.java:7`. Across
the 1066 tracked `.java` files, 53 imports in 46 files name a type or static member that no code and
no javadoc `{@link}`, `@see` or `@throws` uses: 16 in 13 files under `src/main`, 18 in 16 under
`src/test`, 2 under `src/visual`, 1 under `src/jmh`, 7 under `tooling/` and 9 in 7 files under
`harness/`. In 47 of them the simple name appears nowhere else in the file. The other six name it
only where nothing resolves it: `BlockRenderer` names `BoxKit` in a line comment, `TraceReplayTest`
names `TintSource` in a string, and `FluidOptions`, `TintRegistrationResolver` and two harness
mixins name theirs inside a javadoc `{@code}`.

Nothing gates them. javac has no lint for an unused import and the build configures no other,
`PolicyPurityTest` reads the tooling's imports for a banned prefix alone, and
`TierOrderTest.everyImportRunsDownhill`, which holds every import filed in a library or generator
package, skips one whose simple name the stripped code never uses
(`src/test/java/lib/minecraft/renderer/guard/TierOrderTest.java:176`). So
`src/main/java/lib/minecraft/renderer/engine/geometry/Unwrap.java:4-5` and
`src/test/java/lib/minecraft/renderer/engine/geometry/CornerPhaseTest.java:3-4` both import
`bake.mesh.BlockGeometryKit` and `engine.mesh.BoxKit`, tiered above `engine.geometry`, and count no
edge. The sixteen `src/test` files map to no artifact in `parity/reach.json`; twelve of the thirteen
`src/main` files map to some - `Shading` to 18, `Unwrap` to 14, `BlockRenderer` to 10 - and the
graph is class-granular, so an import-only edit there prices that reach.

It settles when no tracked Java file imports a name that neither its code nor a javadoc reference
uses. The `src/test` share, the five `PoseOperator` imports among it, is the part that prices
nothing.
