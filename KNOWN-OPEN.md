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
(`src/test/java/lib/minecraft/renderer/author/install/StyleRegistrarAuditTest.java:101-116`) has the
audit refuse a custom style reading the pufferfish's `left_blue_fin`, a bone its small size form
lacks. The roster holds twelve showcases (`PoseShowcaseDriver.java:150-210`). `PoseAuditorTest`
audits four of them at the driver's own spelling - `rear`, `flutter`, `levitate`, and the horse's
`standAnimation=1` silhouette through the same splice under another id - and no test runs the
driver. It is a visual-set `main` behind the `poseShowcase` task (`gradle/visual.gradle.kts:140`),
the one caller of `validate` outside the tests, and `parity/reach.json` maps it to no artifact.

It settles when a showcase whose audit throws costs its own render and no other: the audit runs
inside the per-showcase `try`, or inside one of its own that reports the refusal and moves on.

## A bone holds its rotation in float degrees, and a radian no float degree reaches is lost

`EntityMesh.Bone`'s rotation
(`src/main/java/lib/minecraft/renderer/asset/mesh/EntityMesh.java:226-236`) is an `EulerRotation`,
which carries degrees
(`src/main/java/lib/minecraft/renderer/engine/geometry/EulerRotation.java:8-10`) and answers
`(float) Math.toRadians(value)` (`:78-80`) to `BoneKit`
(`src/main/java/lib/minecraft/renderer/bake/mesh/BoneKit.java:250-252`). Vanilla's
`ModelPart` holds `xRot`, `yRot` and `zRot` as the float radians a `PartPose` literal or a
`setupAnim` write puts there, and converts nothing. A radian enters a degree float in two places.
At rest, `GeometryParser` writes `(float) Math.toDegrees(r)` for every `PartPose.rotation` and
`offsetAndRotation` it walks
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:2186-2190`,
`:2203-2207`), and `src/main/resources/lib/minecraft/renderer/entity_geometry.json` ships those
degrees. Posed, `PosePlayer.degrees`
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:744-751`) folds each written
rotation channel to `(float) Math.toDegrees(value)`, except one written back to the radian the bone
already reads; a pose's write, a clip's displacement and each container step all reach it through
`posedBone` (`:451`, `:462`, `:885`).

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

A bone that holds radians changes `EntityMesh`, which the reach graph answers with nineteen
artifacts, or `EulerRotation`, degrees by contract for every display transform and camera too and
answered with twenty-four, the two dumps among them demoted by B19 and B26, and `PosePlayer`,
answered with six. The rest half also moves the table: the generator edit owes the
`tooling-flow-gate` loop, and a regenerated `entity_geometry.json` selects thirteen artifacts
through B35.

It settles when a bone's rotation reaches `BoneKit` as the radian vanilla's part field holds - the
table carrying rest radians and `posedBone` handing a written radian through unconverted - or when
carrying degrees is recorded in *Decisions that stay closed* beside the float-multiply refusal,
whose measurement already covers both halves.

## The DOWN reversal flips its crop in place, which is safe only while every crop is a fresh buffer

`HumanoidPart.textures`
(`src/main/java/lib/minecraft/renderer/vanilla/mesh/HumanoidPart.java:246-253`) and
`WornBox.Mesh.textures` (`src/main/java/lib/minecraft/renderer/bake/armor/WornBox.java:136-143`)
reverse `DOWN`'s rows by calling `flipVertical()` on the buffer their crop returned, and the pinned
image library's `PixelBuffer.flipVertical` swaps rows in place. It is the one write a `FaceTextures`
supplier makes into what it hands out: the cape's half turn is `rotate180()`, which returns a copy
(`src/main/java/lib/minecraft/renderer/bake/mesh/PlayerAssembly.java:247`).

It is safe because both crops allocate. `HumanoidPart.cropRect` (`HumanoidPart.java:285-301`) and
`Unwrap.Atlas.crop` (`src/main/java/lib/minecraft/renderer/engine/geometry/Unwrap.java:144-166`)
build a new array per call, and both crops' `@return` promise a new buffer (`HumanoidPart.java:212`,
`Unwrap.java:141-142`). `FaceTextures.byFace` says nothing about who owns what it returns, and the
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

## An adult's wings measure 33 columns past what they draw, in the harness and the renderer alike

Rendered at its own bounds, an adult wearing an elytra leaves 33 blank columns on the left of its
canvas beyond the padding, where the same subject unwinged leaves none.
`src/test/java/lib/minecraft/renderer/EntityOverlayFitTest.java` measures it on the zombie villager,
the zombie and the skeleton, and a villager and a cow show the same 32 to 33. The renderer folds the
wings into the canvas through `EntityGeometryKit.computeScreenBounds` over `ElytraKit.wingsMesh` and
the wing texture (`src/main/java/lib/minecraft/renderer/EntityRenderer.java:309-312`). Handed no
texture, so that it walks the raw box corners, it measures exactly the same: the texture-tight walk
reaches the wing box's own corner on that side while the render leaves it empty. The comment above
the call (`:305-308`) says the geometric box would size the canvas well outside the drawn wing
outline, which the identical measurement does not bear.

