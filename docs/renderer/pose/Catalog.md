# Posing an entity at a tick

Load when touching `EntityOptions.style`, `StyleCatalog`, `PoseStyle`'s rows, `PosePlayer` writing a
resolved row onto a mesh, or a pose key. Posing is five files - [Catalog](Catalog.md),
[Container](Container.md), [Drivers](Drivers.md), [Strips](Strips.md) and [Authoring](Authoring.md).
Indexed from [`CLAUDE.md`](../../../CLAUDE.md).

`EntityOptions.style` names one row of the entity's shipped style catalog - which mechanisms move
the subject and which appearance toggles the selection entails - and `bake/pose/PosePlayer` is the one
place a resolved row is written onto a mesh. The knob is a free string on the one bag that names it,
the id set being open per entity, and the four universal ids are typed on `PoseStyle`. How
`entity_poses.json` and the catalog are derived is [tooling/PoseTable.md](../../tooling/PoseTable.md)'s and
[tooling/Flows.md](../../tooling/Flows.md)'s; this is what the runtime
does with them.

**Every catalog resolves `bind`, `idle`, `stride` and `animated`, and what separates two rows is the
drivers in force.** A pose is a function of what the caller says about the subject, so naming a row
is naming the figures that stop resting - it is not a second mechanism, and every row goes through
`PosePlayer` the same way. `idle` ramps elapsed age alone. `stride` adds the two a stride is carried
on, and they are one schedule rather than two inputs: vanilla accumulates the phase BY the speed once
a tick rather than deriving it from the clock, so the phase is the tick times the speed, and a caller
setting one without the other has described no gait. The speed is the subject's own ambling wander
pace and it is the swing's amplitude as well as the phase's rate; a baby's walk clock runs three
times as fast as its speed, which vanilla applies as a position scale and the table ships as the baby
row's ramp extent, so no renderer code holds the three. `animated` resolves to the first shipped row
the subject's in-force inventory moves, so a caller asking for movement names no mechanism - and a
subject nothing moves answers `bind` and stays a single frame. The universal ids are a coinage
rather than vanilla's - vanilla carries `walkAnimationPos` and `walkAnimationSpeed` and no `stride`
constant - and an entity's own ids ship in its catalog beside them.

- **A walk-gated clip carries the branch it sits inside, where a state-gated one needs none.** A
  state's gate names the clip that is running, so the exclusion is the selection; a walk's gate is
  the stride, which answers for every site at once, so a model choosing between two walk clips
  chooses with an ordinary `if`. The site records that test and the fold settles it against the
  frame its subjects rest in, dropping what it proves unreachable and refusing what it cannot
  settle.
- **A row's `states` member is evidence beside the pose, and nothing at render reads it.** A body
  branches on questions of its render state a resting subject answers one way, and the shipped
  channels hold the arm the resting subject takes; each other arm - a wolf sitting, a parrot's
  pose, an equine's completed stand - is folded once more at rest with that one answer flipped,
  and the bones it places away from the resting row ship under `states` keyed `member=value`,
  spelled as the row's bones are over a `shared` table of their own - and a row two ages reach,
  the armour stand's that both sizes pose through, also leaves out a position each age places at
  that size's own rest, so the stand's finished attack names no arm `x` though vanilla assigns one,
  and the arm stays where each size's mesh rests it. `EntityPosesTable` reads the states into
  `EntityPose.states` after the row's own table is read whole, so no reference crosses
  between a row and a silhouette; `PosePlayer` and `PoseEvaluator` read `container`, `bones` and
  `clips` and never the member, so a table carrying it poses every shipped style to the bits of
  one that does not. What reads a silhouette is pose authoring, beside the mesh, for which parts
  vanilla moves together - a sitting wolf's tail and hind legs placed by hand where its lowered
  body carries them, which the shipped geometry, where they are root-level siblings, cannot say.

**`bind` is the default and hands back the very instance it was given.** Identity, not equality: an
equal copy is still a copy, and every float in it is one the authored path never computed. The same
answer serves a subject whose model poses nothing, one whose pose could not be read, and one writing
only channels a mesh does not carry - so the authored path allocates nothing and rounds nothing, and
that is the whole of why the runtime landed at zero movers. Anything that makes the default return a
rebuilt mesh has broken the contract whether or not the bytes move that day.

**A subject is more than one posed mesh, and the class that poses one is not the class that baked
it.** A geometry coordinate is headed with the class that baked the mesh, and a model reusing its
parent's layer bakes none - a zombie's mesh is `HumanoidModel#createMesh` while the renderer hands it
a `ZombieModel`. Keying the pose off the coordinate poses it as a plain humanoid and loses the
arms-out stance every zombie stands in. `bones.pose` names the poser, written only where the two
disagree.

- **`bones.pose` names a pose KEY, which is a class name until one class poses more than one way.**
  A body reached at two resting frames is split into a row each and each body names the one it takes,
  spelled `Class@member=CONSTANT` over the members the frames disagree on - the four illagers are the
  corpus's one split, on `armPose`. `EntityIndexBuilder.poseOf` splits a coordinate at its first `#`
  and keys on what is left, so a suffix arrives verbatim and the reader needs nothing for it. **A key
  the table does not carry is SILENT** - `poses.getOrDefault(key, EntityPose.NONE)` and `NONE`'s
  refusal is empty, so `isReadable()` answers true and the mesh draws unposed and unstripped - so the
  emitter refuses a declared poser with no row rather than leaving it to be noticed in a render.
- **Every overlay pass poses its own mesh with its own class**, so `Entity.OverlayLayer` carries a
  pose and `PosePlayer.posed` poses the subject's body and every pass together. Posing the body alone
  leaves a sheep's wool where the sheep no longer is. A pass drawing the body's own mesh takes the
  body's pose rather than its coordinate's, or the two part company on a subject that moves.
