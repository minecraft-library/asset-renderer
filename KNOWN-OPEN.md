# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `CLAUDE.md`'s *Decisions
that stay closed*. A measurement belongs in the commit that made it, and in the `reason` recorded
with the baseline it moved. A gap that has already been written down where its reader will be
standing - `scripts/parity/lab/README.md` says which analysis capabilities were never ported and why
that is not an oversight - stays there rather than being restated here; two accounts of one thing is
how a reader gets the wrong half first.

Delete an entry when it closes.

## The parity budget is declared everywhere and measured nowhere

`parityPlan` prints a `BUDGET` line, `SKILL.md` tells a gate session to background a capture whose
budget exceeds 110s, and the store registers `report.wall-time` as a pointer into
`<artifact>#/provenance/wall_time_ms`. Nothing writes the number. `provenance.gather` takes a
`wall_time_ms` argument and no caller passes one, so no captured payload carries the field;
`promote._index_row` copies it to `last_duration_ms` only when it is present, so no index row carries
that either; and `cli`'s plan sums `last_duration_ms` defaulted to `0` over the artifacts a change
reaches. Every budget the plan prints is therefore `0 ms`. `SKILL.md` states what a zero
budget means - that no artifact in the plan has a recorded duration, not that the bundle is free -
which is why this is a gap and not a live defect.

Closing it is one of two things: have each capture step record its own wall time into provenance, or
take the budget out of the plan's output - the `BUDGET` line, the skill's 110s rule and the
`report.wall-time` pointer go together either way.

## Nothing has reviewed the store's bootstrap, so its independent cross-checks cannot be retired

The parity store's first promotion was cross-checked against a set of independently produced reports
and a reference-tree digest, none of which is tracked. Their deletion was gated on a review of that
first promotion, and nothing records one having happened. Until one does, they are the only check on the
store's bootstrap that does not come from the store, so removing them is a decision rather than
housekeeping - and because they are untracked, losing that directory takes the check with it.

## Phase numbers from earlier efforts survive in two places `CLAUDE.md`'s citation rule forbids

`CLAUDE.md`'s *Skip these* states the rule: a tracked file cites no working note, by path or by entry
number. It binds what is authored from here, and the tree it landed in carries a residue of the
`P<n>` phase spelling two earlier efforts used. `git ls-files | xargs grep -nE '\bP-?[0-9]{1,2}\b' |
grep -vE 'P[0-9]+[a-zA-Z]'` finds them, among two kinds of noise: the promoted artifacts under
`src/test/resources/lib/minecraft/renderer/parity/`, whose `provenance.reason` is the exemption
`CLAUDE.md` states, and the two Gradle wrapper jars, which answer as binaries. The second `grep` also
swallows any line that spells a phase with a letter suffix beside the one it was looking for, so a
hit is a floor rather than a total.

Two families, and what clearing each costs is not the same.

The **entity tooling's strings** are the first, and they divide. The `tooling/entity/*Resolver`
citations are in diagnostics the entity flow can emit, and `manifest.tooling-tables` carries a `logs`
member digesting that flow's diagnostics log as its `(severity, path, message)` triples - so
rewording one is a tooling-flow change, measured by re-running the flow and comparing emitted bytes
and the log against a capture from the clean tree, and then promoted. No test asserts any of those
strings, so
`./gradlew test` says nothing either way. `EntityOverlayPolicies`' relocation note is different: a
policy's `provenance` is read by `PolicyPurityTest` for non-blankness and by nothing else, never
reaching a log or a table, so that one really is a one-line edit - held back only because it would
put the whole tooling reach class into a gate for a string, and it should ride the next tooling
commit. Two test display names of the same vintage were the free half and are already cleared.

The **blindness map's `source` column** is the other, in `blindness.json` and in the
`references/blindness.md` the skill renders from it. Some rows carry a phase number, and a larger set
cites the design pack by document and entry number instead, so clearing the phase numbers alone
leaves the column naming the same unresolvable place in a different spelling; clearing the column
means deciding what each row's derivation is called once the pack it came from is gone.
`BlindnessMapTest` requires the column non-blank on every rule, so no row can simply lose it.