Vanilla's reference carries the same columns: `entities/minecraft__zombie~elytra=true.png` is
356x523 and opens on 33 blank ones, and the baby's on 16. So the harness's bounds walk measures the
wings the same way, which is why the stored zombie row matches at 0.0143 on equal canvases, and why
the test holds the left margin to that excess (`WING_BOX_CORNER`) rather than to the padding alone.
What leaves the corner undrawn is not measured.

It settles when the cause is named and either both walks measure what the wings draw - the harness
and the renderer together, since the sweep holds the renderer to the harness's canvas - or the
reserved columns are recorded as the canvas contract; the comment above the call and
`WING_BOX_CORNER` follow whichever holds.

## A block-backed slot icon is tinted at plains, not by its item definition

`GuiIcon.render` sends an id the item index lacks and the block index carries to the isometric block
renderer (`src/main/java/lib/minecraft/renderer/ItemRenderer.java:678-679`) through `adaptToBlock`
(`:700-714`), which names no biome. `BlockOptions` answers with its own default,
`Biome.of(BiomeClimate.PLAINS)`
(`src/main/java/lib/minecraft/renderer/request/BlockOptions.java:73`). `Isometric3D`'s assembly
binds the render's one tint from it (`src/main/java/lib/minecraft/renderer/BlockRenderer.java:386`,
`:283-290`), and an icon's build hands that tint to every face with a tintindex through
`FaceTint.split` (`:520-521`, `:604`). Vanilla's slot icon consults neither a biome nor
`BlockColors`: `ItemModels` binds `minecraft:model` to `CuboidItemModelWrapper`, whose `update`
calculates each tint source of the item definition and appends it to the layer's tint layers in
every display context (javap, 26.1).

Of the 704 26.1 item definitions whose root is a plain `minecraft:model` naming a block model, none
has a `models/item` file or names a block model an item model shares a name with, so every one takes
this branch, and 8 carry a tint list. They are the only definitions that declare a tint and have no
same-named item model, and the only item ids with a `block_tints.json` row and no item model. The
renderer's plains sample lands on colormap pixel `(50, 173)` whether computed in float, as
`ColorMap.sample` does, or in double, as vanilla's `Biome` does: foliage `0xFF77AB2F`, grass
`0xFF91BD59`. Acacia, dark oak, jungle and oak leaves draw `0xFF77AB2F` where their definition's
`constant` is `0xFF48B518`, mangrove leaves draw it where theirs is `0xFF92C648`, and `grass_block`
draws `0xFF91BD59` on its tinted faces where its `grass` source at `(0.5, 1.0)` answers
`0xFF7CBD6B`. Birch and spruce agree, their `CONSTANT` rows
(`src/main/resources/lib/minecraft/renderer/block_tints.json:34-43`) being their definitions'
constants. No `BiomeClimate` row answers `0xFF48B518` foliage or `0xFF7CBD6B` grass, sampled in
either precision or overridden, and `ItemOptions` carries no biome, so a `GUI_ICON` caller has no
way to ask for another point.

The held path takes the definition's tints. `Held3D.heldTints` calculates each through
`ItemTint.resolve` (`ItemRenderer.java:501-506`,
`src/main/java/lib/minecraft/renderer/bake/texture/ItemTint.java:86-103`), and `heldBlockOf` picks
them per tintindex through `BlockGeometryKit.FaceTint.layers` (`ItemRenderer.java:480`,
`src/main/java/lib/minecraft/renderer/bake/mesh/BlockGeometryKit.java:225-228`). Held, the four
leaves draw `0xFF48B518`, mangrove leaves `0xFF92C648` and `grass_block` `0xFF7CBD6B`, vanilla's
colours, and `HeldBlockItemTest` pins the tints of oak, mangrove and `grass_block`
(`src/test/java/lib/minecraft/renderer/HeldBlockItemTest.java:94-105`), so the renderer's held and
slot pictures of each of the six disagree.

`ItemOptions` documents `GUI_ICON` as what a GUI slot shows and as the menu's and the atlas's icon
(`src/main/java/lib/minecraft/renderer/request/ItemOptions.java:34-37`).
`MenuOptions.MenuSlotContent.of(String)` draws a slot item through it
(`src/main/java/lib/minecraft/renderer/request/MenuOptions.java:235-240`), as do the menu's mark
icons and fill (`src/main/java/lib/minecraft/renderer/MenuRenderer.java:311-316`, `:355-360`).
`RENDERER-RULES.md:528-531` states the plains default for a block icon and nothing for a slot icon.
The atlas's block pass does not go through `GuiIcon`: `renderBlockTile` builds its own
`BlockOptions` at the same default
(`src/main/java/lib/minecraft/renderer/AtlasRenderer.java:213-218`) and draws the six the same way,
and its item pass walks `knownItemIds` (`:320`), which holds item-index ids alone
(`src/main/java/lib/minecraft/renderer/content/index/AtlasOrder.java:49-57`).
`RENDERER-RULES.md:113-115` says the atlas routes every block-backed tile through `adaptToBlock`,
but no atlas tile reaches it, and the row that sentence leans on, `adaptToBlockCarriesTheFlag`
(`src/test/java/lib/minecraft/renderer/AtlasRendererMissingTextureTest.java:140-154`), renders
through `ItemRenderer` rather than the atlas. A fix inside `adaptToBlock` alone leaves the atlas
where it is.

