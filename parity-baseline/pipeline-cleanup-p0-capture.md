# pipeline-cleanup phase 0 — loaded-surface baseline capture

> Pipeline-cleanup series, phase 0 (`notes/pipeline-cleanup/IMPLEMENTATION_PLAN.md` §"Phase 0"). This is
> the enforcement oracle for every later phase's parity gate. Test-side + build only — no production
> source changed, so the capture is the loaded state at the base commit.

- **Branch:** `feat/pipeline-cleanup`, off `master`.
- **Base commit:** `7164a2b3` (`Merge pull request #10 from minecraft-library/feat/pixel-mask`).
- **Captured:** 2026-07-17.
- **Committed oracle:** `parity-baseline/pipeline-cleanup-p0-{vanilla,packs}.sha256` (this dir, tracked).
  Working copies live at the gitignored `cache/parity-dump/<label>/{vanilla,packs}/manifest.sha256`.

## What this oracle is, and why it is not a render sweep

Every prior rework gated on rendered pixels (minutes per capture, needs the harness references) or on
bundled-resource bytes (blind to loader logic). This series is dozens of load-side refactors, and
paying a render sweep per phase is the cost the series set out to avoid.

This gate sits between those two: it serializes the **fully-loaded object graph** — the renderer's
inputs. If those are byte-identical before and after a phase, and the phase's diff left the render path
alone, the phase cannot have moved a pixel. Both conditions are mechanical to check.

**Altitude is renderer-context, not `Pipeline.Result`.** Five loaders (`BlockModelLoader`,
`BlockIndexLoader`, `ItemIndexLoader`, `EntityModelLoader`, `PalettedPermutationLoader`) run inside
`PipelineRendererContext.of` and their outputs exist nowhere else — and they are precisely the loaders
this series rewrites. A Result-level dump would green-light breaking every one of them.

## Green gates

| Gate | Result |
|---|---|
| `./gradlew compileJava test` (fast suite) | **GREEN** — BUILD SUCCESSFUL |
| Determinism pre-flight (`det-a`…`det-d` + base, 5 JVMs) | **GREEN** — byte-identical, both configs |

## The capture

```bash
./gradlew parityDump -Plabel=7164a2b3 -q
```

| | vanilla | packs |
|---|---|---|
| Wall time | ~25 s for BOTH configs (one JVM) | |
| Dump size | 27 MB | 33 MB (59 MB total) |
| Manifest | `pipeline-cleanup-p0-vanilla.sha256` | `pipeline-cleanup-p0-packs.sha256` |
| sha256 of manifest | `8471186563d0711034b36253d9a0f2ba6524043742884cc774576464f2064129` | `06af2411cd36cc94d07587e0fa7211ac19dae22007d642f2393c764af2985015` |

Measured cost answers note 10's open question 1 (estimated 30–60 s): **~25 s per capture, both configs**.
A per-phase gate is therefore ~50 s (base is reused from the previous green commit's capture, so in
practice one capture + an instant `diff -r`), versus minutes for `slowTest` + the render sweeps.

**Configurations.** `vanilla` = `PipelineOptions.defaults()` (26.1). `packs` = vanilla +
`cache/asset-renderer/packs/{defrosted, hypixel-skyblock, eureka.cats.zip}`, resolving 4 packs
(vanilla → defrosted → hypixel-skyblock → eureka `.cats`). The packs config is what puts `RuleSet.merge`,
the colour-override channel, the PackId-deriver rungs, and the `.cats` container under the gate; it
prints SKIPPED loudly rather than passing silently when a fixture is missing.

### Sections (14)

`run`, `packs`, `textures`, `block-models`, `item-models`, `blocks`, `block-entities`, `entities`,
`items`, `trees`, `rules`, `misc`, `synthesis`, `probes`.

Row counts under `packs` (sanity — a deterministic dump of nothing would pass just as well):
entities **90**, blocks **2549**, items **4410**, block-models **2489**, trees **3424**,
probes.resolution **6042**, probes.icon_gui **1192**, probes.animation **110**, synthesis.sources **2**.
`id_order` matches the block/item index sizes exactly.

## Determinism — the pre-flight, and why it took five runs

