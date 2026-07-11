# Phase 0 — byte-parity baseline capture

> Resourcepack rebuild, phase 0 (`notes/resourcepack/IMPLEMENTATION_PLAN.md` §"Phase 0"). This is
> the enforcement oracle for every later phase's parity gate. Read-only capture — no render-path
> source changed.

- **Branch:** `feat/resourcepack`, off `feat/pipeline2` after the fixture fix (see below).
- **Base commit:** `e2b0e95` (= `a06a855` + the sha256-fixture re-sync).
- **Captured:** 2026-07-11.
- **Committed oracle:** `parity-baseline/p0.sha256` (this dir, tracked). Working copy lives at the
  gitignored `cache/parity-baseline/p0.sha256`; later phases regenerate `cache/parity-baseline/pN.sha256`
  and `diff` against p0 — restore `cache/parity-baseline/p0.sha256` from this committed copy first if
  `cache/` was cleaned.

## Entry gate (decisions 13 + 16)

Satisfied per `notes/resourcepack/DECISIONS.md` — all 16 decisions recorded; 13 (sprite chrome +
padding 4) and 16 (pre-registered parity bundle) signed off. Decision 9 was overridden to
frame-0-at-default (see the expected-movers note at the end).

## Green gates

| Gate | Result |
|---|---|
| `./gradlew test` (fast suite) | **GREEN** — 322 tests (after the fixture fix below; was red at `a06a855`) |
| `./gradlew slowTest` (network/cache integration) | **GREEN** — BUILD SUCCESSFUL 1m31s |

### Pre-req fixture fix (committed on `feat/pipeline2`, not this branch)

`a06a855` rewrote the `"//"` provenance headers in the 9 bundled resource JSONs
(`tooling2.X` → `tooling.X`) but did not re-sync the `ResourceShaTest` `.sha256` fixtures. Because
the test canonicalises the whole JSON tree (the `"//"` key included) before hashing, all 9 canonical
hashes shifted and the fast suite went red at HEAD. Verified purely cosmetic: **0 non-header lines
changed** across all 9 resources since the last fixture sync (`fde929d`) — loaded data and render
output are byte-identical. Fixed by pasting the actual canonical hashes into the fixtures (the test's
own documented regen workflow):

- **Commit `e2b0e95` on `feat/pipeline2`** — `test(pipeline): re-sync bundled JSON sha256 fixtures
  after a06a855 header fix`. `feat/resourcepack` is branched off it. Cherry-pickable to `master` if
  `feat/pipeline2` merges first.

## Sweep set + per-sweep output counts

Command per run (twice, cache/visual + build/atlas cleaned before each):

```bash
./gradlew atlas blockRender3D itemRender2D entityParityVanilla \
          fluidRenderer portalRenderer loreTooltip playerRender --continue
```

| Sweep | Output dir | Files | In oracle? |
|---|---|---|---|
| `atlas` | `build/atlas/` | 1 png (3673 tiles, 2048×29440) | **NO** — parallel-by-design, smoke-only (see below) |
| `blockRender3D` (default `BLOCK_TEST_2`) | `cache/visual/block-render-3d/` | 35 png (5 blocks × 7) | yes |
| `itemRender2D` (default `ITEM_TEST_1`) | `cache/visual/item-render-2d/` | 7 png | yes |
| `entityParityVanilla` | `cache/visual/entity-parity-vanilla/` | 496 png (124 entities × 4) | yes |
| `fluidRenderer` | `cache/visual/fluid-renderer/` | 10 png + 2 gif | yes |
| `portalRenderer` | `cache/visual/portal-renderer/` | 4 png + 4 webp | yes |
| `loreTooltip` | `cache/visual/lore-tooltip/` | 1 png + 1 gif + 3 webp | yes |
| `playerRender` | `cache/visual/player-render/` | 99 png + 10 gif | yes |

`test`/`slowTest` are boolean green-gates and produce no oracle bytes, so they ran once (not twice).
`blockRender3D`/`itemRender2D` with no `-P` id render fixed static id lists (not "all" — `atlas`
carries the full ~3673-tile block+item coverage; `entityParityVanilla` the full 124-entity set;
`playerRender` the full option matrix).

## The oracle manifest

- **Capture command (pin this exact form for every later phase):**
  ```bash
  find cache/visual -type f \( -name '*.png' -o -name '*.gif' -o -name '*.webp' \) \
    | sort | xargs sha256sum > cache/parity-baseline/pN.sha256
  ```
  Extends the plan's `-name '*.png'` to also cover the animated frame data (`*.gif`, `*.webp`) the
  plan calls for. Excludes `build/atlas/` (see atlas note), the entity `parity-report.tsv` (carries
  non-deterministic render timings), and `atlas.json` (metadata, not render bytes).
- **Oracle file count:** 672 (png + gif + webp under `cache/visual/`).
- **Manifest SHA-256 (`p0.sha256`):** `b67ec15470b8ddc9e42e6bc810679e50c3b43e91c5c6434dd38d9397f4040d25`

### Reproducibility (the gate: run the sweep set twice, hashes stable)

The sweep set ran twice, cleaned between runs. The two manifests differed on **exactly one file** —
`build/atlas/atlas.png` — and were byte-identical on all **672** `cache/visual` outputs. The
on-disk capture equals both run #1 and run #2 (minus atlas):

```
p0 (on-disk) == run1 (no atlas) == run2 (no atlas)   [672 lines, all identical]
```

