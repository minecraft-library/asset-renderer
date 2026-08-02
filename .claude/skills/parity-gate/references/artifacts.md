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

`floor` is how many runs a **first** promotion performs. `runs` is how many actually
agreed, read back from the promoted file - the two are different numbers on purpose,
because a floor that doubled as the record would let a declaration pass for evidence.

| artifact | kind | home | producer | floor | runs | entries | cost | baselined |
|---|---|---|---|---:|---:|---:|---:|---|
| `sweep.entity` | sweep-table | STORE | `entityParityVanilla` | 2 | 2 | 402 | - | yes |
| `sweep.block` | sweep-table | STORE | `blockParityVanilla` | 2 | 2 | 1055 | - | yes |
| `sweep.item` | sweep-table | STORE | `itemParityVanilla` | 2 | 2 | 479 | - | yes |
| `sweep.player` | sweep-table | STORE | `playerParityVanilla` | 2 | 2 | 2 | - | yes |
| `sweep.armor` | sweep-table | STORE | `armorParityVanilla` | 2 | 2 | 7 | - | yes |
| `sweep.glint` | sweep-table | STORE | `glintParityVanilla` | 2 | 2 | 11 | - | yes |
| `manifest.references` | manifest | STORE | `renderVanillaAllReferences` | 2 | 2 | 2312 | - | yes |
| `manifest.visual` | manifest | STORE | `visualSweepSet` | 2 | 2 | 153 | - | yes |
| `manifest.player-raw` | manifest | STORE | `playerRawSweepSet` | 2 | 2 | 18 | - | yes |
| `manifest.dump.vanilla` | manifest | STORE | `parityDump` | 5 | 5 | 14 | - | yes |
| `manifest.dump.packs` | manifest | STORE | `parityDump` | 5 | 5 | 14 | - | yes |
| `manifest.player-sheets` | manifest | STORE | `playerRender` | 2 | 2 | 104 | - | yes |
| `manifest.fluid` | manifest | STORE | `fluidRenderer` | 2 | 2 | 12 | - | yes |
| `manifest.portal` | manifest | STORE | `portalRenderer` | 2 | 2 | 12 | - | yes |
| `manifest.tooling-tables` | manifest | STORE | `entityModels` | 2 | 2 | 10 | - | yes |
| `digest.shipped-tables` | digest-set | STORE | `test` | 1 | 2 | 10 | - | yes |
| `digest.colormap-lut` | digest-set | STORE | `slowTest` | 1 | 2 | 3 | - | yes |
| `digest.dump-sections` | - | EXTERNAL | - | - | - | - | - | - |
| `pin.vanilla-iso-pose` | pin-set | STORE | `test` | 1 | 2 | 1 | - | yes |
| `pin.kit-corners` | pin-set | STORE | `test` | 1 | 2 | 1 | - | yes |
| `pin.corpus-count` | pin-set | STORE | `test` | 1 | 2 | 2 | - | yes |
| `pin.player-crc` | pin-set | STORE | `slowTest` | 1 | 2 | 3 | - | yes |
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
| `report.canvas-mismatch` | - | POINTER | - | - | - | - | - | - |
| `report.wall-time` | - | POINTER | - | - | - | - | - | - |
| `report.worst-list` | - | POINTER | - | - | - | - | - | - |
| `report.failure-rows` | - | POINTER | - | - | - | - | - | - |
| `report.panel-stats` | - | POINTER | - | - | - | - | - | - |
| `report.glint-frames` | - | POINTER | - | - | - | - | - | - |
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
| `atlas`, `diagnoseAtlas`, `diagnoseAtlasTask10` | `AtlasRenderer` dispatches its tiles on `parallelStream` by design, so two runs place the same sprites at different offsets and the output can never be hashed (blindness rule B15). A must-not-crash smoke check. |
| `javadoc` | RED at HEAD with about twenty pre-existing errors, seventeen of them Lombok-generated builders an annotation processor produces and javadoc cannot see. Its exit code carries no information. |
| `jmh` | Benchmark scores, not rendered bytes. `jmh-regression-gate` is the separate skill that compares them. |
| `bedParity`, `packOverlay`, `redstoneTints`, `stackCountBadge`, `loreTooltip` cells | Authoring and version-bump tools. What they write either has no oracle or is already covered by `manifest.visual`. |
| `renderVanillaReferences` and the three narrow harness runs | Preconditions rather than gates: they produce the ground truth every sweep is measured against. Only `renderVanillaAllReferences` refreshes the whole tree, which is why it is the one `manifest.references` names. |