Nothing gates the colour but tests that pin it. `MissingTextureTintRosterTest` asserts the plains
products as expected: oak leaves
(`src/test/java/lib/minecraft/renderer/MissingTextureTintRosterTest.java:62-67`, named for the
foliage colormap at plains), mangrove leaves (`:69-77`, whose comment has the isometric branch read
the block tint table where the held view reads the per-item constant) and `grass_block` (`:161-172`,
where `0xFF8D0057` is the magenta texel times plains grass). Its held mangrove row (`:79-99`) holds
the held view at the constant, so the two mangrove rows pin the disagreement. `GuiIcon`'s javadoc
calls a block-backed icon byte-identical to the isometric block render at the same output frame
(`ItemRenderer.java:629-631`), and `ItemRendererGuiIconTest` holds that for stone and `red_bed`
alone (`src/test/java/lib/minecraft/renderer/ItemRendererGuiIconTest.java:65-89`), neither tinted.
No stored artifact draws a tinted block-backed slot icon. The item and glint sweeps render `GUI_2D`
(`src/visual/java/lib/minecraft/renderer/sweep/ItemParitySweep.java:131`,
`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:333`), the item sweep over
item-index ids alone (`ItemParitySweep.java:98-101`). The menu sweep's screens hold no slot item
(`src/visual/java/lib/minecraft/renderer/sweep/MenuParitySweep.java:77-114`), and the five ids the
menu driver's stored screens draw in a slot or as fill each have an item model
(`src/visual/java/lib/minecraft/renderer/driver/MenuRenderDriver.java:82-207`). The block sweep
renders through `BlockRenderer` at `Biome.INVENTORY_DEFAULT`
(`src/visual/java/lib/minecraft/renderer/sweep/BlockParitySweep.java:186`) and never reaches
`adaptToBlock`, and the atlas reaches no artifact the store holds
(`AtlasRendererMissingTextureTest.java:29-32`).

It settles when `GUI_ICON`'s block branch and the atlas's block pass tint a block-backed id from its
item definition's tint list, picked per tintindex as the held path picks it: the slot icons of oak
leaves, mangrove leaves and `grass_block` draw `0xFF48B518`, `0xFF92C648` and `0xFF7CBD6B`, the
roster test's `grass_block` row expects `0xFF790068`, the product its short grass row holds,
`GuiIcon`'s javadoc states where a tinted icon departs from the plain block render, and
`RENDERER-RULES.md:113-115` names the path the atlas's block tiles take. Passing
`Biome.INVENTORY_DEFAULT` from `adaptToBlock` and `renderBlockTile` settles five of the six and
leaves mangrove leaves at `0xFF48B518`.

## A held element model ignores its item definition's tints and puts a caller tint on every face

`Held3D.buildTrianglesAtTick` builds an item model that declares `elements` with
`FaceTint.split(tint, tint)` (`src/main/java/lib/minecraft/renderer/ItemRenderer.java:532-533`),
one colour whichever side of the split a face falls on, and that colour is the caller's
`DecorationOptions.getTintColor()`, else white (`:437`). `BlockGeometryKit` colours each face by
what its `FaceTint` answers for the face's `tintindex`
(`src/main/java/lib/minecraft/renderer/bake/mesh/BlockGeometryKit.java:440`), so every face takes
it. The frame item carries its tint list - `ItemModelDispatch.resolveRenderItem` from the walked
branch (`src/main/java/lib/minecraft/renderer/content/index/ItemModelDispatch.java:155-161`),
`ItemIndexBuilder.itemOf` from the definition
(`src/main/java/lib/minecraft/renderer/content/index/ItemIndexBuilder.java:116-117`) - and nothing
on this branch reads `Item.tints()`, so no definition tint reaches an element face, and neither does
a leather, potion or firework override, each of which reaches a layer only through one. The
block-backed branch beside it makes the per-index pick: `heldBlockOf` builds its faces with
`FaceTint.layers(heldTints(...))` (`ItemRenderer.java:480`), `heldTints` resolving each definition
tint through `ItemTint.resolve` (`:501-506`), and `FaceTint.layers`
(`BlockGeometryKit.java:225-228`) gives a face the layer its tintindex names, else white.

The caller's `tintColor` lands on faces at `tintindex` -1 and 1 as well as 0, and each `Held3D`
branch that tints deals it out by a rule of its own. The flat branch
(`src/main/java/lib/minecraft/renderer/bake/texture/ItemTint.java:52-63`) gives it to a layer whose
definition tint falls back to it, else, where the layer has no definition tint, to the tintindex-0
layer alone, every other layer staying white. The block-backed branch gives it to a face only
through a definition tint that falls back to it (`ItemTint.java:86-103`), with no tintindex-0
fallback. The element branch gives it to every face. `DecorationOptions.tintColor`
(`src/main/java/lib/minecraft/renderer/request/DecorationOptions.java:21-25`) scopes the knob to
colour-overlay items; the element branch's `@param` (`ItemRenderer.java:523`) says it applies to an
element model's faces, which is what it does.

