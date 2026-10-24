# The reference graph

Load when touching `parity/reach.json` or `reach.py`'s graph and held demotes, or when a plan
selects more or less than a change should reach. Indexed from
[`parity/CLAUDE.md`](../../parity/CLAUDE.md).

**A rule carrying `derived` authors no `sees`; the reference graph answers it per FILE.** Seven do -
`engine-renders`, `option-surface`, `asset-layer`, `tensor-math`, `face-vocabulary`,
`pose-vocabulary`, `pose-authoring` - which is why `PoseEvaluator` plans six artifacts where
`Rasterizer`, in the same engine, plans eighteen, and why moving a type between two derived regions
carries its reach with it. The graph is `parity/reach.json`, derived from the **compiled constant
pool** and committed: an import is not evidence, the javadoc convention requiring a `{@link}` target
be imported, and a same-package call needs no import at all. It is regenerated with
`python parity/scripts/parity reach build` over a compiled tree and held to the tree by
`parityReachCheck` on `check`; `plan` reads the committed file, so a stale graph is a loud difference
rather than a quiet mis-schedule, and a `.java` path it has never heard of is a refusal.

**The graph is class-granular.** An edge says that one class's constant pool names another, not which
of its members reads it, and a constant javac inlines leaves only a class entry in the reader's pool -
so the type declaring a shared constant answers for every producer that reaches any class reading it.
`Camera` names `ModelUnits` for the `PIXELS_PER_BLOCK` that `fromTransform` alone reads, so a
`ModelUnits` edit plans the fluid and portal producers, which reach `Camera` and never call
`fromTransform`. That over-selection is the accepted price, being the safe side of the error, and a
member-level walk would not narrow it: the inlined read carries no member reference to attribute.

**A sealed type's `permits` list is not an edge.** The `PermittedSubclasses` attribute names each
permitted subtype in the sealed type's own pool, and read as a reference it makes everything that
reaches the supertype reach every subtype - every producer reaching `ContentException` would reach
`ColorMapException`, which only the colormap loader throws. So the reader passes over a listed name
that nothing else in the file names, and keeps the edge wherever something does: a member reference,
a descriptor or signature, a `throws` clause, a string constant, or a class operand in a method
body - an instruction, a catch type, a stack-map entry, a bootstrap argument. The one use it cannot
tell from the listing is a compile-time constant read from a subtype the type permits: javac inlines
the value and leaves only the class entry the listing already holds, the blindness the paragraph
above accepts for every inlined read.

**Four trees are compiled for it, not one** - the renderer's main, test and visual sets, and the
generators' main, whose eight flow entry points are what root `manifest.tooling-tables`. That root is
why a renderer type the generators execute answers the tooling tables per file rather than through an
authored list somebody has to remember: `Diagnostics` answers it, `DepthMath` does not, and a type
that gains a generator caller next month answers it that day. A class root the tree does not hold is
SKIPPED rather than refused, so a build that forgets one derives a graph missing every edge under it
- which `parityReachCheck` then reports as a difference on every one.

A rule keeps its `blind` list either way. That says what an artifact OBSERVES, which no reference
graph can answer: the dump reaches a face and a vector because both are serialised, and perturbing
either leaves every dump file byte-identical - so `face-vocabulary` and `tensor-math` subtract it as
`engine-renders` always has. A derived claim's demotion subtracts from its OWN selection, which is
what makes it legal with no sibling claim on the path.

**A held demote's carrier reaches nothing the demote subtracts unless its ledger lists it.** A
demote takes its `blind` list out of the plan on every path it fires on, whatever selected an
artifact there, so a carrier whose own reach holds one loses it from every plan it is in and the
plan says nothing. `HELD_DEMOTES` in `reach.py` holds every claim-keyed demote that fires on a
scanned source path, each against a ledger of the carriers that lose an artifact that way by
decision, and `reach check` refuses an unlisted carrier that reaches what its demote subtracts - a
new carrier or a new edge - and a listed path that no longer does or that the demote no longer fires
on, so a ledger only shrinks. `tooling-blindness`, `menu-closure` and `text-closure` list nothing:
the first is true only of code no renderer producer runs, so a renderer caller of `TableEnvelope`
fails the check instead of quietly taking the sweeps it reaches out of the envelope's plans, the
second only while a menu type reaches nothing but the visual manifest and the menu sweep, and the
third only while a text type reaches nothing but the visual manifest and the tooltip sweep. The
other four list the carriers they subtract from by decision - `engine-renders`, `tensor-math` and
`face-vocabulary` take the dumps off a type the dump reads or serialises and never renders, and
`cit-grammar` takes the renders off a grammar none of their producers parses a rule of. The
`harness-*` claims fire on no scanned source path, so the graph has nothing to check for them.