The oracle's whole value rests on two runs of the same commit producing identical bytes. That is not
free: the loaded graph is spread across hash-ordered maps, per-JVM-salted immutable collections,
machine-absolute paths, and megabyte pixel blobs.

**Five independent JVM runs (five fresh salts), all byte-identical, both configs:**

```
det-a == det-b == det-c == det-d == 7164a2b3    (diff -r, empty)
```

Two runs would NOT have been evidence. `Map.copyOf`/`Set.copyOf` seed their iteration order from a
nanosecond-sampled salt at class-init, and the resulting flap is **intermittent** — an oracle can pass
twice and fail the third time. Five runs is the minimum that makes the claim worth writing down.

### Writer rules (each kills one census item)

1. **Every object's keys are sorted recursively on write** — one guarantee, so a caller cannot leak hash
   order by reflex.
2. **Arrays are NEVER reordered.** An array in this dump means the order is semantic. Corollary that is
   easy to get backwards: a map whose *iteration order* is semantic (`EntityModelData.bones`) **cannot**
   be an object — the writer would sort it — so it is emitted as an array of entries.
3. **Numbers** pass through `(double)(float)`, so a float↔double field migration prints identically.
4. **`Optional.empty()` ⇒ key omitted**, and omission is reserved *strictly* for that. `-1` (animation
   inherit/defer, `tintindex` untinted, `CtmExtras.tintIndex`) and `""` (`defaultStateKey`) are LOADED
   STATE and are always emitted.
5. **`byte[]` ⇒ `{sha256, length}`** — content identity without a quarter-megabyte colormap in the diff.
6. **Paths** relativized against the project dir, forward slashes.
7. **Dump keys are frozen schema constants, never Java field names.** This is what lets the gate stay
   green across the series' planned renames instead of crying wolf on them.

### Deliberately NOT dumped

Decoded pixel buffers and the context's lazy texture cache (`resolveTexture` decodes AND writes it — a
probe would be serializing an object it was concurrently mutating), the atlas (non-deterministic by
design), `packRoot` (machine-relative, scheduled for removal by A1), timestamps, and
**`primaryNamespace`** (a `findFirst` over a salt-randomized set — see the `resolveIn` guard below).

## Ordering verdicts — the load-bearing ones

Ordering is **per-producer, never per-declared-type**. `dev.simplified.ConcurrentMap` is a
ReadWriteLock wrapper, not `java.util.concurrent`, and the static type tells you nothing about order.
Two corrections to the received wisdom, both verified against the pinned `collections-2f2aa58` bytecode:

- **`toUnmodifiable()` discards LinkedHashMap staging only for `ConcurrentHashMap`/`adoptMap`.**
  `ConcurrentLinkedMap.cloneRef()` does `new LinkedHashMap<>(ref)` and **preserves** order. The blanket
  rule is over-broad.
- **For a Gson-bound field the producer is the type adapter, not the field initializer.** The
  ServiceLoader-registered factory maps the *declared interface* to a concrete impl and overwrites
  whatever the initializer built. Hence the single most fragile pair in the codebase:
  - `ModelElement.faces` — declared as the `ConcurrentMap` **interface** ⇒ Gson replaces it with a
    hash-backed map ⇒ its `newLinkedMap()` initializer **and** the javadoc promising author order are
    both **dead** ⇒ **SORT** (author order is already gone; sorting discards nothing the runtime holds).
  - `EntityModelData.bones` — declared as the **concrete** `ConcurrentLinkedMap` ⇒ Gson builds it ⇒
    author order survives ⇒ **ORDER-SEMANTIC** (it is the coplanar depth tie-break).

Order-SEMANTIC, emitted verbatim: merged `citRules`/`ctmRules` (sorted by a total comparator, then walked
first-match-wins), `PackStack.ascending`, `ModelData.elements`, `EntityModelData.bones` + `Bone.cubes`,
`Item.tints` (index = layerN), `BlockTag.values()`, `AnimationData.frames()`, `Block.Multipart.parts`,
`Block.Entity.parts`, entity `overlays`/`blockOverlays`/`transforms`, `id_order`.
`CtmRule.faces` is an **EnumSet** ⇒ ordinal order ("sort every Set" is wrong there).