Vanilla has one path for both geometries. `CuboidItemModelWrapper$Unbaked.bake` bakes the model's
quads through `ResolvedModel.bakeTopGeometry` whether they come from `elements` or from
`builtin/generated`, `CuboidItemModelWrapper.update` calculates each tint the definition lists into
the layer's `tintLayers`, and `ItemFeatureRenderer.getLayerColorSafe` gives a quad
`tintLayers[tintIndex]`, or `-1` where the quad is untinted or its index is out of range (javap,
26.1). An element face at `tintindex` N takes the definition's Nth tint exactly as generated layer N
does, which is the pick `FaceTint.layers` makes.

On the 26.1 assets the branch draws one model. `item/spyglass_in_hand` is the only item model that
declares `elements`, reached by a held `minecraft:spyglass` through its `display_context` select's
fallback and by the index entry named after it, and it has no `tintindex` face. Across the 1506 item
definitions, 71 `minecraft:model` nodes carry `tints`: 63 name a flat `builtin/generated` model and
8 a block model, which `heldBlockOf` draws through the per-index pick; none names the spyglass. The
dropped tints therefore need a pack - an element `item/leather_helmet` under vanilla's definition
draws its undyed texture where vanilla multiplies the dye default `0xFFA06540` into its `tintindex`
0 faces - and no pack this workspace renders against ships a tinted definition, an item model that
declares `elements`, or an override of a tinted item model. The caller tint is reachable on vanilla
assets: a held spyglass given a `tintColor` is multiplied whole, and so is its `GUI_2D` sprite,
layer0 being tintindex 0 by the generated convention (`ItemTint.java:156-161`).

Nothing gates either half. No stored artifact renders `HELD_3D`: the item and glint sweeps render
`GUI_2D` (`src/visual/java/lib/minecraft/renderer/sweep/ItemParitySweep.java:131`,
`src/visual/java/lib/minecraft/renderer/sweep/GlintParitySweep.java:333`).
`HeldDisplayContextTest.heldSpyglassResolvesTheElementModel`
(`src/test/java/lib/minecraft/renderer/HeldDisplayContextTest.java:73-81`) asserts only that the
held spyglass resolves a model with elements, and
`ItemRendererMissingTextureTest.elementFaceTexturesSubstitute`
(`src/test/java/lib/minecraft/renderer/ItemRendererMissingTextureTest.java:92-108`) draws the branch
at the default white. The tests that hold the per-index pick reach the block-backed branch and the
kit, not this one: `HeldBlockItemTest.heldTintsAreTheDefinitions`
(`src/test/java/lib/minecraft/renderer/HeldBlockItemTest.java:94-105`),
`MissingTextureTintRosterTest.mangroveLeavesHeldTakeTheirDefinitionConstant`
(`src/test/java/lib/minecraft/renderer/MissingTextureTintRosterTest.java:79-99`) and
`BlockGeometryKitTest.layerTintPicksTheNamedLayerElseWhite`
(`src/test/java/lib/minecraft/renderer/bake/mesh/BlockGeometryKitTest.java:164-173`). No test
renders an item with a `tintColor`; `ItemTintResolveTest`
(`src/test/java/lib/minecraft/renderer/bake/texture/ItemTintResolveTest.java:55-66`) resolves one
map-colour tint through `ItemTint.resolve`.

It settles when the element branch colours its faces through `FaceTint.layers` over the frame
item's tint list, each entry resolved through `ItemTint.resolve`, as `heldBlockOf` colours a
block-backed id's through `heldTints`, and the caller's `tintColor` reaches an element face by a
rule the fix states rather than inherits. `FaceTint.layers` over the resolved list is the
block-backed branch's rule and leaves every spyglass face white; the flat branch's tintindex-0
fallback carried onto faces leaves them white too; only the whole-model multiply keeps the held
spyglass matching its `GUI_2D` sprite. No vanilla asset draws a tinted element model, so the test
that holds the fix builds one.

## Item-model parents resolve among item models alone, so a held dripleaf draws the missing cube

`ResolvedModels.load` resolves block models and item models in two separate passes
(`src/main/java/lib/minecraft/renderer/content/pack/ResolvedModels.java:60-65`). In each pass,
`resolveModels` builds its parent lookup from that kind's raw map alone (`:79-88`). Both walks over
it climb through `parentOf` (`:213-221`), which answers empty for a parent that map does not hold:
`mergeParentChain` then returns the model's own copy (`:242-243`), and `resolveDisplay` stops
climbing (`:180`). Vanilla has a single model namespace. `ModelManager.MODEL_LISTER` is
`FileToIdConverter.json("models")`, so `loadBlockModels` lists every file under `models/` into one
map keyed `minecraft:block/...` and `minecraft:item/...`. `ModelDiscovery`'s resolver answers a
parent with a `Map.get` on that map and falls back to the missing model when the parent is absent
(javap, 26.1). The class javadoc calls the merge exactly the vanilla client's per-file resolution
(`:38-39`) and says the display resolves per slot as vanilla's `findTopTransform` walks it
(`:28-30`). `mergeParentChain`'s javadoc keeps `kindPrefix` unused because every parent reference
already carries its kind segment (`:231-233`), and `resolveDisplay` and `parentOf` document their
map as every raw model of this kind (`:172`, `:210`). The lookup searches only the calling kind's
map.

