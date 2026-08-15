# Parity store

Generated from `index.json`. **Do not edit** - regenerate with:

```
./gradlew test --tests "*ParityViewsTest" -Dasset.parity.regenerateViews=true --rerun
```

Every artifact this store knows about is below. An artifact that is not `baselined`
has no last known value yet, so a comparison against it answers `MISSING_BASELINE`
rather than passing.

## Artifacts

Values this store holds, one file each.

| artifact | file | entries | headline | promoted at | baselined |
|---|---|---:|---|---|---|
| `digest.colormap-lut` | `digests/colormap-lut.json` | 3 | 3 entries | `b4d6e5bbe256c9912a9850a6f902fd61f3bbd92e` | yes |
| `digest.shipped-tables` | `digests/shipped-tables.json` | 10 | 10 entries | `b4d6e5bbe256c9912a9850a6f902fd61f3bbd92e` | yes |
| `manifest.dump.packs` | `manifests/dump-packs.json` | 14 | 14 entries | `4440fde0ba8af73b02b159814fbd36ba3b73dcce` | yes |
| `manifest.dump.vanilla` | `manifests/dump-vanilla.json` | 14 | 14 entries | `4440fde0ba8af73b02b159814fbd36ba3b73dcce` | yes |
| `manifest.fluid` | `manifests/fluid.json` | 12 | 12 entries | `4440fde0ba8af73b02b159814fbd36ba3b73dcce` | yes |
| `manifest.player-raw` | `manifests/player-raw.json` | 18 | 18 entries | `65a62ff78ae8e916c1fb25527df23a6c32288c02` | yes |
| `manifest.player-sheets` | `manifests/player-sheets.json` | 104 | 104 entries | `65a62ff78ae8e916c1fb25527df23a6c32288c02` | yes |
| `manifest.portal` | `manifests/portal.json` | 12 | 12 entries | `4440fde0ba8af73b02b159814fbd36ba3b73dcce` | yes |
| `manifest.references` | `manifests/references.json` | 2322 | 2322 entries | `494a152fd3bf76bc2290aada4291024e0e49791e` | yes |
| `manifest.tooling-tables` | `manifests/tooling-tables.json` | 10 | 10 entries | `0bcb42335c9f472169445099c348bceaf2b92e58` | yes |
| `manifest.visual` | `manifests/visual.json` | 210 | 210 entries | `f7aa87338a8bc20907a03f0db6cba69c7a22cbdc` | yes |
| `pin.block-crc` | `pins/block-crc.json` | 3 | 3 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.corpus-count` | `pins/corpus-count.json` | 4 | 4 entries | `6db0412c6e1274b594cf85015af4175fbd5b474c` | yes |
| `pin.fluid-crc` | `pins/fluid-crc.json` | 13 | 13 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.kit-corners` | `pins/kit-corners.json` | 1 | 1 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.player-crc` | `pins/player-crc.json` | 3 | 3 entries | `23fdf579e8e9da8054f22dc3f4a5ac09d032da8c` | yes |
| `pin.portal-crc` | `pins/portal-crc.json` | 2 | 2 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `pin.vanilla-iso-pose` | `pins/vanilla-iso-pose.json` | 1 | 1 entries | `00a006307ac6443e243d30caf238849e20cd9c60` | yes |
| `report.oracle-index` | `index.json` | - | - | - | **no** |
| `roster.blindness-rules` | `blindness.json` | - | - | - | **no** |
| `sweep.armor` | `sweeps/armor.json` | 7 | sum 18.2471 | `ffa8a7d1fd7d03ade3fde5d62d4ea3a1df823055` | yes |
| `sweep.block` | `sweeps/block.json` | 1055 | sum 131.5270 | `ffa8a7d1fd7d03ade3fde5d62d4ea3a1df823055` | yes |
| `sweep.entity` | `sweeps/entity.json` | 402 | sum 60.0047 | `ffa8a7d1fd7d03ade3fde5d62d4ea3a1df823055` | yes |
| `sweep.glint` | `sweeps/glint.json` | 11 | sum 528.0484 | `65a62ff78ae8e916c1fb25527df23a6c32288c02` | yes |
| `sweep.item` | `sweeps/item.json` | 479 | sum 128.9575 | `ffa8a7d1fd7d03ade3fde5d62d4ea3a1df823055` | yes |
| `sweep.menu` | `sweeps/menu.json` | 10 | sum 0.0407 | `f7aa87338a8bc20907a03f0db6cba69c7a22cbdc` | yes |
| `sweep.player` | `sweeps/player.json` | 2 | sum 8.4315 | `65a62ff78ae8e916c1fb25527df23a6c32288c02` | yes |

## Pointers

Artifacts that are a field of another artifact's file. A sum that is the sum of the
column beside it is one answer, so it is stored once and pointed at.

| artifact | pointer |
|---|---|
| `report.buckets` | `sweeps/<sweep>.json#/summary/buckets` |
| `report.coverage-gaps` | `sweeps/<sweep>.json#/summary/gaps` |
| `report.diagnostics-log` | `manifests/tooling-tables.json#/logs` |
| `report.failure-rows` | `sweeps/<sweep>.json#/rows/<n>/status` |
| `report.harness-sweep-counts` | `<artifact>#/provenance/counts` |
| `report.run-provenance` | `<artifact>#/provenance` |
| `report.sum` | `sweeps/<sweep>.json#/summary/sum` |
| `report.wall-time` | `<artifact>#/provenance/wall_time_ms` |
| `report.worst-list` | `sweeps/<sweep>.json#/summary/worst` |

