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