The 26.1 set holds 3676 models, 2392 block and 1284 item. Two of them name a parent of the other
kind, and both are item models naming a block model: `item/big_dripleaf` names `block/big_dripleaf`
and `item/small_dripleaf` names `block/small_dripleaf_top`. No block model names an item parent, and
the only parent outside both maps is `builtin/generated`. Each of the two resolves to its own file
alone, with no elements and no textures, so `ModelData.rendersNothing`
(`src/main/java/lib/minecraft/renderer/asset/model/ModelData.java:80-96`) holds for both.
`ItemIndexBuilder.load`
(`src/main/java/lib/minecraft/renderer/content/index/ItemIndexBuilder.java:84-88`) drops them as
templates, and `addDispatchOnlyItems` skips them because their backing model failed that filter
(`:149-150`). Their item definitions name an item model, which
`ItemModelTreeLoader.deriveBlockItemModels`
(`src/main/java/lib/minecraft/renderer/content/pack/ItemModelTreeLoader.java:177-183`) does not
take, so `BlockIndexBuilder.primaryBlock`
(`src/main/java/lib/minecraft/renderer/content/index/BlockIndexBuilder.java:485-486`) leaves the big
dripleaf's `modelIcon` false, and `attachBlockstateOnlyBlocks` (`:672-674`) leaves the small one's
false, 26.1 shipping no `block/small_dripleaf` model. `Held3D.render`
(`src/main/java/lib/minecraft/renderer/ItemRenderer.java:386-401`) then has no route to draw and
falls to the missing cube, or refuses when the substitution is off. Vanilla holds the big dripleaf
as `block/big_dripleaf`'s six elements, posed by the item model's own `thirdperson_righthand`
(`[0, 0, 0]`, `[0, 1, 0]`, `0.55`). It holds the small dripleaf as `block/small_dripleaf_top`'s
eight elements, posed at `[0, 0, 0]`, `[0, 4, 1]`, `0.55`.

The display walk meets the same lookup. `resolveDisplay` (`ResolvedModels.java:175-188`) reads each
file's own slots with its left hands filled from its right, and climbs no further than the item
model, so each item model holds its own slots and none of `block/block`'s. The big dripleaf holds
six, `gui`, `fixed` and the four hand slots; the small one holds the four hand slots alone.
Vanilla's walk through the block parent finds eight declared for each: it adds `block/block`'s
`ground` and `on_shelf` to the big dripleaf, and its `gui`, `ground`, `fixed` and `on_shelf` to the
small one. Neither misses a hand, because vanilla's deserializer fills a file's left hands from that
file's right hands before the walk, which shadows `block/block`'s own `firstperson_lefthand`. No
renderer reads `ground`, `fixed` or `on_shelf`: the production readers of a display are
`Held3D.heldDisplay` for `thirdperson_righthand` (`ItemRenderer.java:581-586`) and
`BlockIndexBuilder.iconGuiFor` for `gui` (`BlockIndexBuilder.java:396-419`). Both item models
declare their own `thirdperson_righthand`, and the big dripleaf its own `gui`. The small dripleaf's
item model has no `gui`, so its icon falls to its block row's model (`:416-418`), which inherits
`block/block`'s `[30, 225, 0]` at `0.625`, the value vanilla's walk reaches. The dump serializes
each resolved model's display
(`src/visual/java/lib/minecraft/renderer/dump/PipelineParityDump.java:1807`), so its
`item-models.json` records the six slots and the four where vanilla resolves eight for each.

A pack's item model that names a block parent resolves the same way. `resolveModel`
(`ResolvedModels.java:127-131`) then reports it as an empty template, which misnames the cause.
The Hypixel+ 0.23.4 pack in the texture-pack cache ships one: `hplus:item/personal_deletor` names
`block/lava_cauldron` and binds only a `particle` texture of its own. None of the three packs the
pack dump stacks (`PipelineParityDump.java:129`) ships one.

Nothing compares the result to vanilla. No stored artifact renders `HELD_3D`: the `item-render-2d`
members of `manifest.visual` are the driver's default `GUI_2D` run. The item sweep draws `GUI_2D`
from the `items/` reference tree
(`src/visual/java/lib/minecraft/renderer/sweep/ItemParitySweep.java:59`, `:131`), and that tree
holds no dripleaf. The block sweep's `minecraft__big_dripleaf` and `minecraft__small_dripleaf` rows
(`src/test/resources/lib/minecraft/renderer/parity/sweeps/block.json:723-734`, `:9807-9818`) are at
0 differing pixels, drawn by `BlockRenderer` directly against the harness's GUI render of each block
as an item (`src/visual/java/lib/minecraft/renderer/sweep/BlockParitySweep.java:33-39`).
`HeldBlockItemTest` pins the present outcome. Its bootstrap asserts that the big dripleaf's
`modelIcon` is false (`src/test/java/lib/minecraft/renderer/HeldBlockItemTest.java:61-62`), and
`dripleafStaysOnTheMissingModel` (`:107-113`) asserts the refusal, as `Held3D`'s javadoc
(`ItemRenderer.java:349-352`) and its render comment (`:391-394`) document. `ResolvedModelsTest`
resolves no parent across kinds. `parity/reach.json` answers `ResolvedModels` with
`digest.colormap-lut` and the two dump manifests. Their `item-models.json` and `items.json`
(`src/test/resources/lib/minecraft/renderer/parity/manifests/dump-vanilla.json:22`, `:26`) hash
the two blank models with their own slots alone, and an index without them.