**One verdict was overturned during review, and it matters:** `Entity.BoneToggle.bones` reads as
SORT — the map is only ever looked up by key on the hide path — but the **reveal** path does
`bones.putAll(spec.bones())` into the mesh's order-semantic bone map, so its sequence feeds the depth
tie-break. Sorting it would have let a tooling change that reordered the source array pass the gate
while changing which face wins at tied depth in production. It is emitted as an array.

The three `Map.copyOf` collections that would have flapped silently: `Entity.Axes.variants`,
`ItemModelNode.Special.fields()`, `PalettedPermutationSource.permutations()`.

## Coverage gaps — recorded, not papered over

1. **CIT/CTM rules have ZERO coverage in BOTH configs.** `rules.json` is `cit_rules: []`,
   `ctm_rules: []` everywhere. Note 10 asserts "defrosted carries `OPTIFINE_RULES` (CIT/CTM live)" —
   **that is false**. `defrosted` earns the capability from `optifine/color.properties` + `colormap/`;
   it ships **no `cit/` and no `ctm/` directory**, and neither does any other fixture. So `RuleSet.merge`'s
   CIT/CTM comparator and the first-match-wins walk are **not** gated. A phase touching CIT/CTM parsing
   or rule ordering needs its own evidence — the empty dump diff proves nothing there.
   *What the packs config DOES gate:* the colour-override channel is live —
   `defrosted/optifine/color.properties` carries `lilypad=5EA334` and surfaces as
   `colors.overrides.lilypad = "0xFF5EA334"` (vanilla: `{}`). W5 has real evidence.
2. **Multipart `when` OR branch is never exercised.** 63 blocks carry multipart; none produces an `OR`,
   and none produces a `|`-alternative. `redstone_wire` — the main user of both — is **absent from
   `blockIndex`** (pre-existing: the index is keyed largely by block-*model* names, e.g.
   `minecraft:redstone_dust_side`, and redstone_wire has no `block/redstone_wire.json`). The dump reports
   what is loaded; the `OR` code path is written and correct but sees no data.
3. **`synthesis.json` dumps SOURCES, not the synthesizer's registry.** The registry has no accessor and
   is a derived cartesian expansion (one row per texture per permutation, ids normalized, collisions
   last-write-wins). Reproducing it in the dump would be a second copy of a production rule, free to drift
   silently. The sources are the exact inputs production hands the synthesizer, via the exact expression
   production uses.
4. **`CatharsisConfig` is not dumped.** It is a local in a private acquisition method, never stored on
   `ResourcePack`/`PackStack`/`Result`; only `size()` is reachable, and only by duplicating acquisition's
   private byte reads. Its observable effect already surfaces in `packs.json` as `PackRoot.overlay(...)`
   entries.
5. **`gui_light` is not loaded and cannot be dumped** (deferred as PC2). The field can never bind (JSON
   key is `gui_light` carrying `"front"`/`"side"`; Gson is on identity naming) and nothing reads it. It is
   emitted as the constant `false` it actually holds, under the honest name `gui_light_3d`, as a canary
   for a Gson-configuration regression.

## PC1 (`PackAcquisition.namespaces()`) — checked, still latent, NOT fixed

The pre-flight was the decision procedure for the deferred PC1 defect (`namespaces()` builds a `TreeSet`
then returns `Set.copyOf`, discarding the sort; `TextureSynthesizer` does last-write-wins `registry.put`
while iterating it, so colliding keys across namespaces can resolve differently run-to-run).

**`synthesis.json` did not flap** across five runs, so PC1 was not triggered and remains deferred per
`notes/pr-followup/followup1.md` §D. Why it stayed quiet: only the `minecraft` namespace ships
`atlases/*.json`, so `sources` has **2 entries** and there is nothing to collide — consistent with the
standing note that neither fixture ships colliding permutations. The dump additionally sorts `sources` by
content, which pins the multiset against the flapping namespace order.

That sort deliberately does **not** claim to cover PC1: the same order still decides the registry's
collision winners in production. It is a real defect that a serializer cannot fix, and the dump says so
rather than implying coverage it does not have.

**The `resolveIn` probe would have smuggled the same flap back in** and is guarded instead. A resolved
id's namespace comes from a search order led by `pack.primaryNamespace()` — a `findFirst` over that same
salted set — which flaps whenever ≥2 namespaces normalize to the pack id. So the probe emits a
`namespace_candidates` count for every pack (a count is always deterministic) and emits `samples` only
where that count cannot flap. On these fixtures every pack reports ≤1, so all samples are present; a pack
that ever trips the guard will say so in the artifact rather than diffing at random.

