# What a resting subject answers

Load when touching `rest_defaults`, `input_defaults` or `question_defaults`,
`EntityRestStateResolver`, `EntityEquipmentResolver`, `EntityBlockOverlayResolver`, how a flag
folds, `EntityAgeAxisResolver`'s baby pins and `age_scale`, or `ageScale`. The state-silhouette
spelling two ages share, and its proof against the mesh, is [PoseTable.md](PoseTable.md); why a flag
is not a channel is [Vocabulary.md](Vocabulary.md). Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

Three resolutions of what a figure rests at are made here - `rest_defaults`, `input_defaults` and
`question_defaults`, by the flow's own names - and the fold consumes all three whole; **none is
emitted and none is read at render**. Every claim below is about generation rather than about a
shipped byte.

**A figure a pose names rests at what its own render state builds it at, and an enum member is the
same question with no zero to fall back on.** Answering false to every constant is a state no enum is
in, so a switch lands on whichever arm it ends at rather than the arm the subject stands in - a wrong
pose rather than a wrong number. `rest_defaults` carries the constant each member rests holding and
`input_defaults` the numeric half, both read off the render state's own constructor; a per-subject
`rest` still overrides, which is what keeps an evoker's arms crossed while a pillager's hang.
**`rest_defaults` is keyed per MODEL, because a bare field name does not span one type** - an armour
stand's `rightArmPose` is a `Rotations` beside a humanoid's `ArmPose`, and a parrot's `pose` is not
the `Pose` every other subject carries. The model's own `setupAnim` names the state its members are
read off, which is the only thing that tells those apart; a flat table reads all four as conflicts
and drops them. What makes a push a constant OF a member is that its owner is the field's declared
type, true of an enum constant and of `ItemStack.EMPTY` and false of a static the state merely holds.
A member named through a call is answered by the fields it forwards to, and refuses where two
branches disagree.

**`rest` is the subject's own fact and not its model's**, because one `IllagerModel` serves every
illager and only the pillager hangs its arms. `EntityRestStateResolver` reads the accessor
`extractRenderState` fills the field from and takes its last unguarded return, resolved against the
**subject's** entity class - a renderer shared by several calls the accessor on the type they have in
common, so reading the owner off the instruction gives every illager `AbstractIllager`'s
unconditional `CROSSED`. Answering no constant at all drew three illagers with the wrong pair of arms.
A field filled by a static helper handed the entity alone answers that helper's fall-through constant,
read on the helper's own owner since a static does not dispatch - which rests the parrot `STANDING`
off `ParrotModel.getPose`.

**A question asked of a reference the render state holds rests at what that reference was built
with, and `question_defaults` carries it**, keyed per MODEL for the reason `rest_defaults` is and
then by `receiver.question`. Where a figure the state builds at zero and one nobody models are the
same number, a reference is a whole value and every component of it is an answer a subject stands at
- an armour stand's legs splay a degree each way before anything happens to it, and answering zero
stands it with its legs together.

- Answerable because the shape is closed: the state's constructor assigns the field a static of the
  field's own declared type, that type is a record of floats, and the static's own initialiser builds
  it from literals. So the question names a record component, the component names a constructor
  argument BY POSITION, and the argument is a number. Losing the position splays both legs the same
  way, which costs more than answering nothing - measured at 7.68 against the 4.56 of a stand with
  its legs together, and 0.05 with the positions right.
- Anything outside that shape answers nothing rather than a guess, and a zero row is omitted on the
  same terms `input_defaults` omits one. That cannot shadow `isEmpty`, which rests at ONE: an
  emptiness is asked of a stack rather than read off a record of floats, so no row can ever name it.
- **A filled equipment slot is the one place an emptiness is answered zero.** `EntityEquipmentResolver`
  writes the render-state field a layer's item getter reads as the row's `item_field`, a
  generation-only member `RestStrip` takes off with `rest`. Where the wearer's body asks `isEmpty` of
  that field, `PoseFlow` folds the body once more exactly as it folds the body's own row, with that
  one question answered zero, and emits the result beside the row keyed
  `<Model>@<field>.isEmpty=false`, named by `axes.equipment.options.<slot>.pose`. Only the happy ghast's
  body reshapes on it in 26.1, squeezing to `0.9375` inside its harness; the zombie nautilus's coral
  asks the same question of a flag and gets no row.
- **A selected carried block folds its wearer's body the same way, through the field its draw is
  gated on.** `EntityBlockOverlayResolver` writes that field as the selectable block row's
  `carried_field`, generation-only and taken off by `RestStrip`, and `PoseFlow.foldCarriers` folds
  the body once more beside its row with it answered as selected, naming the result by
  `axes.carried.options.block.pose`: a field the body asks `isEmpty` of is answered zero, keyed
  `<Model>@<field>.isEmpty=false` - the enderman's raised arms - and one `StyleRoster.COUNTDOWNS`
  names is read as its start less the elapsed age, the `int` the field is, keyed
  `<Model>@<field>=countdown` - the iron golem's offered flower, swaying until the count ends. The
  style flow measures that body as a site gated on `carried`, so a variant moving on the age clock
  ships a source only a selected block admits.