A single namespace puts both models into the item index, and that moves `GUI_ICON`.
`GuiIcon.render` (`ItemRenderer.java:675-682`) sends an item-index id through `Gui2D`. Its layer
loop (`:197-217`) stops at the first `layerN` the item does not bind (`:201`), and an element model
binds none. The two would be the only item-index ids whose `gui` resolution lands on an element
model: of the 1284 item models only `item/spyglass_in_hand` declares elements, and the spyglass's
definition answers `gui` with its flat model. Both ids fall to the block path, which the block
sweep holds at zero. `sweep.block` would not see that move, because `BlockParitySweep` builds its
own `BlockRenderer` (`BlockParitySweep.java:101`), and no stored `GUI_ICON` render holds a dripleaf.

It settles when a model's parent resolves against one map holding both kinds, as vanilla's does. A
held big dripleaf and a held small dripleaf then draw their block geometry at their item models'
own `thirdperson_righthand`, each item model's display carries the eight slots vanilla's walk
finds, `GUI_ICON` still draws both as the block sweep holds them, and
`dripleafStaysOnTheMissingModel` becomes a test that they draw.

## ColorMap samples in float where vanilla samples in double, and eight biomes land a pixel apart

`ColorMap.sample` (`src/main/java/lib/minecraft/renderer/asset/ColorMap.java:69-81`) clamps,
multiplies and subtracts in float: `adjRain` at `:71`, then `(int) ((1.0f - adj) * COORD_MAX)` for
the column and the row at `:73-74`. Vanilla's `Biome.getGrassColorFromTexture`,
`getFoliageColorFromTexture` and `getDryFoliageColorFromTexture` clamp in float and widen with
`f2d` before `GrassColor.get`, `FoliageColor.get` and `DryFoliageColor.get`, and
`ColorMapColorUtil.get(double, double, int[], int)` does the multiply, the subtraction and the scale
in double; the item tint `GrassColorSource.calculate` widens its two fields the same way (javap,
26.1). In the eight rows below the rounding is the subtraction's: `1.0f - adj` rounds up to the
float nearest `0.6` or `0.8`, whose product with `255f` rounds to a whole `153` or `204`, while
vanilla's double sits at `152.9999985` or `203.9999992` and truncates one lower.

Recomputed over the 65 rows of `src/main/java/lib/minecraft/renderer/vanilla/BiomeClimate.java`,
eight pick a different pixel: `MEADOW` and `CHERRY_GROVE` (`:42-43`, row 153 against vanilla's
152), `TAIGA` and `OLD_GROWTH_SPRUCE_TAIGA` (`:46`, `:49`, row 204 against 203), and
`WINDSWEPT_HILLS`, `WINDSWEPT_GRAVELLY_HILLS`, `WINDSWEPT_FOREST` and `STONY_SHORE` (`:75-77`,
`:82`, column 204 against 203). On the extracted 26.1 colormaps one of those pairs differs in a
colour a render reads: meadow's foliage, which the renderer answers `0xFF64A948` where vanilla
answers `0xFF63A948`, one in red. Cherry grove's foliage pair differs the same way, but the row
carries a foliage override that `Tints.biome` answers before any sample
(`src/main/java/lib/minecraft/renderer/bake/texture/Tints.java:57-58`). Every other pair holds one
colour on all three maps, and the `defrosted` pack's grass and foliage maps hold one colour at all
eight points. Off the table the split is wider: over a 0.01 grid of the unit square, 209 of 10201
climate points pick a different pixel, and a pack's item definition can declare a grass point
anywhere in it, which `ItemTint.resolve` samples through the same method
(`src/main/java/lib/minecraft/renderer/bake/texture/ItemTint.java:96-98`).

Nothing stored or tested sees it. `parity/reach.json:351-369` answers `ColorMap` with sixteen
artifacts, and every sample they take lands on `(0.5, 1.0)` or `(0.8, 0.4)`, two points where the
float and the double agree. `ColorMap.sample` has two callers. Through `Tints.biome`
(`Tints.java:63`), `sweep.block` renders at `Biome.INVENTORY_DEFAULT`'s `(0.5, 1.0)`
(`src/visual/java/lib/minecraft/renderer/sweep/BlockParitySweep.java:186`), as does the carried
block (`src/main/java/lib/minecraft/renderer/EntityRenderer.java:903`); the visual block drivers
and a block-backed id's GUI icon (`src/main/java/lib/minecraft/renderer/ItemRenderer.java:700-713`)
take `BlockOptions`' default `PLAINS` at `(0.8, 0.4)`
(`src/main/java/lib/minecraft/renderer/request/BlockOptions.java:73`); and the fluid manifest's
cherry grove (`src/visual/java/lib/minecraft/renderer/driver/FluidRenderDriver.java:165`) resolves
`WATER`, which samples no colormap. Through `ItemTint.resolve`, an item samples at its
definition's own point - the flat icon and the held sprite through `resolveLayerTint`, and a held
block-backed id through `Held3D.heldTints` (`ItemRenderer.java:501-505`), which resolves
`grass_block`'s grass tint there and consults no block tint source - and the six 26.1 grass
definitions, `bush`, `fern`, `grass_block`, `large_fern`, `short_grass` and `tall_grass`, all sit
at `(0.5, 1.0)`. `digest.colormap-lut` hashes the colormap bytes, and the two dump manifests
record those bytes and each grass tint's declared point
(`src/visual/java/lib/minecraft/renderer/dump/PipelineParityDump.java:797-802`, `:1187-1191`),
never a sample.