## Two API gaps worked around (test-side, no production edit)

The gate's claim is that it adds test classes and a Gradle task and touches no production source.
Widening these would be tidier and provably moves no pixel, but it is not the dump's place to spend that
claim:

- `ItemModelNode.Select.Case` / `RangeDispatch.Entry` — nested inside **records**, where the
  implicit-public rule of interface members does not reach, so they are package-private and unnameable
  from the dump's package. Bridged by `src/test/java/.../pack/item/ItemNodeAccess.java`.
- `NbtValues.snbt` — package-private, and `Tag.toString()` has no stability contract a byte oracle may
  rest on. Bridged by `src/test/java/.../pack/rule/NbtLiteralText.java`.

## Guards — why an empty load cannot pass

An empty index fails nothing downstream (every lookup just returns empty), so the dump would happily
write a well-formed artifact of nothing, and two of them would compare equal. `dump(...)` therefore
throws when the texture index is empty, or when either the block or item index is empty — the latter
covering exactly the loaders (`BlockIndexLoader`/`ItemIndexLoader`) this series rewrites.

The probes take the **concrete** `PipelineRendererContext`, not the `RendererContext` interface: most of
that interface's defaults return empty, but `resolveIconGui`'s default carries real logic and falls back
to the *block* model's gui. A stub would not produce a loudly empty `icon_gui` — it would produce a
populated, plausible, quietly wrong one. A compile-time type rules that out and cannot rot.

## Restore recipe

`cache/` is gitignored, so a clean wipes the working dumps. To re-establish the base after a clean:

```bash
git checkout 7164a2b3                       # or any commit; P0 is load-neutral
./gradlew parityDump -Plabel=7164a2b3 -q
sha256sum -c /dev/stdin <<< "$(sed 's|\*|*cache/parity-dump/7164a2b3/vanilla/|' parity-baseline/pipeline-cleanup-p0-vanilla.sha256)"
```

Or simply compare the regenerated manifest against the committed copy:

```bash
diff cache/parity-dump/7164a2b3/vanilla/manifest.sha256 parity-baseline/pipeline-cleanup-p0-vanilla.sha256
diff cache/parity-dump/7164a2b3/packs/manifest.sha256   parity-baseline/pipeline-cleanup-p0-packs.sha256
```

## Per-phase gate (the workflow this oracle exists for)

```bash
# after every green commit — cheap, leaves a ready base for the next phase:
./gradlew parityDump -Plabel=$(git rev-parse --short HEAD) -q

# at a phase gate (working tree = the phase; BASE = last green commit):
./gradlew parityDump -Plabel=head -q
diff -ru cache/parity-dump/$(git rev-parse --short HEAD) cache/parity-dump/head

# verdict:
#   diff empty AND git diff --stat touches only pipeline/**, asset/**, tooling, test/build
#     -> LOAD-NEUTRAL proven; run ./gradlew test; SKIP slowTest + render sweeps.
#   diff non-empty -> must equal the phase's pre-registered expected-diff manifest, exactly.
```

**Standing precondition:** any touch to `engine/**` or the renderers demotes the phase to byte-moving
regardless of the dump verdict. An identical dump proves the render *inputs* are identical, which implies
identical output only while the render code itself is untouched.

## Gate criteria — all met

- [x] Fast suite green at HEAD (`compileJava test`).
- [x] All 14 sections implemented; both configurations captured.
- [x] Determinism proven — **5** independent JVM runs byte-identical, both configs (2 would not have
      been evidence: the salt flap is intermittent).
- [x] Content sanity checked — 90 entities, id_order matches index sizes, digest refs join
      (2330 blocks → a named model; the 148 that do not are the element-less unresolved models).
- [x] Cost measured — ~25 s/capture, 59 MB (answers note 10 Q1).
- [x] Coverage gaps recorded rather than assumed (CIT/CTM dark; multipart OR dark).
- [x] PC1 checked via the pre-flight, still latent, left deferred.
- [x] Committed oracle copies on the branch (survive `cache/` cleans).
- [x] Zero production source touched.
