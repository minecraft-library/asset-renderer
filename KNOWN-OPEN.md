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
