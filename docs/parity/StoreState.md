# Store state that does not look like store state

Load before renaming or moving a test or visual-set class, or editing above a cited line in one of
the five line-pinned files below. Indexed from [`parity/CLAUDE.md`](../../parity/CLAUDE.md).

**A test or visual-set class's own name and path are store state.** `index.json` homes fourteen rows
at a `sources[*].test_class` or `external[*].home` FQN and `ParityIndexTest` resolves each against the
source tree; `blindness.json` announces nine test paths verbatim as `B38` trigger paths and
`BlindnessMapTest` asserts every announced trigger path is a tracked path. So renaming or moving one of
those files is a promote, not a rename, and the cheapest way to find out is to grep both files for the
class before touching it. Five files go further and pin a LINE NUMBER: in
`EntityModelLoaderArmorRosterTest`, `HumanoidPartCropTest`, `ArmorParitySweep`, `GlintParitySweep` and
`PlayerParitySweep`, an edit above a cited anchor - a javadoc line included - either keeps the line
count or moves the citing row's `lines` pointer in `index.json`'s `sources` section in the same
commit, and `ParityIndexTest`'s `everyLinesCitationBracketsItsRoster` refuses a range that no longer
opens on its anchor. Rewriting a store row to satisfy a naming rule falsifies the record the rule
exists to keep; the name is the thing that gives way.

An edit to `blindness.json`, its `source` column included, also owes the skill's rendered view:
`.claude/skills/parity-gate/references/blindness.md` renders from it, and `ParityReferencesTest`
fails until `./gradlew :test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true
--rerun` writes it again - without `--rerun` the task reports success and writes nothing.
