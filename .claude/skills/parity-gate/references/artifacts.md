# Parity artifacts

Generated from `ParityArtifacts` and `index.json`. **Do not edit** - regenerate with:

```
./gradlew test --tests "*ParityReferencesTest" -Dasset.parity.regenerateViews=true --rerun
```

Every artifact the store knows about, in roster order: sweep-table,
render-manifest, file-digest-set, value-pin, roster, report, probe. `home` is where
the value lives - `STORE` has a file, `POINTER` is a field of another artifact's
file, `SOURCE` is a deliberate second copy held in Java, and `EXTERNAL` is not held
at all. An artifact that is not `baselined` has no last known value, so a comparison
against it answers `MISSING_BASELINE` rather than passing.

`producer` is what a capture of that row **runs**: `parityCapture` depends on the
producers of every row it captures, and the row's own capture step orders itself after
them rather than depending on them. The column is those tasks in the build file's
order, asserted equal to that row there. It is not a census of every task able to write
those bytes, so a task absent from a row can still write into it - a narrower harness
run refreshing part of the reference tree, one visual producer rewriting its own
sub-tree. Run less than a row names and the rest of its file set stays at whatever wrote
it last, while the capture hashes cleanly, because every declared member exists.

The store's own two root files are the exception: no capture step covers either, so the
build file holds no row for them and the column says what writes the file instead -
`parityPromote` for the index, which a promotion stamps in the same act as the baseline
it writes, and nothing for the rule roster, which is hand-authored. Both are asserted
against that reading rather than against the build file.

`floor` is how many runs a **first** promotion performs. `runs` is how many actually
agreed, read back from the promoted file - the two are different numbers on purpose,
because a floor that doubled as the record would let a declaration pass for evidence.

| artifact | kind | home | producer | floor | runs | entries | cost | baselined |
|---|---|---|---|---:|---:|---:|---:|---|
| `sweep.entity` | sweep-table | STORE | `entityParityVanilla` | 2 | 2 | 402 | 23999 ms | yes |
| `sweep.block` | sweep-table | STORE | `blockParityVanilla` | 2 | 2 | 1055 | 50027 ms | yes |
| `sweep.item` | sweep-table | STORE | `itemParityVanilla` | 2 | 2 | 479 | 129288 ms | yes |
| `sweep.player` | sweep-table | STORE | `playerParityVanilla` | 2 | 2 | 2 | 13402 ms | yes |
| `sweep.armor` | sweep-table | STORE | `armorParityVanilla` | 2 | 2 | 7 | 16490 ms | yes |
| `sweep.glint` | sweep-table | STORE | `glintParityVanilla` | 2 | 2 | 11 | 31712 ms | yes |
| `sweep.menu` | sweep-table | STORE | `menuParityVanilla` | 2 | 2 | 10 | 13757 ms | yes |
| `sweep.entity-animation` | sweep-table | STORE | `entityAnimationParityVanilla` | 2 | 2 | 90 | 18414 ms | yes |
| `manifest.references` | manifest | STORE | `renderVanillaAllReferences` | 2 | 2 | 3042 | 138730 ms | yes |
| `manifest.visual` | manifest | STORE | `visualSweepSet` | 2 | 2 | 210 | - | yes |
| `manifest.player-raw` | manifest | STORE | `playerRawSweepSet` | 2 | 2 | 18 | - | yes |
| `manifest.dump.vanilla` | manifest | STORE | `parityDump` | 5 | 5 | 14 | 28467 ms | yes |
| `manifest.dump.packs` | manifest | STORE | `parityDump` | 5 | 5 | 14 | 28467 ms | yes |
| `manifest.player-sheets` | manifest | STORE | `playerRender` | 2 | 2 | 104 | 17186 ms | yes |
| `manifest.fluid` | manifest | STORE | `fluidRenderer` | 2 | 2 | 12 | - | yes |
| `manifest.portal` | manifest | STORE | `portalRenderer` | 2 | 2 | 12 | - | yes |
| `manifest.tooling-tables` | manifest | STORE | `entityModels`, `blockModels`, `blockDefaults`, `blockItems`, `blockTints`, `potionColors`, `glintItems`, `colorMaps` | 2 | 2 | 11 | 45049 ms | yes |
| `digest.shipped-tables` | digest-set | STORE | `test` | 1 | 1 | 11 | - | yes |
| `digest.colormap-lut` | digest-set | STORE | `slowTest` | 1 | 2 | 3 | - | yes |
| `digest.dump-sections` | - | EXTERNAL | - | - | - | - | - | - |
| `pin.vanilla-iso-pose` | pin-set | STORE | `test` | 1 | 2 | 1 | - | yes |
| `pin.kit-corners` | pin-set | STORE | `test` | 1 | 2 | 1 | - | yes |
| `pin.corpus-count` | pin-set | STORE | `test` | 1 | 1 | 4 | 6718 ms | yes |
| `pin.player-crc` | pin-set | STORE | `slowTest` | 1 | 1 | 3 | - | yes |
| `pin.block-crc` | pin-set | STORE | `slowTest` | 1 | 2 | 3 | - | yes |
| `pin.portal-crc` | pin-set | STORE | `slowTest` | 1 | 2 | 2 | - | yes |
| `pin.fluid-crc` | pin-set | STORE | `slowTest` | 1 | 2 | 13 | - | yes |
| `pin.armor-span` | - | SOURCE | - | - | - | - | - | - |
| `pin.tick-lattice` | - | EXTERNAL | - | - | - | - | - | - |
| `roster.humanoid-armor` | - | SOURCE | - | - | - | - | - | - |
| `roster.overlay-pipeline` | - | SOURCE | - | - | - | - | - | - |
| `roster.humanoid-part-crop` | - | SOURCE | - | - | - | - | - | - |
| `roster.face-phase` | - | SOURCE | - | - | - | - | - | - |
| `roster.frame-turn` | - | SOURCE | - | - | - | - | - | - |
| `roster.armor-subjects` | - | SOURCE | - | - | - | - | - | - |
| `roster.glint-subjects` | - | SOURCE | - | - | - | - | - | - |
| `roster.player-scopes` | - | SOURCE | - | - | - | - | - | - |
| `roster.sheet-groups` | - | SOURCE | - | - | - | - | - | - |
| `roster.dump-sections` | - | SOURCE | - | - | - | - | - | - |
| `roster.pack-fixtures` | - | SOURCE | - | - | - | - | - | - |
| `roster.appearance-axes` | - | SOURCE | - | - | - | - | - | - |
| `roster.blindness-rules` | blindness-roster | STORE | - | 1 | - | - | - | **no** |
| `report.sum` | - | POINTER | - | - | - | - | - | - |
| `report.buckets` | - | POINTER | - | - | - | - | - | - |
| `report.coverage-gaps` | - | POINTER | - | - | - | - | - | - |
| `report.wall-time` | - | POINTER | - | - | - | - | - | - |
| `report.worst-list` | - | POINTER | - | - | - | - | - | - |
| `report.failure-rows` | - | POINTER | - | - | - | - | - | - |
| `report.run-provenance` | - | POINTER | - | - | - | - | - | - |
| `report.diagnostics-log` | - | POINTER | - | - | - | - | - | - |
| `report.harness-sweep-counts` | - | POINTER | - | - | - | - | - | - |
| `report.movers` | - | EXTERNAL | - | - | - | - | - | - |
| `report.expected-diff` | - | EXTERNAL | - | - | - | - | - | - |
| `report.plan` | - | EXTERNAL | - | - | - | - | - | - |
| `report.capture-note` | - | EXTERNAL | - | - | - | - | - | - |
| `report.harness-fit-log` | - | EXTERNAL | - | - | - | - | - | - |
| `report.oracle-index` | index | STORE | `parityPromote` | 1 | - | - | - | **no** |
| `probe.pixel` | - | EXTERNAL | - | - | - | - | - | - |
| `probe.depth-quantum` | - | EXTERNAL | - | - | - | - | - | - |

