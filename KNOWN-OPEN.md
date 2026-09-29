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
`src/test/java/lib/minecraft/renderer/content/index/EntityModelLoaderTest.java:599-602` gives the
small mesh's missing arms as the reason its form keeps the row's pose instance, and owes a rewrite.

It settles when a small stand draws both arms under `arms` and no plate under `base_plate`, a stand
named at `Size.LARGE` keeps both flips, and a test selects each toggle at each size.

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
(`src/test/java/lib/minecraft/renderer/author/audit/PoseAuditorTest.java:83-96`) audits that beg
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

## A bone holds its rotation in float degrees, and a radian no float degree reaches is lost

`EntityMesh.Bone`'s rotation
(`src/main/java/lib/minecraft/renderer/asset/mesh/EntityMesh.java:202-212`) is an `EulerRotation`,
which carries degrees
(`src/main/java/lib/minecraft/renderer/engine/geometry/EulerRotation.java:8-10`) and answers
`(float) Math.toRadians(value)` (`:78-80`) to `BoneKit`
(`src/main/java/lib/minecraft/renderer/bake/mesh/BoneKit.java:248-250`). Vanilla's
`ModelPart` holds `xRot`, `yRot` and `zRot` as the float radians a `PartPose` literal or a
`setupAnim` write puts there, and converts nothing. A radian enters a degree float in two places.
At rest, `GeometryParser` writes `(float) Math.toDegrees(r)` for every `PartPose.rotation` and
`offsetAndRotation` it walks
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:2186-2190`,
`:2203-2207`), and `src/main/resources/lib/minecraft/renderer/entity_geometry.json` ships those
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
