# Parity store

Generated from `index.json`. **Do not edit** - regenerate with:

```
./gradlew :test --tests "*ParityViewsTest" -Dasset.parity.regenerateViews=true --rerun
```

Every artifact this store knows about is below. An artifact that is not `baselined`
has no last known value yet, so a comparison against it answers `MISSING_BASELINE`
rather than passing.

## Artifacts

Values this store holds, one file each.

| artifact | file | entries | headline | promoted at | baselined |
|---|---|---:|---|---|---|
| `manifest.dump.packs` | `manifests/dump-packs.json` | 14 | 14 entries | `72dc3d6238acfe81ac9d3134c246914bcfba8082` | yes |
| `manifest.dump.vanilla` | `manifests/dump-vanilla.json` | 14 | 14 entries | `72dc3d6238acfe81ac9d3134c246914bcfba8082` | yes |
| `manifest.fluid` | `manifests/fluid.json` | 9 | 9 entries | `336520d912aebf292671cc1472c937d24d727aa4` | yes |
| `manifest.pack-tooltips` | `manifests/pack-tooltips.json` | 44 | 44 entries | `5f2174f3c4e0f23707ff31931da0659c7b298b4e` | yes |
| `manifest.player-raw` | `manifests/player-raw.json` | 9 | 9 entries | `336520d912aebf292671cc1472c937d24d727aa4` | yes |
| `manifest.player-sheets` | `manifests/player-sheets.json` | 97 | 97 entries | `336520d912aebf292671cc1472c937d24d727aa4` | yes |
| `manifest.portal` | `manifests/portal.json` | 6 | 6 entries | `d99fbb39861a7ecb8af36502129de262820a7977` | yes |
| `manifest.references` | `manifests/references.json` | 9846 | 9846 entries | `f3a33d46ce971755f1ab77d932a28f65a527ecdb` | yes |
| `manifest.tooling-tables` | `manifests/tooling-tables.json` | 11 | 11 entries | `40befed4310d346ac870f99f049f1576ac401274` | yes |
| `manifest.visual` | `manifests/visual.json` | 169 | 169 entries | `336520d912aebf292671cc1472c937d24d727aa4` | yes |
| `pin.block-crc` | `pins/block-crc.json` | 3 | 3 entries | `cf0d4c89508be445db9503ec9fc6b59aeb71340d` | yes |
| `pin.fluid-crc` | `pins/fluid-crc.json` | 13 | 13 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.player-crc` | `pins/player-crc.json` | 3 | 3 entries | `6aaf4c2d2f42e30267c80c9d7bcb7cec2beb64de` | yes |
| `pin.portal-crc` | `pins/portal-crc.json` | 2 | 2 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.vanilla-iso-pose` | `pins/vanilla-iso-pose.json` | 1 | 1 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `report.oracle-index` | `index.json` | - | - | - | **no** |
| `roster.blindness-rules` | `blindness.json` | - | - | - | **no** |
| `sweep.armor` | `sweeps/armor.json` | 7 | sum 17.7355 | `4756faf3a8c3dd8502d19c00bd4cb207901b3ab7` | yes |
| `sweep.block` | `sweeps/block.json` | 1063 | sum 7.6024 | `addedec7a23b08d02a4f18bc83d435010e342988` | yes |
| `sweep.book` | `sweeps/book.json` | 19 | sum 0.0000 | `7d5af558e13726ff73f2f588a98217b8f4134a6a` | yes |
| `sweep.entity` | `sweeps/entity.json` | 406 | sum 10.9966 | `4756faf3a8c3dd8502d19c00bd4cb207901b3ab7` | yes |
| `sweep.entity-animation` | `sweeps/entity-animation.json` | 134 | sum 1.8479 | `4756faf3a8c3dd8502d19c00bd4cb207901b3ab7` | yes |
| `sweep.entity-walk` | `sweeps/entity-walk.json` | 134 | sum 1.8886 | `4756faf3a8c3dd8502d19c00bd4cb207901b3ab7` | yes |
| `sweep.glint` | `sweeps/glint.json` | 11 | sum 527.7353 | `6aaf4c2d2f42e30267c80c9d7bcb7cec2beb64de` | yes |
| `sweep.item` | `sweeps/item.json` | 479 | sum 85.0398 | `181f4ab9b8180f98fafc72869bb9232a390fb1cc` | yes |
| `sweep.menu` | `sweeps/menu.json` | 15 | sum 0.1182 | `560a68449662f6fefb9713bcd6cd6d712a38c8d3` | yes |
| `sweep.player` | `sweeps/player.json` | 2 | sum 8.3792 | `cf0d4c89508be445db9503ec9fc6b59aeb71340d` | yes |
| `sweep.tooltip` | `sweeps/tooltip.json` | 76 | sum 0.3274 | `4264daab8a17cbdd2dce01193c5d0838d58d75a8` | yes |
| `sweep.tooltip-hypixel-skyblock` | `sweeps/tooltip-hypixel-skyblock.json` | 44 | sum 0.0000 | `33935094db15adf57139f1790ec28fa311dfa634` | yes |