## Tasks that carry no artifact id

Recorded so a reader does not conclude they were forgotten. None of them can be
selected by `parityPlan`, and none is a gate.

| task | why it has no id |
|---|---|
| `generateAtlas` (with `-Pdiagnose` / `-PsourceFilter` / `-PskipRender`) | `AtlasRenderer` dispatches its tiles on `parallelStream` by design, so two runs place the same sprites at different offsets and the output can never be hashed (blindness rule B15). A must-not-crash smoke check. |
| `javadoc` | RED at HEAD, and every error is the same one: a builder an annotation processor produces and the doclet cannot see. Its exit code carries no information. The incubator module flag is wired onto it like every other consumer, which is why the two errors that were about `SimdOps` are gone and the task is still red. |
| `jmh` | Benchmark scores, not rendered bytes. `jmh-regression-gate` is the separate skill that compares them. |
| `redstoneTints`, `stackCountBadge`, `blockFlipbook` | Authoring and version-bump tools. No stored artifact is defined over any of their output directories - none is a member of `manifest.visual` - so what they write is compared against nothing this store holds. |
| `blockRender3D`, `entityProjections`, `entityRender3D`, `itemDayCycle`, `itemRender2D`, `loreTooltip`, `menuRender`, `projectionSmoke` | Visual producers whose `cache/visual` sub-tree is a member of `manifest.visual`, so the rows they write are gated under that id and captured by `visualSweepSet` rather than by a task of their own. |
| `renderVanillaReferences` and the three narrow harness runs | Preconditions rather than gates: they produce the ground truth every sweep is measured against. Only `renderVanillaAllReferences` refreshes the whole tree, which is why it is the one `manifest.references` names. |
| `renderVanillaPitchRollProbe`, `renderVanillaDepthQuantumProbe` | Harness probes that render OUTSIDE the reference tree and refresh no reference, so neither is a precondition either. The second's output is registered as `probe.depth-quantum`, which is external: it is evidence rather than a value a gate reproduces. |
