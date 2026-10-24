# Figures, clocks and selections

Load when touching what a frame answers - `PoseStyle.frameAt`, `PoseEvaluator.AT_REST`,
`StyleDriver`, a catalog driver, a selection group, the two clocks - or how a flag is settled.
Posing is five files - [Catalog](Catalog.md), [Container](Container.md), [Drivers](Drivers.md),
[Strips](Strips.md) and [Authoring](Authoring.md). Indexed from [`CLAUDE.md`](../../../CLAUDE.md).

**A shipped pose names no figure the frame does not answer, and the frame answers the resolved
row's driver map.** `PoseStyle.frameAt` is the one place a figure is answered, and outside that map
everything rests: elapsed age is the tick, and a field nobody drives reads zero. Everything a subject standing
still says about itself is resolved where the table is written - which constant an enum member holds,
what a question of a reference the state holds rests at, what a figure its own render state builds it
at, and the age a baby's own entity answers in place of the one its render state builds - so there
is nothing a caller can leave out and be wrong about. That is what
`PoseEvaluator.AT_REST` is, and it is why the evaluator's arms are a literal, a figure, a bone read,
an operation and a choice, and a choice turns on a numeric comparison alone.

**The roster is elapsed age, the stride pair, the clocks a plain IDLE or WALK advances, and the
selections a subject rests in.** Each ships as a catalog driver: elapsed age is a `ramp`; the stride
pair a `hold` and a `ramp` at the subject's own pace; a clock - a field vanilla's own tick advances
while the subject stands or walks, such as a guardian's tail and spikes, a dragon's wings, a squid's
tentacles, a shulker's lid, an axolotl's swim blend and a warden's heart - a `hold`, `ramp`, `stroke`
or `trapezoid` per gait, the stride row spelling its half where it differs from the idle row's; and a
one-hot selection a `hold` on the row that selects it, whose field answers one while every other
member's rests at zero. A `sweep` or `cycle` moves a field from the rest it names to its extent
across one figure period, which authored styles use and no shipped row does. They are wave shapes
rather than mechanisms, and what they share is that the resolved row's driver map answers each by
render-state field name. A motion vanilla starts at random or on an event - a slime's hop, a horse's
tail flick - is no clock and is not drawn.

- **A `sweep` or `cycle` runs from rest, so tick zero is free for them; a clock need not.** Frame 0
  of an authored strip driving only those answers what the mesh answered before the figure was
  driven. A clock answers vanilla's settled state for the gait from its first tick - a held level, or
  a beat already lit - so frame 0 of a posed strip is not the bind frame for a subject that carries
  one. **A selection has no such property either**, and neither needs one: `bind` hands back the mesh
  it was given and every still sweep renders there, so the posed sweeps are the only gates a clock or
  a selection reaches.
- **A boolean render-state field is a figure of this kind, not a flag.** A flag is a bone's
  visibility, which folds to a literal at generation; a boolean the walk keeps symbolic arrives as a
  number wherever the body reads it, and a body that branches on one leaves a select comparing that
  number against zero. So a dolphin's `isMoving` is one arm of a two-member selection, and adding it
  to the generator's driven set is the whole of what keeping it symbolic takes.
- **A group's resting member is nobody's driver**, which is how a caller asks for a whole group to
  rest: an undriven field answers zero, exactly what a member nobody selected holds, so exclusion is
  free. Every member that names a field names one no other member does - a shared name would make
  the frame's field lookup a first match rather than an answer - and the harness contract copy
  spells the resting member with the empty token.
- **A group carries a resting member only where vanilla has a resting arm.** An axolotl's `IN_AIR` is
  one of its own enum's members, a dolphin's `STILL` is the false arm of a boolean, and the clip
  groups rest on a stopped `AnimationState` - which is a boolean too, `isStarted()` being
  `startTick != Integer.MIN_VALUE`, and is the arm the subject spends most of its life in. **Two have
  none.** A bat's own `setupAnimationStates` stops one of its two states to start the other every
  tick, so a bat is on the wing or hanging and never neither; a breeze's whirl is started
  unconditionally - `Breeze.tick` runs `idle.startIfStopped` before the pose switch and regardless of
  which arm it takes - so its group has one member and nothing to select against. A member for either
  absence would be a coinage, and neither still subject is selectable.
- **A selector is a field vocabulary rather than a subject, and a shared field name merges two.** A
  camel's idle clip and a copper golem's both gate on `idleAnimationState`; a warden's, a creaking's
  and a sniffer's actions spell a shared `attackAnimationState` and `diggingAnimationState` between
  them. A field name is the whole of what the frame is asked, so two rows that exclude one another
  say so through the `group` their drivers carry, never through who reads the field.
- **A per-gait default is a base plus a replacement, never a switch.** The rabbit's `idle` row
  drives its head tilt and its `stride` row's hop driver REPLACES it in the same group; the breeze
  slides under a stride where it stands grounded at rest - the two subjects whose locomotion is a
  state-gated clip. Every other subject's stride row keeps its idle selections, because what a
  stride reaches for those subjects is a walk-gated clip that already plays - a camel's dash is a
  synched flag a rider sets and an armadillo's roll is fear, and selecting either under a stride
  would animate something vanilla does not do when the animal merely walks. **Reading a subject's
  delta between the two gaits rather than its gates is what makes those look like locomotion.**
- **One state vanilla drives is deliberately driven by no shipped row**, for a mechanism reason: a
  baby axolotl's `walkAnimationState` gates a walk-driven play site the fold settles and drops, so
  driving it puts that site back. The load-time validation of the select join tolerates exactly
  that field and no other.
