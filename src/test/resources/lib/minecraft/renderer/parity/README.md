# Parity store

Generated from `index.json`. **Do not edit** - regenerate with:

```
./gradlew test --tests "*ParityViewsTest" -Dasset.parity.regenerateViews=true
```

Every artifact this store knows about is below. An artifact that is not `baselined`
has no last known value yet, so a comparison against it answers `MISSING_BASELINE`
rather than passing.

## Artifacts

Values this store holds, one file each.

| artifact | file | entries | headline | promoted at | baselined |
|---|---|---:|---|---|---|
| `digest.colormap-lut` | `digests/colormap-lut.json` | - | - | - | **no** |
| `digest.shipped-tables` | `digests/shipped-tables.json` | - | - | - | **no** |
| `manifest.dump.packs` | `manifests/dump-packs.json` | - | - | - | **no** |
| `manifest.dump.vanilla` | `manifests/dump-vanilla.json` | - | - | - | **no** |
| `manifest.fluid` | `manifests/fluid.json` | - | - | - | **no** |
| `manifest.player-sheets` | `manifests/player-sheets.json` | - | - | - | **no** |
| `manifest.portal` | `manifests/portal.json` | - | - | - | **no** |
| `manifest.references` | `manifests/references.json` | - | - | - | **no** |
| `manifest.tooling-tables` | `manifests/tooling-tables.json` | - | - | - | **no** |
| `manifest.visual` | `manifests/visual.json` | - | - | - | **no** |
| `pin.block-crc` | `pins/block-crc.json` | - | - | - | **no** |
| `pin.corpus-count` | `pins/corpus-count.json` | - | - | - | **no** |
| `pin.fluid-crc` | `pins/fluid-crc.json` | - | - | - | **no** |
| `pin.kit-corners` | `pins/kit-corners.json` | - | - | - | **no** |
| `pin.player-crc` | `pins/player-crc.json` | - | - | - | **no** |
| `pin.portal-crc` | `pins/portal-crc.json` | - | - | - | **no** |
| `pin.vanilla-iso-pose` | `pins/vanilla-iso-pose.json` | - | - | - | **no** |
| `report.oracle-index` | `index.json` | - | - | - | **no** |
| `roster.blindness-rules` | `blindness.json` | - | - | - | **no** |
| `sweep.armor` | `sweeps/armor.json` | - | - | - | **no** |
| `sweep.block` | `sweeps/block.json` | - | - | - | **no** |
| `sweep.entity` | `sweeps/entity.json` | - | - | - | **no** |
| `sweep.glint` | `sweeps/glint.json` | - | - | - | **no** |
| `sweep.item` | `sweeps/item.json` | - | - | - | **no** |
| `sweep.player` | `sweeps/player.json` | - | - | - | **no** |

## Pointers

Artifacts that are a field of another artifact's file. A sum that is the sum of the
column beside it is one answer, so it is stored once and pointed at.

| artifact | pointer |
|---|---|
| `report.buckets` | `sweeps/<sweep>.json#/summary/buckets` |
| `report.canvas-mismatch` | `sweeps/<sweep>.json#/rows/<n>/canvas_mismatch` |
| `report.coverage-gaps` | `sweeps/<sweep>.json#/summary/gaps` |
| `report.diagnostics-log` | `manifests/tooling-tables.json#/logs` |
| `report.failure-rows` | `sweeps/<sweep>.json#/rows/<n>/status` |
| `report.glint-frames` | `sweeps/glint.json#/rows/<n>/frames_delta` |
| `report.harness-sweep-counts` | `<artifact>#/provenance/counts` |
| `report.panel-stats` | `sweeps/<sweep>.json#/rows/<n>/panel` |
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
| `pin.tick-lattice` | `lib.minecraft.renderer.option.TimelineTest` | an identity (millisAt(f) == tickAt(f) * 50.0), not a captured value - re-baselining is not a concept for it |
| `probe.depth-quantum` | `cache/asset-renderer/vanilla/<version>/depth-quantum-probe/` | written deliberately outside the reference tree by renderVanillaDepthQuantumProbe |
| `probe.pixel` | `notes/refharness-unify/probes/` | instrumented-build evidence; not reproducible by re-running a gate, so it can be neither captured, compared nor promoted |
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
| `pin.armor-span` | `lib.minecraft.renderer.engine.kit.ArmorKitCitCompositeTest` | inspect the built shell's vertical span; the trim-triple assertion beside it is the only coverage of the item-icon half of the trim permutation |
| `roster.appearance-axes` | `lib.minecraft.renderer.visual.AppearanceKey` | AppearanceKey.Axis against the harness TraitAxis; asset is a strict superset |
| `roster.armor-subjects` | `lib.minecraft.renderer.visual.TestArmorParityVanilla` | must match the harness ArmorSweep roster byte for byte or the sweep finds no reference |
| `roster.dump-sections` | `lib.minecraft.renderer.pipeline.dump.PipelineParityDump` | PipelineParityDump's section insertion order; the values are the keys of manifests/dump-*.json, so a drift is already gated |
| `roster.face-phase` | `lib.minecraft.renderer.face.FacePhaseTest` | vanilla's own corner order per face, read off the bakery and the polygon paths |
| `roster.frame-turn` | `lib.minecraft.renderer.face.FrameTurnTest` | the order-8 diagonal group; each constant is which axes it negates |
| `roster.glint-subjects` | `lib.minecraft.renderer.visual.TestGlintParityVanilla` | the 7 always-foil GUI items plus the 4 worn-leather diagnostics the harness GlintSweep renders |
| `roster.humanoid-armor` | `lib.minecraft.renderer.pipeline.loader.HumanoidArmorRosterTest` | EntityModelLoader.load() filtered on humanoidArmor().isPresent() |
| `roster.humanoid-part-crop` | `lib.minecraft.renderer.face.HumanoidPartCropTest` | Unwrap.Atlas.rect at each part's atlas origin under Turn.HALF_X |
| `roster.overlay-pipeline` | `lib.minecraft.renderer.pipeline.loader.OverlayPipelineRosterTest` | EntityPipelineTraits over the extracted client jar's layer classes |
| `roster.pack-fixtures` | `lib.minecraft.renderer.pipeline.dump.PipelineParityDump` | PipelineParityDump's PACK_FIXTURES, also a provenance field on the two dump manifests |
| `roster.player-scopes` | `lib.minecraft.renderer.visual.TestPlayerParityVanilla` | the scopes the harness PlayerSweep renders |
| `roster.sheet-groups` | `lib.minecraft.renderer.visual.TestPlayerRender` | the -Psheets groups TestPlayerRender accepts; ten offline plus the network-only account group |
