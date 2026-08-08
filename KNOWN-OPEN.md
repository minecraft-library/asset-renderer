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

## The blindness map's `source` column cites a working note `CLAUDE.md`'s citation rule forbids

`CLAUDE.md`'s *Skip these* states the rule: a tracked file cites no working note, by path or by entry
number. It binds what is authored from here, and the tree it landed in carried a residue of the
`P<n>` phase spelling two earlier efforts used. `git ls-files | xargs grep -nE '\bP-?[0-9]{1,2}\b' |
grep -vE 'P[0-9]+[a-zA-Z]'` finds what is left, among two kinds of noise: the promoted artifacts under
`src/test/resources/lib/minecraft/renderer/parity/`, whose `provenance.reason` is the exemption
`CLAUDE.md` states, and the two Gradle wrapper jars, which answer as binaries. The second `grep` also
swallows any line that spells a phase with a letter suffix beside the one it was looking for, so a
hit is a floor rather than a total.

The entity tooling's strings were the other family and are cleared - the eight resolver diagnostics
by re-running the flow and promoting the moved log digest, the policy string with them. One family
is left, and it is the harder one.

The column lives in `blindness.json` and in the `references/blindness.md` the skill renders from it,
and across 41 rules it divides three ways. **Nine rows spell a phase number. Twenty cite the design
pack by document and entry** - `07/blindness#N`, with one row also naming an audit the same way. The
remaining twelve already describe their own derivation and would survive untouched.

So scrubbing the nine changes nothing about what the column resolves to: twenty rows go on naming the
same unresolvable place in a spelling the grep above does not even look for. Clearing the column
means deciding what each of those twenty-nine rows' derivation is called once the pack it came from
is gone, and `BlindnessMapTest` requires the column non-blank on every rule, so no row can simply
lose it. That is a rewrite of the map's provenance, not a scrub, which is why it is open rather than
done.

## Most of the blindness map is unmeasured, and the first measurement falsified a rule

The map holds 41 rules. Fifteen have been measured by perturbing a file the rule triggers on,
re-capturing what it declares and comparing: six answered exactly as declared, four moved a strict
subset of their declared `sees`, one differed, two were void because their observed set was exactly
the reference-drift set rather than reach, one was confirmed outright and one was falsified. The
remaining twenty-six are declared and unchecked.

**The falsified rule is B2, and the defect is its trigger glob rather than its claim.** Perturbing
`asset/pack/rule/ColorProperties.java` moved `manifest.dump.packs`, which B2 lists as blind; the
control artifact did not move, and the other blind artifact did not either. B2 claims CIT and CTM
rules are dark in both dump configurations, and that claim is about CIT and CTM. Its first trigger
path is the whole `asset/pack/rule/**` package, so a change to a sibling the claim says nothing about
still selects the rule, and the rule then declares blind an artifact that change reaches. A
false-blind is the one direction of error a gate cannot absorb: it tells a session to skip the
artifact that would have caught the change.

**The confirmed rule is B10**, whose blind list is exact - perturbing the `buildBox` UV quartet left
both `sweep.block` and `sweep.item` untouched, so the claim that `BlockRenderer` never calls
`buildBox` holds. Four of its six declared `sees` moved. One was never measured because its producer
failed, and `manifest.portal` was captured and did not move, which makes it a candidate
over-declaration rather than a second falsification.

A rule moving a **strict subset** of what it declares is ambiguous between an over-declaring rule and
a perturbation too weak to reach the rest, so those four results settle nothing on their own. Only a
rule moving something it declared **blind** is unambiguous, and it is also the only direction that
matters: over-declaring `sees` costs a gate session time, where a false-blind costs it the finding.

Four things make this expensive, each already paid for once. A live perturbation reddens `test`, and
a red `test` writes no completion marker, after which the compare correctly refuses the root - only
four artifacts need `test` at all. Capturing a set aggregates, so one failing producer discards every
sibling that worked, which is what defeated most of the rules rather than the perturbations
themselves; capture one artifact at a time. A perturbation under `harness/**` re-renders the
reference tree, and a driver that restores source cannot restore rendered files, so every rule
measured after one is measured against perturbed ground truth. And killing a driver strands its
perturbation, because the restore never runs.