- **A bone drawn only while a stack is empty is a toggle, and the filled slot selects it.**
  `ZombieNautilusCoralModel` writes `corals.visible = state.bodyArmorItem.isEmpty()`, which
  `EntityBoneResolver.decodeEmptinessGate` reads as a gate over the stack field, naming the toggle
  `body_armor_item` the way any flag names one. The fold answers the emptiness one, so the corals rest
  drawn and the toggle hides them. `EntityEquipmentResolver.nameWearerToggles` then writes
  `wearer_toggle` on the equipment row whose `item_field` is that same field, and the renderer adds it
  to the selection whenever the slot is filled. The corals are the class of the warm COAT, not of the
  family, so a coat drawing a mesh of its own carries the `toggles` its baking class gates as a size
  option does, and `EntityMeshMarking` joins them onto that coat's site alone.

**Every flag folds to a literal here, which is why nothing at render reads a flag channel.** Which
bones a subject rests without is written by `EntityMeshMarking` onto the mesh it rests in - a bone
nothing can draw is dropped, a bone a selection can draw rests `visible: false` naming what flips it
- and nothing strips a mesh at load. A flag the fold cannot settle to a literal - one reading a clock
- and a resting `skip_draw` both refuse the flow, which is the coverage the per-frame flag path used
to hold, moved to where a version bump surfaces it loudly.

**A baby option carries the toggles its own class gates, as a size option does, less a flag its
renderer pins on the baby arm of an age test.** A baby's mesh is gated by the same `setupAnim` as an
adult's, so a toggle left off it is a baby goat whose horns no selection can hide. But the renderer
decides what a flag holds before the model reads it: `LlamaRenderer.extractRenderState` stores
`false` into `hasChest` on the `isBaby()` arm, so vanilla never draws a chest on a baby llama, and
a `chest` toggle copied onto the baby would let a selection draw one.
`EntityAgeAxisResolver.pinnedOnBaby` reads those literal stores off the renderer chain and
`EntityBoneResolver` drops each gate by its FLAG before naming a toggle, a goat's `horn` being a
bone stem rather than a flag. A pin storing `true` refuses the flow instead: the fold rests a flag at
what the render state builds it at, so a flag every baby holds true would rest hidden and the
marking would drop a bone every baby draws.

**A figure the renderer's extraction writes per age rests at the age its site renders at.** That
figure is `ageScale`: `LivingEntityRenderState` builds it at one, and
`LivingEntityRenderer.extractRenderState` overwrites it with the entity's `getAgeScale`, which
`LivingEntity` answers `isBaby() ? 0.5f : 1.0f` and a goat, a camel, a turtle and a few others
override with another literal of the same shape. `EntityAgeAxisResolver.ageScaleOnBaby` reads the
baby arm's literal off the nearest `getAgeScale` up the subject's entity class and writes it onto the
baby option as `age_scale`, a generation-only member `RestStrip` takes off with `rest`. **It has a
second writer, because an armour stand is a baby exactly while it is small**: its `isBaby` answers
`isSmall`, and its renderer copies that flag and swaps the small model in on it.
`EntityAgeAxisResolver.forwardedAge` reads those facts, and the layer the swapped model is baked
from, off the jar as `AsmWalker` chains, and `EntitySizeAxisResolver` writes the same answer onto
the size option baked from the swapped-in layer, matched by its `ModelLayers` field rather than by
the option's name. The pose flow files every site by the age it renders at - an option stating an
`age_scale` at it, its mesh and its overlays alike; an overlay's own `baby` mesh at the baby's age;
everything else at one - and a model that reads `ageScale` and is reached at one age alone folds
against a copy of `input_defaults` carrying it, its state silhouettes included. **A model reading
it that two ages reach is one row read at both**: it is folded at each, the folds - its wearer
row's too - have to agree, and each state silhouette is written once in the spelling every site
holds. Where the folds part, where a state has no such spelling, or where the row is reached at
two frames as well, the flow refuses rather than keeping the constructed age in silence. A baby
whose `getAgeScale` has another shape carries no `age_scale`, and a model reading the figure at
that baby's site refuses the flow rather than folding at the adult's age in silence.

- The worn armour's `alternate` is no baby site, though only a baby wears it: a worn shell evaluates
  no pose row, and filing it at the baby's age would put `HumanoidModel`, which many adult sites also
  reach, at two ages for no render. `ArmorStandArmorModel`'s own row holds the large stand's attack
  offsets for the same reason: the small stand's armour alternate draws that class, whose
  `attackTime=1` places the arms at `x` 5 and -5 where the small shell rests them at 2.5 and -2.5,
  and nothing evaluates that row.
- Twelve baby rows fold off the constructed age in 26.1, each named by one line in the flow's log: the
  two foals', whose stride moves the tail by the age, and ten whose state silhouettes place offsets
  by it. `ArmorStandModel` is the one row written once for two ages, named by one line as well.
- Two places fold at the constructed age whatever age the sites render at, and 26.1 reaches
  neither. `PoseFlow.foldTransforms` folds every renderer's `setupRotations` there, and no
  `setupRotations` reads `ageScale`. And a row reading the figure only in a clip site's condition or
  arguments folds there too, because whether a row reads it is asked of
  `InputDefaultResolver.namedBy`, which walks the container, the channels and the flags alone; none
  of the model methods reading `ageScale` plays a keyframe clip.
