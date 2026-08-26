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

## The budget prints as a floor, and three mechanisms keep twelve artifacts out of it

`parityPlan`'s BUDGET line sums each planned artifact's `last_duration_ms`, and twelve of the
store's twenty-six baselined rows carry none - so the line reads as a floor rather than a cost, and a
bundle whose measured half is a two-second table and whose unmeasured half boots the client prints
comfortably under the rule that decides whether to background it. Three independent mechanisms hold
it there and none of them is neglect.

**A capture stamps a wall time only for a producer that ran to completion in that invocation.** The
build times each producer between its own `doFirst` and `doLast` and passes the sum as `--wall-time`
only when it exceeds zero, so an up-to-date producer and a producer whose suite failed both record
nothing. That half closes by forcing re-execution on a green tree, and it is why `entity_models`'
own row carried no duration through the promotion that re-based it: `test` was red in that capture,
so the hook never fired.

**An aggregator producer can never stamp one.** `visualSweepSet` and `playerRawSweepSet` do their
work through `dependsOn` alone, so the span between their own `doFirst` and `doLast` opens after
every dependency has finished and rounds to zero. `manifest.visual` and `manifest.player-raw` are the
two rows that follow, and no forced re-run reaches them - measured null over three runs each.

**A measured duration cannot be promoted on its own.** `parityPromote` skips a row whose content is
unchanged before it reads the file, so the index row carrying `last_duration_ms` is never rebuilt: a
timing refresh over ten rows reported `promoted 0: nothing` with every capture carrying a wall time.
A duration therefore lands only as a side effect of a promotion that also moves content, and the rows
that never move are exactly the rows that never get one.

What the producers actually cost, measured over three sequential forced re-runs each under no other
load: `test` 8131/8133/9128 ms, `slowTest` 22053/22150/20402 ms, `fluidRenderer` 12228/12316/12497 ms,
`portalRenderer` 148026/146600/147676 ms. The portal alone outruns the threshold that decides whether
a capture is backgrounded, which no budget has ever said. Closing this is two small changes rather
than a phase - let an unchanged row still refresh its recorded duration, and give an aggregator the
sum of its dependencies' - and the numbers above are what they would record.