## Pointers

Artifacts that are a field of another artifact's file. A sum that is the sum of the
column beside it is one answer, so it is stored once and pointed at.

| artifact | pointer |
|---|---|
| `report.buckets` | `sweeps/<sweep>.json#/summary/buckets` |
| `report.diagnostics-log` | `manifests/tooling-tables.json#/logs` |
| `report.failure-rows` | `sweeps/<sweep>.json#/rows/<n>/status` |
| `report.harness-sweep-counts` | `<artifact>#/provenance/counts` |
| `report.run-provenance` | `<artifact>#/provenance` |
| `report.sum` | `sweeps/<sweep>.json#/summary/sum` |
| `report.wall-time` | `<artifact>#/provenance/wall_time_ms` |

## External

Artifacts this store does not hold, and where each one does live. Recorded so a
citation by path is never the only record that something exists.

| artifact | home | reason |
|---|---|---|
| `probe.pack-icons` | `cache/asset-renderer/vanilla/<version>/pack-icons/` | written deliberately outside the reference tree by renderVanillaPackIcons over the roster packIconRoster writes beside it, and read by packIconParityVanilla, a diagnostic report rather than a gate |
| `probe.pack-sources` | `cache/asset-renderer/vanilla/<version>/pack-sources/` | written deliberately outside the reference tree by renderVanillaPackSources, and read by packSourceParityVanilla, a diagnostic report rather than a gate |
| `probe.pixel` | `untracked working notes` | instrumented-build evidence; not reproducible by re-running a gate, so it can be neither captured, compared nor promoted, and what it was written into is gitignored rather than tracked, so no path here would resolve for anyone who clones this |
| `report.expected-diff` | `<working root>/_run/expected-diff.json` | a pre-registered input to one gate, written before the capture it gates |
| `report.harness-fit-log` | `build/parity/harness-<task>.log` | the harness prints one fit line per cohort to stdout; the tee under build/ is where it is persisted, and it is scratch rather than a promoted value |
| `report.movers` | `<working root>/_run/compare.json` | a statement about a comparison rather than a value a run produces, so it is never promoted |
| `report.plan` | `<working root>/_run/plan.json` | what a changed path sees and is blind to; capture-only, never promoted |

## Sources

Pins held as a deliberate second copy in Java, so a change to the first copy fails
loudly. Externalising one would defeat exactly that, so the store records where it
lives and how to re-derive it, and carries no value.

| artifact | home | re-derive |
|---|---|---|
| `pin.armor-span` | `lib.minecraft.renderer.bake.armor.ArmorKitTest` | inspect the built shell's vertical span; the trim-triple assertion beside it is the only coverage of the item-icon half of the trim permutation |
