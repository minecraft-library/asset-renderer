# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## Four held-pose style rows are measured constant, and their names say they should move

The style emitter measures frog `jump`, bat `rest` and both axolotl `play_dead` selections
(baby clip, adult factor) as constant over the whole period - every clip channel one distinct
keyframe value - so they ship as held poses (`sources: []`, a distinct but motionless render).
Their vanilla names say they animate. Either the vanilla clips genuinely hold a pose and the
selection's motion lives somewhere the walk does not carry, or the offline measurement (clip
constancy, the time-axis rule, or the extraction of those clips) is wrong. Investigate against
the client; if they should move, the fix is in the measurement or the keyframe walk, and the
rows re-emit with real sources.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows, and the one
coupling to the entity bag is `PoseStyle.appliesTo(EntityOptions)` - a player catalog either ships
age-free rows, which never call it, or that member grows a shape the player bag can answer.
Everything else on the axis - `resolve`, `frameAt`, the drivers, `PoseKit.frames` - is already
bag-agnostic.

## Whether an adult axolotl can play dead decides the (id, age) pair mechanism

The catalog allows two rows sharing one id with disjoint `age` members - coined solely for the
axolotl's `play_dead` (baby clip row vs adult factor row), with `appliesTo` disambiguating at
resolve. If vanilla adult axolotls never play dead, the adult factor row is a fiction: drop it,
and the per-(id, age) uniqueness relaxation collapses back to plain per-entity id uniqueness.
Check the vanilla behaviour before building anything else on the pair mechanism.

## Pose packages - the vocabulary sits under `asset`, the authoring stack is one flat package

`asset` is for what the pipeline loads and what an asset type stores. `asset.pose` holds that -
`EntityPose`, `PoseClip`, `PoseStyle`, `StyleCatalog`, `StyleDriver` are loaded from the pose table
and stored on `Entity` - but it also holds the pose language those records are built from:
`PoseExpr`, `PosePredicate`, `PoseOperator`, `PoseChannel`, `MotionSource`. None of the five knows
a file or an entity; the evaluator runs them and the compiler splices them. They sit under `asset`
because nothing else existed to hold pose information when they were written.

`author.pose` is the other half of the same problem: one package holding the human verb surface,
the intermediate form, the compiler and its interner, the seat derivation, the validator, the
registrar, the player rig and the table emitter - six kinds of thing behind one name.

### Proposed structure

```
lib.minecraft.renderer.pose            the pose language - no I/O, no entity
    PoseExpr, PosePredicate, PoseOperator, PoseChannel, MotionSource

lib.minecraft.renderer.asset.pose      what the pipeline loads and an Entity stores
    EntityPose (Silhouette, Clip), PoseClip, PoseStyle, StyleCatalog, StyleDriver, Drawn

lib.minecraft.renderer.pose.author     the human surface: verbs, selectors, IR, portable value
    Poses, HumanoidPose, QuadrupedPose, CustomPose, PoseBuilder, LimbStance, Timeline,
    Side, Corner, Turn, Ease, Preset, PoseScript, BuiltStyle

lib.minecraft.renderer.pose.compile    lowering a built style onto a row
    PoseCompiler, GraphInterner, Seats, Kinematics, StyleDiagnostics

lib.minecraft.renderer.pose.audit      measuring a built style against a row
    PoseValidator, PoseAudit

lib.minecraft.renderer.pose.install    binding to entities and the renderer
    StyleRegistrar, PlayerRig, SkinContext, PoseEmitter
```

Dependencies point one way, top to bottom: `install` to `audit` to `compile` to `author` to
`engine.kit` and `asset.pose` to `renderer.pose`. Nothing under `asset`, `engine`, `pipeline` or
`option` imports the four authoring packages - the rule that holds today, unchanged.

Why each line falls where it does:

- `renderer.pose` mirrors `renderer.face`: a top-level vocabulary leaf that `asset` depends on
  downward, with a parity claim of its own. `asset.pose` keeps only records the loader constructs
  from the table.
- `pose.author` is everything an author touches and nothing they do not. It depends on the
  language alone, so a built style is entity-free by package shape rather than by convention.
- `pose.compile` is where units, rebasing and inference live. `Seats` reads silhouettes and
  produces a compile-time conclusion, so it belongs here and not in `asset`; the diagnostics sink
  is the compiler's recorded channel and rides along.
- `pose.audit` is analysis rather than lowering, two types today and the home of any per-pair
  reading the validator grows.
- `pose.install` owns woven rows: the registrar, the rig that synthesizes the player row, its
  skin context, and the emitter, because emitting is what one does with a row the registrar
  produced. The emitter and the pose table reader are inverse functions of one grammar; a shared
  format codec would be the emitter's long-term home, and until it exists `install` is the honest
  place.

No package holds one class.

### Migration notes

- The blindness glob over `author/**` becomes one glob per authoring package, and `renderer.pose`
  needs a claim of its own, modelled on `face-vocabulary`. Run `python parity/scripts/parity
  triggers` after the move; the per-type `@Parity(subject = ENTITY)` declarations travel with
  their files.
- Grep the parity store before renaming any test class: a class the store homes a row at is a
  promote, never a rename (`parity/CLAUDE.md`). The authoring tests are unlikely to be
  store-homed, but the grep comes first.
- Every `RENDERER-RULES.md` heading a reach rule cites is a rename hazard of the same kind; the
  move touches packages, not headings, but check the rules that cite the pose sections.
- `Drawn` pairs a mesh with the pose that moves it at draw time, which reads as engine state
  rather than loaded data. It stays in `asset.pose` above because moving it is a separate
  decision.
- Import order and javadoc links follow the move mechanically (`toolsmith java reorder`,
  `toolsmith java docs`); `package-info` files keep inline fully-qualified link targets.
- The move is byte-neutral for every render: no type changes, only its package. The gate is the
  fast suite plus `paritySelfTest`; no capture is owed.
