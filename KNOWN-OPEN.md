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
(`tooling/src/main/java/lib/minecraft/renderer/tooling/geometry/GeometryParser.java:2200-2204`,
`:2217-2221`), and `src/main/resources/lib/minecraft/renderer/entity_geometry.json` ships those
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
(`src/visual/java/lib/minecraft/renderer/driver/PoseShowcaseDriver.java:239-255`), it lands the
constants as written, and the small stand's arms draw at `x` 5 and -5 where vanilla's stay at 2.5
and -2.5.

Nothing selects the state. The renderer consults no silhouette; the showcase spells two, the wolf's
`isSitting=true` and the horse's `standAnimation=1` (`PoseShowcaseDriver.java:206`, `:215`); and the
seat derivation reads a state as a witness only where a leader turns in it
(`src/main/java/lib/minecraft/renderer/author/mesh/Seats.java:249-254`, `:280`), which the attack
state, moving two pivots and turning nothing, never does.

It settles when the state a small stand is posed from places its arms at the small model's age
scale - a silhouette of the small form's own, or the attack offsets carried as a product with
`ageScale` - so a statue spelled from `attackTime=1` puts the small stand's arms where vanilla's
small model holds them.

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