- **An age whose group rests on a member driving no field ships no `idle` row, and resolving one
  through the universal row is the answer rather than a gap.** A group's idle default earns the
  standing row and no row of its own, so a default naming a field builds one and a default naming
  none leaves the age without an `idle` to resolve; the universal standing row then renders the mesh
  as authored, which is what vanilla draws for a resting subject. Deriving a row there instead would
  emit one that drives nothing, which is the shape the emitter already declines. The age axis is much
  wider than the axolotl: every entity whose baby walks at a pace of its own ships its `stride` split
  by age, so a row's `age` member is an ordinary column.
- **A value-constant clip is half of what earns `sources: []`, never the whole of it.** A row ships
  an empty source inventory when nothing travels across its whole composed binding, which needs the
  clip it selects to hold one value per channel AND the binding to leave no moving sibling
  co-playing. The second half is the group tokens: a bat's `rest` and `idle` carry one token, so
  selecting `rest` evicts the flying clip and only the held one is left, where a camel's `sit_pose`
  sits in a different group from its idle clip and keeps a moving `CAMEL_IDLE` underneath a pose
  every bit as constant as the bat's - and correctly earns `select`. Predicting an empty inventory
  from the clip alone gets the camel wrong. **A held row is still a distinct picture**, measured by
  rendering rather than reasoned from bone channels: each of the four that ship one - frog `jump`,
  bat `rest`, and the axolotl's `play_dead` at each age - differs from its base over 29.8% to 39.4%
  of a 128x128 canvas at frame 0, at a full alpha delta, and the three whose base moves render one
  frame where the base renders its strip.
- **A flag is folded against the FIGURES alone, which is what lets a state gate one.** Nothing at
  render reads a flag channel, so a bone's visibility has to be settled where the table is written -
  but the two halves of the driven set differ in whether a selection could carry it. A flag gated on
  a one-hot STATE is a bone a selection draws, so the fold settles the state and the mesh keeps the
  bone resting undrawn with a toggle over it; a flag gated on a FIGURE is a bone that blinks with the
  clock, which no toggle can say, so it stays symbolic and the flow refuses.
  `StyleRoster.DRIVEN_FIGURES` is that line, and a field filed on the wrong side ships wrongly
  shaped rows rather than failing loudly - the shipped catalog is what `StyleCatalogMirrorTest` then
  holds to the harness contract.
- **A bone drawn only while a clip runs is a toggle, and the frog's croak is the corpus's one.**
  `EntityBoneResolver` reads a gate by descriptor, so `<AnimationState>.isStarted()` is matched beside
  a plain `:Z` field read and the toggle is named off the state with its type suffix dropped. The
  clip and the bone are then inseparable and a render wanting the croak asks for both - the state, and
  the `croak` appearance - which is the same pair an armour stand's arms already take.
- **The roster ships once, and the contract copies are held to the shipped file.** The generator's
  `StyleRoster.DRIVEN` / `DRIVEN_FIGURES` are derivation input: what they split is emitted as the
  style catalog on `entity_models.json`, the pipeline loads it, and the harness keeps a deliberate
  copy of each group's per-gait default in `IdleFigures`, pinned against the shipped rows by
  `StyleCatalogMirrorTest`, where a value that moved on one side only renders happily and reports as
  a defect in this renderer. The harness reads every clock as the value the schedule roster carries
  for each frame - computed by `StyleDriver.at` on this side, so a clock has one implementation and
  no copy; the mirror holds instead that every clock a shipped row drives has a harness consumer
  writing it. Generator drift is silent a different way: **a field the roster names and the
  generator folds ships the arm a never-ticked subject takes whatever a caller selects**, which is
  what cost the rabbit's head 55.73 of delta - and a field kept symbolic that no shipped row drives
  reads zero at every tick. `EntityPoseLoadTest` pins that the shipped poses read nothing outside
  the driven set, because that failure mode is a silent zero: the arm a switch ends at is not the
  arm a subject stands in, and it cost the skeleton family a forty-four degree forward swing at rest
  before the fold reached it.

**Nothing at render reads a flag channel.** Every flag in the corpus folds to a literal at
generation, so which bones a subject rests without is written onto the mesh it rests in - a bone
nothing can draw is gone from that mesh, and a bone a selection can draw rests `visible: false`
naming what flips it - and nothing strips a mesh at load; the loader passes the two channel tokens
over. It is why the guardian's eye and the fox's legs need neither a harness pin nor an asset answer:
vanilla writes the eye `true` unconditionally, and calls the fox's `setWalkingPose` - which sets all
four legs `true` - before any branch, leaving only `setSleepingPose` behind an `isSleeping` that
rests false.

## Decisions that stay closed

- Do not merge the two clocks. `StyleDriver` counts ticks and REPLACES a render-state field, its
  window arriving from the row it was resolved against; a clip counts seconds through truncated
  whole milliseconds and ADDS a bone displacement, its window a component of its own. They address
  different spaces - a field name against a bone, a member and an axis - and one evaluates itself
  where the other's arithmetic is private to the kit that reads it. They are not even peers: a
  clip's own gate and clock ARE drivers, minted three lines above the clip in `Lowering`, so a
  merged type would take its own output as a precondition. `StyleDriver` validates only its timing,
  refusing one its wave cannot run or does not read; a clip's record validates nothing; and nothing
  cross-checks the arms, so a merged constructor would accept a held clock carrying keyframes - it
  would compile, render, and displace nothing. What IS worth taking is the arithmetic:
  `ClipPlayer` holds the keyframe curve and four helpers privately, called from one site, and none of
  them reads anything outside a keyframe, so they would sit on the channel beside the driver's own
  `at` and make the two units and the two rounding schedules legible as a pair for no new state.