The tests pin agreeing points only: `ColorMapTest` at `(0.5, 1.0)`
(`src/test/java/lib/minecraft/renderer/asset/ColorMapTest.java:35`, `:50`), `ItemTintResolveTest`
at `(0.5, 1.0)` and `(1.0, 0.0)`
(`src/test/java/lib/minecraft/renderer/bake/texture/ItemTintResolveTest.java:43-44`),
`HeldBlockItemTest` at `grass_block`'s `(0.5, 1.0)` on the vanilla map
(`src/test/java/lib/minecraft/renderer/HeldBlockItemTest.java:101-102`),
`MissingTextureTintRosterTest` at plains and at short grass's `(0.5, 1.0)`
(`src/test/java/lib/minecraft/renderer/MissingTextureTintRosterTest.java:65`, `:137`), and
`BiomeTintTest` at the inventory centre
(`src/test/java/lib/minecraft/renderer/bake/texture/BiomeTintTest.java:68`), with uniform fills
or overrides everywhere else. So a double path would move no stored byte, and nothing would
catch a regression of one either. The method's javadoc (`ColorMap.java:44-53`) calls the formula
byte-for-byte identical to `ColorMapColorUtil.get` and writes it without a type, which reads as
covering the float arithmetic it does not match.

The one reach today is a caller's own. `BlockOptions`' biome is a public builder input, and
`Biome.of(BiomeClimate.MEADOW)` on any of the six foliage-tinted blocks in
`src/main/resources/lib/minecraft/renderer/block_tints.json` - oak, jungle, acacia, dark oak and
mangrove leaves and the vine - tints the block one step of red off vanilla's. A pack item
definition declaring a grass point among the 209 draws the item off vanilla the same way, and no
pack in the cache declares one off `(0.5, 1.0)`. Nothing in the workspace renders either.

It settles when `ColorMap.sample` widens the clamped temperature and downfall to double and takes
the product, the row and the column there, as `ColorMapColorUtil.get` does, with a `ColorMapTest`
case at a point the two disagree on - meadow's `(0.5, 0.8)` - or when float sampling is recorded
in `RENDERER-RULES.md`'s *Decisions that stay closed* and the javadoc's identity claim is narrowed
to the points where it holds.

## The pose table drops the happy ghast's harnessed body scale

Vanilla's `HappyGhastModel.setupAnim` assigns `body.xScale`, `yScale` and `zScale` `0.9375f`
whenever the render state's `bodyItem` is not empty (offsets 5-39), and `body` parents `inner_body`
and all nine tentacles (javap, 26.1). The shipped row carries only the empty-stack arm, each axis a
read of the body's own scale
(`src/main/resources/lib/minecraft/renderer/entity_poses.json:83034-83053`), and no `states` member:
the fold answers the stack's question at its rest, and a state silhouette keeps position and
rotation channels alone
(`tooling/src/main/java/lib/minecraft/renderer/tooling/animation/PoseStates.java:153-158`).

The units are no obstacle. A pose reads and writes a scale channel as vanilla's field - the bone's
rest over the scale above its part, one on the ghast's body at either age
(`src/main/java/lib/minecraft/renderer/bake/pose/PosePlayer.java:558`), and a written value over
that rest as the ratio the chain carries (`:684-697`) - so a literal 0.9375 on the body draws the
adult at 3.75, as vanilla does under the root that `HappyGhastModel#createBodyLayer` scales by 4
(offsets 469-474, javap, 26.1). What is missing is a row that carries it.

No stored row draws a harnessed ghast posed. The `~equip=body` row of `sweep.entity`
(`src/test/resources/lib/minecraft/renderer/parity/sweeps/entity.json:1320`) renders at the default
`bind`, which `PosePlayer.posed` hands back unposed (`PosePlayer.java:114`, `:140`), and the idle
and walk sweeps hold the unharnessed adult and baby.
`PosePlayerStyleTest.everyShippedWrittenScaleIsItsRest`
(`src/test/java/lib/minecraft/renderer/bake/pose/PosePlayerStyleTest.java:191-226`) asserts that
every shipped written scale equals the value its bone's own field rests at, bit for bit, on every
form under every listed style; a 0.9375 written on the ghast's body reddens it and names the row
that then owes a capture.

It settles when the table carries the harnessed arm, so a harnessed happy ghast posed under any
style but `bind` draws its body, inner body and tentacles at 0.9375 of their rest, and the corpus
pin admits that row.

## The small stand's shared pose places attack-state arms at the adult's offsets

