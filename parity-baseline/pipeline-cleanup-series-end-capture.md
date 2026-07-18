# pipeline-cleanup series-end — loaded-surface reconciliation

> The one-time full-evidence closure for the pipeline-cleanup series
> (`notes/pipeline-cleanup/IMPLEMENTATION_PLAN.md` §"P23"). Every earlier phase gated on the cheap
> loaded-surface dump (`parityDump`) plus, for the render-touching phases, a spot render. This is the
> single reconciliation that pays the full slow + visual + JMH evidence once for the whole series.

- **Branch:** `feat/pipeline-cleanup`, off `master`.
- **Phase-0 base commit:** `7164a2b3` (oracle `parity-baseline/pipeline-cleanup-p0-{vanilla,packs}.sha256`).
- **Series-end commit:** `1cbe8b4d` (P22 — the last code phase).
- **Captured:** 2026-07-17.
- **Committed oracle:** `parity-baseline/pipeline-cleanup-series-end-{vanilla,packs}.sha256` (this dir, tracked).
  Working copies live at the gitignored `cache/parity-dump/head/{vanilla,packs}/manifest.sha256`.

## Manifest digests

| | vanilla | packs |
|---|---|---|
| Phase-0 oracle | `8471186563d0711034b36253d9a0f2ba6524043742884cc774576464f2064129` | `06af2411cd36cc94d07587e0fa7211ac19dae22007d642f2393c764af2985015` |
| Series-end oracle | `4dc3b5162b410ab1f702efed77942917b7e15ca840a457d0ceb85fb731f17866` | `05225769a459b41b5362b0929a09daa50761627855038d68a20e9f34b7dfccde` |

## Step 1 - dump delta vs phase-0 == the registered expected-diff manifests, exactly

`diff -r cache/parity-dump/0d2c88f8 cache/parity-dump/head` differs, in BOTH configs, in exactly three
serialized sections (plus the `manifest.sha256` that indexes them). Every other section is byte-identical
to phase 0.

| Section | Change | Registered by | Vanilla | Packs |
|---|---|---|---|---|
| `textures.json` | drops `size` (removal-only) | P10 (IndexedTexture→ResolvedTexture) | 3664 entries | 6042 entries |
| `blocks.json` | adds `icon_gui` (additive) | P14 (bake Block.iconGui) | 1153 blocks | 1192 blocks |
| `entities.json` | adds `members` (additive) | P16 (canvas-group members) | 13 grouped | 13 grouped |

Nothing else moved. The two render-touching bridge fixes of this run (P20 `ModelData.textures` →
`Map<ModelTexture>`, P21 typed `Block.Multipart.When`) were designed against the P0 dump's already-final
form, so both are dump-neutral; the style pass (P22) is bytecode-equivalent. This satisfies P23's strict
form: the series-end dump differs from phase 0 by exactly the union of the pre-registered manifests.

## Step 2 - slow + visual evidence (once, at the series-end commit)

| Gate | Result |
|---|---|
| `./gradlew compileJava test` (fast suite) | **GREEN** — exit 0 |
| `./gradlew slowTest` (network/integration/parallelism/block-entity parity) | **GREEN** — exit 0 |
| `./gradlew entityParityVanilla` (124 subjects) | **CLEAN** — 123/124 < 1.0; only `mooshroom_brown` 31.47 (pre-existing red-vs-brown mushroom-overlay bug) |
| `./gradlew blockParityVanilla` (1055 subjects) | **CLEAN** — 1051/1055 < 1.0; only the 4 pre-existing ≥1.0 (3 amethyst buds + enchanting_table BE-composition), byte-identical values to pre-series |
| `itemRender2D minecraft:compass` | natural (needle + ring sprite; item-tree dispatch) |
| `blockRender3D minecraft:tnt` | natural (textured TNT block, iso pose) |

No subject regressed. The full ≥1.0 set (entities: mooshroom_brown; blocks: large/medium/small amethyst
bud, enchanting_table) is exactly the pre-series set at unchanged deltas — all cross-shape / BE-composition
divergences tracked independently (followup1 R20).

## Step 3 - JMH baseline

`TexturePackLoadBenchmark` re-captured as a NEW baseline (never comparable across the P8
`Pipeline`→`ClientAcquisition` rename). Full session output at `.jmh/pipeline-cleanup-series-end-texturepackload.txt`
(gitignored scratch). Settings: warmup 3, iters 5, forks 2, `Mode.AverageTime`.

- `TexturePackLoadBenchmark.coldLoad`: **10343.1 ± 507.6 ms/op** (avgt, 10 samples = 2 forks × 5 iters).

This is a reference point for future perf work, not a gate.

## Series phases reconciled here (BM-demoted per-phase evidence)

Each of these landed with only a spot render as per-phase visual evidence; the full sweep above is their
series-end reconciliation: **P1, P5, P7, P11, P14, P15, P16, P17, P20, P21**. All fold into the clean
sweep — no accumulated drift.

## Restore recipe

```bash
git checkout 1cbe8b4d
./gradlew parityDump -Plabel=head -q
diff cache/parity-dump/head/vanilla/manifest.sha256 parity-baseline/pipeline-cleanup-series-end-vanilla.sha256
diff cache/parity-dump/head/packs/manifest.sha256   parity-baseline/pipeline-cleanup-series-end-packs.sha256
```

## Gate criteria - all met

- [x] Step-1 dump delta vs phase 0 == union of registered manifests (P10 size-drop, P14 icon_gui, P16 members), both configs.
- [x] Fast suite green at the series-end commit.
- [x] `slowTest` green.
- [x] Entity + block sweeps show no unexplained regression (only the pre-existing ≥1.0 set, unchanged).
- [x] Spot renders (compass, tnt) natural.
- [x] JMH `TexturePackLoadBenchmark` baseline re-captured.
- [x] Series-end oracle copies committed beside the phase-0 baselines (survive `cache/` cleans).
