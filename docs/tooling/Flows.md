# Per-flow rules

Load when touching `block_defaults.json`'s reader, `EntityIndexes`, `EntityTextureScrollResolver`,
`EntityOverlayResolver`'s alpha, or `EntityPaceResolver` and the style flow's paces. Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

- `block_defaults.json` is read by `content/table/BlockDefaultsLoader`, not `BlockStateLoader`,
  which loads blockstate *variants* from vanilla JSON. It also applies the pack override at
  `renderer/block_defaults.json`, the only way a pack reaches an ASM-derived default state.
- `EntityIndexes` is session-lifetime and `EntityContext` per-subject; do not merge them. There is no
  writer class, and the only post-pass is `EntityGroupLinker.link`.
- **A texture scroll is read as the RATE it is written as, never fitted from an evaluation.**
  `EntityTextureScrollResolver` carries the age as a value of its own and only a multiply of that age
  by a literal becomes a rate, so an offset that merely happens to be a number at every tick is
  refused. The wither's armour is exactly that - `Mth.cos(age * 0.02) * 3` - and a reader that
  evaluated at one tick would emit a rate for a pass that swings and animate it wrongly at every tick
  but the first. The wrap the factory is handed carries a rate through unchanged, because it is the
  same wrap the renderer applies at the offset rather than a second operation.
- **A layer alpha ships as the overlay's `opacity`, an expression, and a pass has no other
  spelling of one.** `EntityOverlayResolver` walks the alpha lambda into the pose grammar and folds it
  against `StyleRoster.DRIVEN` as a pose is folded; what still reads an input - the warden's spots
  on the age, its heart on its clock - is written self-contained by `PoseJson.inline` and kept
  whatever it answers at the first frame, and the style flow drives every input it reads. A
  constant, an alpha reading only resting inputs and one the walk cannot read keep the frozen-frame
  number, written below one as the grammar's own `{"const": ...}`.
- **A stride walks at its subject's vanilla pace, and the pace is read, not declared.**
  `EntityPaceResolver` joins the jar reads - the movement speed, the idle stroll, the walk feed, the
  move control and the fastest behaviour - with the rows of `EntityPacePolicies` where the jar
  cannot answer, into a wander and a ceiling per (entity, age). `StyleFlow` drives a stride side
  whose sources read the walk clock at its wander - the swing held at its speed, the clock ramped at
  its rate - and writes its ceiling, the fastest walk, once for the catalog in the model's `ceilings`
  member under its age's token, a fact about how fast the subject moves rather than about one row; a
  side reading no walk clock keeps hold 1 / ramp 1 and records none. A side reading the walk clock
  with no pace stops the flow rather than shipping the literal pair.