The `ArmorStandModel` row's `attackTime=1` state
(`src/main/resources/lib/minecraft/renderer/entity_poses.json:61971`) places `left_arm` at `x` 5 and
`right_arm` at `x` -5, each at `z` 0. Vanilla's small stand is a baby: `ArmorStand.isBaby` answers
`isSmall()` (offsets 0-4), `LivingEntity.getAgeScale` answers 0.5 for a baby (offsets 0-14), and
`HumanoidModel.setupAttackAnimation` multiplies each arm's attack `x` and `z` by `5.0f` and the
render state's `ageScale` (offsets 60-155), which `ArmorStandArmorModel.setupAnim` reaches through
`HumanoidModel.setupAnim` (javap, 26.1). So the state's 5 and -5 are the full-size stand's, and the
small model's arms, baked at 2.5 and -2.5, stay there under an attack. Both sizes pose through the
one row: the small size option names `ArmorStandModel`
(`src/main/resources/lib/minecraft/renderer/entity_models.json:172-175`), and
`EntityModelLoaderTest` pins the small form holding the row's pose instance
(`src/test/java/lib/minecraft/renderer/content/index/EntityModelLoaderTest.java:604-608`).

The small mesh carries both arms, at `(-2.5, 13, 0)` and `(2.5, 13, 0)`, and the install weaves the
small form apart on the scales its parts rest at
(`src/main/java/lib/minecraft/renderer/author/compile/FormWalker.java:284`, `:571-578`), so a style
spelled from the state compiles against those arms. Spelled as the showcase spells a silhouette,
every channel spliced whole through the raw hatch
(`src/visual/java/lib/minecraft/renderer/driver/PoseShowcaseDriver.java:222-233`), it lands the
constants as written, and the small stand's arms draw at `x` 5 and -5 where vanilla's stay at 2.5
and -2.5.

Nothing selects the state. The renderer consults no silhouette; the showcase spells two, the wolf's
`isSitting=true` and the horse's `standAnimation=1` (`PoseShowcaseDriver.java:194`, `:202`); and the
seat derivation reads a state as a witness only where a leader turns in it
(`src/main/java/lib/minecraft/renderer/author/mesh/Seats.java:249-254`, `:280`), which the attack
state, moving two pivots and turning nothing, never does.

It settles when the state a small stand is posed from places its arms at the small model's age
scale - a silhouette of the small form's own, or the attack offsets carried as a product with
`ageScale` - so a statue spelled from `attackTime=1` puts the small stand's arms where vanilla's
small model holds them.

## The geometry table hangs the parched's hat at the root where vanilla hangs it from the head

`SkeletonModel#createSingleModelDualBodyLayer` adds `head` to the root (offsets 100-162) and chains
`addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO)` onto the PartDefinition that
call returns (offsets 165-173), so vanilla's parched carries its hat under the head (javap, 26.1).
The table ships the hat with no parent
(`src/main/resources/lib/minecraft/renderer/entity_geometry.json:23645`), the one of the 31
hat-bearing geometries whose hat does not hang from `head`. `GeometryParser` takes a part's parent
from the local slot it loads
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:1338-1340`) or
from a chained `getChild` (`:1391-1405`), and the `CubeListBuilder.create` case snapshots it
(`:1910-1914`); a part chained onto the PartDefinition the previous `addOrReplaceChild` returned
passes through neither, so it lands at the root.

The hat has no cubes, so nothing draws differently. It does reach the compile's hat decision:
`PoseCompiler.hatRidesHead` answers false on this mesh
(`src/main/java/lib/minecraft/renderer/author/compile/PoseCompiler.java:398-411`), so a humanoid
head write on `minecraft:parched` weaves onto its hat and its head clip channels copy there
(`:1133-1134`, `:1377-1380`), onto a bone that draws nothing.

It settles when the parser parents a part chained onto the previous `addOrReplaceChild`'s return and
the parched's hat ships under `head`, the tooling-flow-gate accounting for every key the fix moves.

## The block-overlay anchor composes every ancestor where vanilla applies the part's own step

`MushroomCowMushroomLayer`, `SnowGolemHeadLayer` and `IronGolemFlowerLayer` each take the attached
part off the model - `getHead()`, or `getFlowerHoldingArm()` - and call that part's
`translateAndRotate` on the stack the layer was handed (offsets 243-247, 40-44 and 23-31), so the
block takes the part's own step and none of its ancestors' (javap, 26.1).
`EntityGeometryKit.resolveBoneAnchorMatrix`
(`src/main/java/lib/minecraft/renderer/bake/mesh/EntityGeometryKit.java:719-724`) answers
`BoneKit.buildChainTransform` over the posed mesh, every ancestor's step composed down to the
attached bone, and `EntityRenderer` places the block there
(`src/main/java/lib/minecraft/renderer/EntityRenderer.java:941-960`).
`EntityGeometryKit.scalesNonUniformly`, which decides whether the block's normals turn by that
placement's inverse-transpose, walks the same ancestors.

The two agree today because every attached part - the mooshroom's and the snow golem's `head`, the
iron golem's `right_arm` - is a top-level bone of its mesh. What stands above such a part is a
container step, which sits above every top-level bone alike, and the iron golem's pose seats the
only one among the three rows: its turn is `IronGolemRenderer.setupRotations`' walking sway (javap,
26.1), a step vanilla's stack holds under the layer as well. A parent between the root and an
attached part would reach our block and not vanilla's - a style turning or scaling it moves the
block, a written scale riding the chain to every descendant, and a clip's non-uniform one turning
its normals too - and no attached part in the table has one.

It settles when the anchor composes the attached part's own step over the steps that stand above
every top-level bone, rather than the part's whole ancestor chain, as vanilla's layers do, and the
non-uniform test reads the steps the anchor composes.
