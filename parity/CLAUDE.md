# asset-renderer parity

The store and the declarations that decide what it can see. Its own Gradle build, the smallest leaf
in the repo: five annotation types under `lib.minecraft.renderer.parity` importing nothing but
`java.lang.annotation`, and the parity toolkit's Python package beside them at `parity/scripts/parity/`.

**Running a gate is the `parity-gate` skill's job**, `.claude/skills/parity-gate/SKILL.md` - the task
sequence, the flags, the decision table and the refusal codes live there, and the runbooks in its
`references/procedures.md`. This file is the mechanics underneath: how a declaration is read, how
reach is resolved, what the roster and the index owe each other, and which edits are store state
without looking like it.

## Where the rest is written down

| Working on | Read |
|---|---|
| A `@Parity` declaration, a rule's trigger paths, `Scope.PACKAGE`, an `ignored` seam | [DeclaringReach](../docs/parity/DeclaringReach.md) |
| `parity/reach.json` - what makes an edge, a sealed type's `permits`, the four compiled trees, held demotes | [ReferenceGraph](../docs/parity/ReferenceGraph.md) |
| Coining or retiring an artifact - `ParityArtifacts.ALL`, `index.json`, `ROOTS` and `UNROOTED`, `parityPinWriters` - a failed producer, a provenance reason | [RosterAndIndex](../docs/parity/RosterAndIndex.md) |
| Renaming or moving a test or visual-set class the store cites, or a heading a rule cites | [StoreState](../docs/parity/StoreState.md) |

## The build

Every build that writes a `@Parity` declaration includes this one and takes it **`compileOnly`** -
retention is `SOURCE`, so javac drops the descriptor before it writes a class file and no published
artifact carries one. The renderer's test and visual sets are the exception and take it outright:
a roster guard reads `Subject.values()` at run time, and the visual set inherits the test
configurations whole.

The toolkit runs as `python parity/scripts/parity <command>` and is documented in its own
`README.md`. `paritySelfTest` is its suite, run one test module per process, and it compiles the
class roots its reach cases read. `parityCapture` runs it once per gate that captures, behind the
producers whose outputs its real-tree cases read, and the renderer's `check` schedules it too; no
other parity task pulls it in. The capture index names the toolkit that closed it, and the compare
and the promotion refuse a capture another toolkit closed.
