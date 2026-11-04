# Store state that does not look like store state

Load before renaming or moving a test or visual-set class, renaming a heading a reach rule cites, or
moving the file that holds one. Indexed from [`parity/CLAUDE.md`](../../parity/CLAUDE.md).

**A test or visual-set class's own name and path are store state.** `index.json` homes one row at a
`sources[*].test_class` FQN, and `ParityIndexTest` resolves that and any `external[*].home` spelled
as a class against the source tree; `blindness.json` announces five test paths verbatim as `B38`
trigger paths and `BlindnessMapTest` asserts every announced trigger path is a tracked path. So
renaming or moving one of those files is a promote, not a rename, and the cheapest way to find out is
to grep both files for the class before touching it. Rewriting a store row to satisfy a naming rule
falsifies the record the rule exists to keep; the name is the thing that gives way.

**Those five are the pin writers, and two lists outside the store name them too.** `reach.py`'s
`ROOTS` roots each self-captured row at its writer by simple name, and `parityPinWriters` in
`gradle/parity.gradle.kts` lists each by qualified name as everything `parityPinTest` runs.
`ParityTaskWiringTest` holds the two to each other and the list to the source tree, so a writer
renamed without both fails there; a writer the list missed would leave its row unwritten by every
capture - UNPRODUCED, and only once every producer of the bundle has run.

**A heading a rule cites is store state too.** `blindness.json`'s `source` column cites headings of
the root `CLAUDE.md` and of the topic files under `docs/`, each by its file's repo-relative path and
the heading's name, and `BlindnessMapTest` holds every citation to a tracked file that still carries
the heading. The plan never reads the column, so nothing else notices a dead one. Renaming the heading
and moving the file are the same edit to the map: rename the citation with it, and
`gradle/parity.gradle.kts` declares both files as inputs of the test task so the check runs on the
edit.

An edit to `blindness.json`, its `source` column included, also owes the skill's rendered view:
`.claude/skills/parity-gate/references/blindness.md` renders from it, and `ParityReferencesTest`
fails until `./gradlew :test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true
--rerun` writes it again - without `--rerun` the task reports success and writes nothing.