`atlas.png` flaps intermittently between JVM invocations because `AtlasRenderer.render{Blocks,Items}`
dispatches tile renders on a `parallelStream` **by design** (shaves build time). Its tiles are
outputs of the block/item/fluid/portal renderers, which ARE byte-stable in this manifest, so the
atlas adds no parity signal — it is a "must not fail" smoke check only (ran clean exit-0 across both
sweeps + 15 standalone invocations). Byte-exactness of the atlas is explicitly **not** gated.

## Pin item 1 — colormap byte-match probe (02 D10)

Reference for the phase-2 check "stack-resolved colormap PNG sampling ≡ bundled `color_maps.json`
LUT". Decoded from `src/main/resources/lib/minecraft/renderer/color_maps.json` (raw big-endian ARGB,
256×256, row-major). The `sha256(rawARGB)` is the definitive byte-match key; the sample pixels are a
human-readable cross-check. Sample points use the vanilla lookup `x=(1−clampT)·255`,
`y=(1−clampT·clampR)·255`.

| Map | dims | sha256(raw ARGB bytes) |
|---|---|---|
| GRASS | 256×256 | `99ac9a2db44c6ed14da168bad2f66001535fd8b6290a2255bc8aa251d16afcc4` |
| FOLIAGE | 256×256 | `64c43c6b59f7da4ae1c8f56a332c6e21a6d0789dd0272c2cc32c809bc2e0da50` |
| DRY_FOLIAGE | 256×256 | `04fe97199d0400e161c1413077735b8dff765d86999890d76953681bee86708f` |

Sample ARGB (0xAARRGGBB) at fixed points:

| point (x,y) | GRASS | FOLIAGE | DRY_FOLIAGE |
|---|---|---|---|
| 0,0 | `FF47CD33` | `FF1ABF00` | `FFA35F46` |
| 255,0 | `FFFFFFFF` | `FF749A3E` | `FF8D6A33` |
| 0,255 | `FFBFB755` | `FFAEA42A` | `FFA38046` |
| 255,255 | `FF80B497` | `FF60A17B` | `FF8F7A5A` |
| 128,128 | `FF7CBD6C` | `FF5BAB47` | `FFA37146` |
| plains 51,173 (0.8,0.4) | `FF91BD59` | `FF77AB2F` | `FFA37546` |
| jungle 12,36 (0.95,0.9) | `FF59C93C` | `FF30BB0B` | `FFA36346` |
| snowy 255,255 (0.0,0.5) | `FF80B497` | `FF60A17B` | `FF8F7A5A` |
| desert 0,255 (2.0,0.0) | `FFBFB755` | `FFAEA42A` | `FFA38046` |

Sanity: plains grass `0x91BD59` is the canonical vanilla plains grass colour. ✔

## Pin item 2 — sidecar same-pack binding (02 D4)

**Baseline behaviour (record, don't change):** a texture's `.mcmeta` sidecar binds to the pack+root
that supplied the winning PNG — vanilla-exact. A higher-priority pack that overrides only the PNG
(no sidecar) drops the lower pack's animation rather than inheriting it. The current scanner already
does this via the sibling-`.mcmeta` read in `TexturePackLoader` (confirmed live at HEAD). Phase 2
keeps it deliberately and pins it with a test; phase 0 only records that it is the baseline.

## Pin item 3 — latent behaviours that are BASELINE (01 parity ledger)

These look like bugs but are the intended baseline bytes; later fixes are diffs by design, not
regressions:

- **Item animated-strip squash** — an animated item texture would blit the whole vertical strip
  squashed. Never triggered by vanilla 26.1 (no `textures/item` mcmetas). Phase-4 decision-10 is the
  pre-registered fix.
- **CTM non-application** — connected-texture rules never render (`resolveCtm` has zero render-path
  callers). Stays parse-and-store; CTM renders nothing by mandate.
- **Tooltip procedural chrome** — the tooltip is drawn 100% procedurally and pack-blind, including
  square (vs notched) bg corners, filled (vs open) ring corners, and padding 5 (vs vanilla 4).
  Phase-8 decision-13 is the pre-registered sprite-chrome flip.

## Expected movers at phase 4 (decision 9 override)

Decision 9 (frame-0-at-default, overriding the raw-strip recommendation) means several entity/BE
parity captures in this p0 baseline are **pre-registered to move** when phase 4 lands: conduit
(`wind.png.mcmeta`) and the raw-strip-blocked entities/BEs re-baseline to frame-0 sampling, paired
with a vanilla-reference-harness frame-pinning repair + reference re-render. So a phase-4 diff of the
entity sweep against p0 that touches exactly those subjects is expected, not a gate failure. p0
captures TODAY's bytes (raw-strip included) so the move is measurable.

## Gate criteria — all met

- [x] Fast suite green at HEAD (`test`) — after the `e2b0e95` fixture fix.
- [x] Network/cache integration green (`slowTest`).
- [x] Full sweep set captured; manifest written (`p0.sha256`, 672 files).
- [x] Manifest reproducible — sweep set run twice, oracle byte-identical across both runs
      (only the by-design-parallel `atlas.png` differed and is excluded).
- [x] Colormap byte-match probe recorded (02 D10).
- [x] Sidecar-binding baseline recorded (02 D4).
- [x] Latent-behaviour baselines recorded (01 ledger).
- [x] Committed oracle copy on the branch (survives `cache/` cleans); tag `resourcepack-p0-green`.