## External

Artifacts this store does not hold, and where each one does live. Recorded so a
citation by path is never the only record that something exists.

| artifact | home | reason |
|---|---|---|
| `digest.dump-sections` | `cache/parity-dump/<label>/{vanilla,packs}/` | the same 28 values manifest.dump.vanilla and manifest.dump.packs already carry, one per section file; a third copy of one number |
| `pin.tick-lattice` | `lib.minecraft.renderer.engine.compose.TimelineTest` | an identity (millisAt(f) == tickAt(f) * 50.0), not a captured value - re-baselining is not a concept for it |
| `probe.depth-quantum` | `cache/asset-renderer/vanilla/<version>/depth-quantum-probe/` | written deliberately outside the reference tree by renderVanillaDepthQuantumProbe |
| `probe.pixel` | `untracked working notes` | instrumented-build evidence; not reproducible by re-running a gate, so it can be neither captured, compared nor promoted, and what it was written into is gitignored rather than tracked, so no path here would resolve for anyone who clones this |
| `report.capture-note` | `commit messages` | its one non-duplicated field is the promotion reason, which is a provenance key; its restore recipe is parityCompare, which is code rather than prose |
| `report.expected-diff` | `<working root>/_run/expected-diff.json` | a pre-registered input to one gate, written before the capture it gates |
| `report.harness-fit-log` | `build/parity/harness-<task>.log` | the harness prints one fit line per cohort to stdout; the tee under build/ is where it is persisted, and it is scratch rather than a promoted value |
| `report.movers` | `<working root>/_run/compare.json` | a statement about a comparison rather than a value a run produces, so it is never promoted |
| `report.plan` | `<working root>/_run/plan.json` | what a changed path sees and is blind to; capture-only, never promoted |

## Sources

Rosters and pins held as a deliberate second copy in Java, so a change to the first
copy fails loudly. Externalising one would defeat exactly that, so the store records
where it lives and how to re-derive it, and carries no value.

| artifact | home | re-derive |
|---|---|---|
| `pin.armor-span` | `lib.minecraft.renderer.engine.kit.ArmorKitTest` | inspect the built shell's vertical span; the trim-triple assertion beside it is the only coverage of the item-icon half of the trim permutation |
| `roster.appearance-axes` | `lib.minecraft.renderer.parity.AppearanceKey` | AppearanceKey.Axis against the harness TraitAxis; asset is a strict superset |
| `roster.armor-subjects` | `lib.minecraft.renderer.visual.TestArmorParityVanilla` | must match the harness ArmorSweep roster byte for byte or the sweep finds no reference |
| `roster.dump-sections` | `lib.minecraft.renderer.pipeline.dump.PipelineParityDump` | PipelineParityDump's section insertion order; the values are the keys of manifests/dump-*.json, so a drift is already gated |
| `roster.face-phase` | `lib.minecraft.renderer.face.CornerPhaseTest` | vanilla's own corner order per face, read off the bakery and the polygon paths |
| `roster.frame-turn` | `lib.minecraft.renderer.face.TurnTest` | the order-8 diagonal group; each constant is which axes it negates |
| `roster.glint-subjects` | `lib.minecraft.renderer.visual.TestGlintParityVanilla` | the 7 always-foil GUI items plus the 4 worn-leather diagnostics the harness GlintSweep renders |
| `roster.humanoid-armor` | `lib.minecraft.renderer.pipeline.loader.EntityModelLoaderArmorRosterTest` | EntityModelLoader.load() filtered on humanoidArmor().isPresent() |
| `roster.humanoid-part-crop` | `lib.minecraft.renderer.face.HumanoidPartCropTest` | Unwrap.Atlas.rect at each part's atlas origin under Turn.HALF_X |
| `roster.overlay-pipeline` | `lib.minecraft.renderer.pipeline.loader.EntityModelLoaderOverlayPassTest` | EntityPipelineTraits over the extracted client jar's layer classes |
| `roster.pack-fixtures` | `lib.minecraft.renderer.pipeline.dump.PipelineParityDump` | PipelineParityDump's PACK_FIXTURES, which the dump throws on a missing member of; no second copy of the set is stored anywhere |
| `roster.player-scopes` | `lib.minecraft.renderer.visual.TestPlayerParityVanilla` | the scopes the harness PlayerSweep renders |
| `roster.sheet-groups` | `lib.minecraft.renderer.visual.TestPlayerRender` | the -Psheets groups TestPlayerRender accepts; ten offline plus the network-only account group |
