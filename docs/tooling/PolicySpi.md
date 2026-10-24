# The policy SPI

Load when touching `policy/`, a flow's `*Policies` enum, `TraceReplay` or `PolicyPurityTest`.
Indexed from [`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

`policy/` holds the SPI and no fact of its own. A flow-local package-private `*Policies` enum declares
the rows its flow needs, and `NavigationPolicy.navigate(AsmContext)` is how a consumer reaches one. A
row answers one of the five arms `Navigation` seals over: `At` and `AtResource` name a bytecode or
jar-resource coordinate the caller re-enters the generic engine at, `Dataflow` adds the ordered steps
a replay walks from one, `Value` is a terminal declared fact carrying its mandatory provenance, and
`None` leaves generic detection standing. `requireAt` / `requireDataflow` are the narrowing a consumer
would otherwise write per site; they raise rather than fall back, a row answering an arm its consumer
cannot read being a contradiction in the roster rather than a jar that drifted.

- **Consumers consult, they do not read.** A payload accessor is sanctioned on a `Value` row alone.
  `SHEET_TEXTURE_BASES` is the one row handing a coordinate out through one: its coordinates are keyed
  by `CatalogFamily`, and the frame the flow builds carries the subject's `beTypeId()` where that key
  is derived from its `localId()`, so the accessor takes the family `BlockCatalogResolver` already
  holds. That is a cost rather than a correctness bar - a subject-keyed consultation would answer the
  same sheet, and the three families that ask for none would answer off the table the way
  `RENDERER_ENTRY_METHODS` does. `PolicyPurityTest` pins it as the only exemption: a second member
  handing a coordinate out, a raw read of a coordinate row's payload and a coordinate destructured
  into its own strings each fail.
- **`Trace.Step` is a ceiling, not a starting point.** It is sealed over eight records, declares no
  functional member anywhere, and no step carries a bytecode node, an instruction or an ASM type, so a
  policy declaring a whole trace still names nothing of the fetch surface. `TraceReplay` is the one
  engine that walks it, and a trace that cannot complete raises naming the step index rather than
  answering a fact nothing derived.
- **`AsmContext.keyless` is the one fabricated-frame spelling.** A session-lifetime row has no roster
  id, so the frame stamps `KEYLESS_SUBJECT` rather than inventing an id per site, and a row consulted
  through one answers the same whatever the subject - asserted, the arms being records. Which rows
  those are is declared in the test and re-derived from the sources, so a consumer that gains a
  subject-keyed consultation drops the rows it reaches out of that coverage and fails there rather
  than thinning the guard in silence.
- **A policy never fetches, by import and by reach.** The import bans do not see
  `context.session().cache()`, which needs no import, so the cache reach is banned as text in a policy
  source as well.
- `PolicyPurityTest` discovers implementors by walking for the `*Policies.java` filename and reflects
  on a `provenance` field each declares itself, so they cannot share a superclass and an enum left with
  no constants fails rather than passing as a member. It pins that each stays flow-local, and declares
  the arm every row answers, so a row carrying a coordinate that comes back as a `Value` fails there.

## Decisions that stay closed

The two policy refusals are the arm's own ceiling: a fact needing a step the closed vocabulary does not
declare is not a candidate for the arm, and growing the vocabulary to fit one caller is what the ceiling
exists to prevent.

- Do not move `BANNER_DYE_TARGET` onto a trace - recovering the dye-taking mesh needs a recursive
  search of a submit's callees under a visiting set, and the answer is the declared type of a parameter
  slot, appearing nowhere as an operand. `BlockTintFlagResolver` owns that walk and the row names the
  coordinate it starts at.
- Do not move `BANNER_FIELDS` off `Value` - recovering a split id from a `ModelLayers` field name needs
  string operators the vocabulary does not have (strip a prefix, append a suffix, prepend a namespace),
  and the ids they would build are our own output convention rather than anything vanilla spells.