- The insertion is one line at the top of `EntityRenderer.renderEntity`'s `buildAtTick` lambda - the
  posed *definition* goes into `FeatureContext`, so a feature reading `ctx.definition()` gets posed
  passes without a gate of its own, and the collar redraws the posed body.
- The rebuild preserves the mesh's **own bone order**, which is vanilla's bake order and the tied-depth
  priority. A `LinkedHashMap`, never `Map.copyOf`, whose iteration is salted per JVM launch.
- Composition is channel-wise on the Euler triplet `BoneKit.applyBoneRotation` feeds to
  `Quaternionf.rotationZYX`. **Do not pre-compose a rotation as a matrix** - it reaches that call
  through different arithmetic and parts from the authored pose at a delta of zero.
- **A channel written back to what the mesh already held keeps the mesh's own degrees.** The table is
  radians throughout and a bone stores degrees, and `toDegrees(toRadians(d))` does not return `d` for
  about one float in fifty thousand - `31f` is one - so converting unconditionally walks every bone a
  bind-resolving pose touches by an ulp a render.
- Three written scale axes fold onto one uniform scale, which is vanilla's field and rides the
  chain as its ratio to the value that field rests at - the bone's rest over the scale above its
  part, `EntityMesh.scaleAbove`, which is also what a read of the channel answers. That ratio is
  the pose scale `BoneKit` composes after the rotation, so it reaches every descendant's cubes and
  pivot as vanilla's stack carries a part's field, and the rest factor itself is never rewritten. A
  clip's scale adds to the same field axis by axis, so its ratio is the field's rest plus the
  displacement over that rest. A written divergence refuses, because no one value is all three axes
  and no shipped pose writes them apart. `HappyGhastModel` writes one expression to all three, a
  read of the body's own field, which answers one on either age's mesh and is handed back unscaled
  as a write equal to its rest. A filled body slot swaps in the row folded with the slot filled,
  whose three axes are the literal `0.9375`, so a harnessed adult draws its body at 3.75 inside a
  harness drawn at 4 under every style but `bind`.
- **A face below a non-uniform pose scale turns its normal by the chain's inverse-transpose**, which
  is vanilla's normal matrix once `PoseStack.Pose.scale` has met axes of different magnitude. The
  chain itself leans a face turned off the scale's axes toward the stretch, where vanilla's leans it
  away - the baby nautilus's mouths in its swim. `EntityGeometryKit.chainNormal` takes the turn for
  an entity face and a carried block alike - for a face on what
  `EntityGeometryKit.scalesNonUniformly` answers for the bone's whole chain, for a carried block on
  what `EntityGeometryKit.anchorScalesNonUniformly` answers for the steps its anchor composes. Every
  other face turns by the chain itself: under uniform scales the two are one direction rounded apart,
  and the fork keeps the inverse-transpose's rounding off every face no non-uniform scale reaches.

## Decisions that stay closed

- **Do not derive a style row's `age` from which fields a form's pose reads, and do not remove the
  member from the rows that carry it.** Every entity whose baby walks at a pace of its own - 35 of
  them - ships its `stride` split by age, and the axolotl splits its `idle`, `stride` and `play_dead`
  by what each age's model reads; but the member carries a second thing that has nothing to do with
  either. `PoseBuilder.age` / `allAges` scope a hand-authored pose, defaulting every custom style to
  the adult alone because an install weaves a style whose age admits a baby onto the baby form too,
  and a baby mesh can lack a bone its adult declares, which refuses a strict install - and a style
  that keeps the stride and spans both ages installs as an adult row and a baby row, each walking at
  its own age's pace, which makes authored rows the column's second producer. So
  `AppearanceOptions.applies` and the filters in `adultRow`, `inForce` and `carries` stay
  load-bearing whatever the generated rows do, and what is the axolotl's alone is the two roster
  strings that cross-check its split.
  Deriving it is also not available on its own terms: the two sides of a paced stride drive the same
  fields - the walk pair, at two paces - and every form of the subject reads them, so no rule over
  which fields a pose reads tells the baby's row from its adult's.

  **And the emitted column is already derived, which is the whole of why there is nothing to move.**
  `StyleFlow` measures every row of a subject carrying a baby form TWICE, once per age side and each
  against that side's own meshes, and emits an age-split pair only where the two sides measure apart -
  in what they source, in the drives they carry, or in whether they render apart from their base at
  all. The roster's declared age is a cross-check against what the entity's forms say rather than the
  column's source. So the column records a measurement over meshes, clips, pose programs, paces and
  evaluated channel series, and a loader re-deriving it from the shipped drives would be answering
  the same question from strictly less.
- **Do not drop the axolotl's adult `play_dead` row, and do not widen the baby one over both ages.**
  Vanilla gates playing dead on no age at all - `PlayDead.checkExtraStartConditions` is
  `return axolotl.isInWater();`, the arming site sets `PLAY_DEAD_TICKS` without reading an age, and
  `AxolotlRenderer.extractRenderState` fills `playingDeadFactor` and copies `playDeadAnimationState`
  unconditionally. What forks by age is the expression, not the behaviour: `AdultAxolotlModel` reads
  the factor and `BabyAxolotlModel` the clip, with no model-side overlap, so the adult row is the
  only play-dead appearance an adult has and the baby row drives a field the adult mesh never reads.
  The two sharing one id is deliberate and is one entry of `StyleRoster`'s id-override table, not a
  vanilla fact - the derivation would otherwise spell the adult row `playing_dead`.
