# Pose authoring and compiling

Load when touching `author/**` - `PoseBuilder`, `PoseCompiler`, `Seats`, `FormWalker` - or the
`PoseOperator` roster. What is settled here is settled as refusals. Posing is five files -
[Catalog](Catalog.md), [Container](Container.md), [Drivers](Drivers.md), [Strips](Strips.md) and
[Authoring](Authoring.md). Indexed from [`CLAUDE.md`](../../../CLAUDE.md).

## Decisions that stay closed

- Do not delete the `PoseOperator` constants no shipped row uses - thirty-one of the fifty
  today. The roster is the WALK's vocabulary for vanilla's own method calls rather than dead API:
  `Mth.sqrt`, `Mth.rotLerp`, `Easing.inCirc` and the rest are what a `setupAnim` body is read
  THROUGH, so dropping one turns the next version's pose into a refusal for a subject that used to
  be read. `PoseOperator` is one type both sides read, `:tooling` taking `project(":")` on
  `implementation`, so the roster the renderer ships cannot shrink without the walk's, which is where
  that reach lives. It buys no declared type either - an enum is one type whatever its constants are -
  and the only type behind it is `VanillaEase`, the bit-exact reproduction of vanilla's easing, which
  has a test of its own.
- Do not hoist the double-wave or the duplicate-keyframe refusal to where a dropped address reaches
  them. `PoseCompiler.Rules`' own doc settles it: the per-bone refusals that run inside the fold are
  left visible rather than gathered, because moving them in states a schedule they do not keep.
  Neither sits behind a drop the way it reads, either - `foldWave` is reached unconditionally for a
  container step, and `lowerClip`'s duplicate check does fire on a named bone the mesh lacks, the
  drop keeping that bone on its track plan. Only a selector answering no bone goes unsaid. The rule
  they would join already has a script-only twin: `Rules.checkOffset` walks the same frames against
  the script, with the flattening at one and no mesh in hand.
- Do not record a near miss the seat derivation refuses. The refusal sits inside a walk over every
  ordered pair of bones, so a line at it fires 1530 times over one pass of the shipped corpus,
  across 49 of the 131 subjects, 29 of them near the bound. What it would report is a property of
  the shipped pose and the mesh rather than of the style being compiled - the derivation reads
  neither the script nor anything a caller chose - so the same lines repeat under every style of
  every row. The shares `Seats.TOLERANCE` argues from are the record, and the corpus's nearest
  refusal is an ender dragon's head on its third neck segment, at `0.1006`.
- Do not give `Turn` a mirror-negation accessor. It covers three of the seven arms that negate under
  the mirror, and `Keyframe` - the timeline analogue of `Swing`, four lines below it in the same
  switch - carries three named angles and no axis, so it applies the same rule inline and can never
  consult one. An accessor reading as the rule, while the arm a reader would most want held to it
  cannot call it, is worse than the two spellings standing side by side.
- Do not unify the three-test root-anchor predicate. Six bodies carry it and only three are free to
  touch - `Seats.isTopLevel`, the compiler's joint climb and `PoseCompiler.hatRidesHead` - so a
  shared body has to be authored where those three live and leave `BoneKit`, `PosePlayer` and
  `EntityMesh.scaleAbove` spelling it themselves, which is the opposite of the unification
  `PosePlayer.isTopLevel`'s javadoc gestures at. The compiler's joint climb is not a
  copy: it carries a fourth test for a pivot of its own, argued in place, and folding the first
  three out makes a four-test climb read as three and an afterthought. The kit javadoc claiming the
  same three tests is imprecise rather than false - the composition reaches the root by a fourth
  route, a parent cycle, which fires only on re-entry and which a tree cannot produce.
- Do not hoist the raw hatch out of the custom tier. It puts the one capture nothing can mirror into
  the one builder that rewrites captures retroactively: the flip and the two mirror verbs walk the
  stances and never the raws, and there is no expression mirror to fix that with - the mirror sign
  rule maps a stance's fragments and nothing negates a sign inside an expression. After a hoist a
  flip sends the stance to the far bone and leaves the raw on the near one, where the raw lowering
  splices it over whatever crossed in, and what is recorded is a divergence of a pair rather than
  the collision it reads as. The diagnostic that would make it visible cannot be written where it
  looks like it can: the channel map a raw is spliced over is seeded from the SHIPPED pose, so a
  warn on "something was already here" fires on the hatch doing the thing it exists to do. A raw
  landing where this style's own stance wove has to be read off the stance plans. Land that check
  before widening the surface; the blindness is the ground, not the width. Folding the tier also
  deletes no tier - the builder above it is abstract and self-typed, so a concrete leaf survives
  either way, and the saving is one file.
- Do not extract the mirror sign rule or the gain-scale rule as a shared helper. The two records it
  is written over implement different sealed interfaces with nothing above them, so a helper taking
  the axis and the two bounds has no return type it can use: closing both arms takes a constructor
  reference against a functional interface this tree has none of, and the gain site takes a fifth
  argument because the factor is a parameter. The arms return the captured instance itself where the
  axis is pitch, and `implicitHatMirror` compares fragment-list identity with `==`, so a helper that
  always allocates moves what a pin already reads. The rule is written three times rather than twice
  - a spin follows it over its one value - so a helper closing two of the three leaves the drift it
  exists to close.
- Do not weave a mesh apart because its hat relation differs from the mesh whose weave it draws.
  The compile decides the head's implicit hat copy on the mesh it runs on, and a pass, a no-hat
  alternate, a lent size or shape form, or a pass reusing another form's weave draws that decision
  over a mesh of its own; where the two hang their hats differently the hat would move twice or be
  left behind, and `FormWalker` refuses the install instead. Weaving each such mesh apart would need
  a play site per row, where one play site serves every woven row, and no vanilla subject can reach
  the refusal - every vanilla humanoid hangs its hat from the head. An author who wants a hat placed
  otherwise spells the hat's own stance, which is never a copy and never refused.
