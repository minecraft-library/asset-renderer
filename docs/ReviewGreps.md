# Review greps for the notes rule

Load when reviewing a change for a `notes/` citation or a phase-number spelling. The rule is
`CLAUDE.md`'s *Skip these*: no tracked file cites a `notes/` path or a working note's entry by
number. This is what a review greps for and what survives the grep. Indexed from
[`CLAUDE.md`](../CLAUDE.md).

Only the path half is greppable: `git ls-files | xargs grep -l 'notes/'` at review, deliberately not
in the pre-commit hook. What survives that grep is the `notes/**` blindness glob, in the map and in
the skill's rendered view of it; the test exempting that glob from the assertion that every
`no_reach` glob matches a tracked path; the ignore rule that makes the directory gitignored; `CLAUDE.md`'s
*Skip these*; each gate skill's list of commit kinds that skip it; and the two toolkit tests asserting
that `notes/parity` is refused as a working root. Telling those from a citation is a reading rather
than a pattern. The entry-number half has no one pattern to grep for, a note's ids being whatever
that note chose, so it is read rather than matched. An id a tracked file defines itself is not a
citation at all: the reach rules carry their own `B<n>` in an `id` member.

The phase-number spelling has a review grep of its own, and the tree is clean of what it forbids:
`git ls-files | xargs grep -nE '\bP-?[0-9]{1,2}\b' | grep -vE 'P[0-9]+[a-zA-Z]'` answers today with
the promoted artifacts, whose recorded reason is the exemption `CLAUDE.md` states beside the rule, and binaries whose bytes happen
to match - the two Gradle wrapper jars and several of the README's images under `docs/images/`.
