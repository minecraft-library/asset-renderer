# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `CLAUDE.md`'s *Decisions
that stay closed*. A measurement belongs in the commit that made it, and in the `reason` recorded
with the baseline it moved.

Delete an entry when it closes.

## The load-time mesh surgery is wanted baked, and no design that generalizes exists yet

`EntityIndexBuilder` re-derives meshes at every load - the undrawn strip, the squid family's y
shift, the warden's retained-bone subset - and the owner wants that information baked into shipped
geometry coordinates, the way `@grow=` / `@scaled=` / `@baby=` already bake thirty of the hundred
and forty-five. The naive bake fails on a measured blocker: a toggle re-adds bones the undrawn
strip removes - the armour stand's arms, the chest of every equine that carries one, the turtle's
egg belly, six subjects in all - and `loadBoneToggles` captures those bones' geometry from the
UNSTRIPPED mesh, so the strip has to run somewhere that mesh still exists. Shipping a coordinate
per toggle state is combinatorial; keeping the strip for the six blocked subjects keeps the
mechanism for every subject; and the two pieces that could bake alone - the shift and the retained
subset, two methods and roughly fifty lines - cost a table gate and a reinterpretation of the squid
shift's recorded reason for a net near zero.

Closing it is a research effort rather than a phase: a design in which the shipped form carries
both what a subject rests without and what a toggle can re-add, generally enough that the load-time
surgery goes whole rather than by exception. Until one exists, the surgery stays at load and the
derivations stay runtime facts.

## Most of the clip tables are dead offline, and the live tail is why they have not been shed

Of `entity_poses.json`'s seventy-four clip tables, fifty-seven are reachable only through `state`
gates - started by something that has not happened to a subject standing still, so `ClipKit`
answers null for them in every render mode - and two more are named by no play site at all. Together
they are roughly 300 KB of the member's 359, parsed into records on every boot. They stay because
the tail is not dead the same way: thirteen of the fifty-seven are locomotion clips vanilla fires
when the subject actually moves - the axolotl's swim and underwater walk, both rabbits' hop, the
bat's flight, both camels' dash, the breeze's slide, the armadillo's roll - which is exactly what a
WALK gate against a genuinely striding reference needs, and WALK has no vanilla reference until the
harness drives a stride. Shedding now would be re-adding then.

Whoever takes it inherits three constraints. The loader throws on a play site naming a table the
file does not carry, so sites and tables move together. A prior decision binds the two unread flag
channels and the three defaults tables - which the emitter still writes and nothing reads - to
whatever commit sheds the clips. And `EntityPoseLoadTest` pins the turtle's egg-belly bone through
a channel map the flag shed empties, so its pins move with the emitter. Closing it is either the
harness stride (WALK gets a reference, the thirteen become measurable, and the genuinely dead rest
shed behind them) or an owner ruling that the offline renderer never plays a state-driven clip.

## The horse marking still draws through its own feature arm, beside the mechanism the other axes take

The texture axes answer for themselves on the overlay pass, but the horse's marking is not an
overlay row: it is a node whose presence gates a dedicated feature arm, `MarkingsGate` and
`Entity.Layers.markings` carry the gate, and the drawn texture comes off `HorseMarking`'s own
enum columns. The node ships the full option-to-path map beside itself
(`textures_by_value`, four rows) and nothing reads it - the same unread map the iron golem's
crackiness row ships. Folding the marking into the axis mechanism is therefore two questions nobody
has asked squarely: whether the shipped map becomes the read and the enum's ref columns go - the
texture spelling of the move the tint ruling refused, against the standing rule that stability and
call-site legibility beat deriving - and whether the marking becomes an overlay row at all, which
is a table shape the generator would have to emit. Until both are answered, the feature arm stays.

## The gate roster is nine records where one axis interface was licensed

`AppearanceGate` seals over nine arms - state, flag, charged, tinted, equipment, markings, collar,
age, size - and the surface-reduction work licensed exactly one new type for the whole programme:
an axis interface collapsing that roster, on the ground that collapsing N types into one is the
reduction worth a type where a carrier is not. The programme closed without scheduling it and the
design lives nowhere tracked. An earlier pass at unifying the appearance axes was parked with the
owner rather than decided, so this is a design conversation before it is a phase.
