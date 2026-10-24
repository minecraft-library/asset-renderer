# Posed strips and the posed ground truth

Load when touching `PoseStrips`, `StripFit`, the `idle/` and `walk/` references and the sweeps that
read them, a pass's opacity, or how a posed canvas is measured. Posing is five files -
[Catalog](Catalog.md), [Container](Container.md), [Drivers](Drivers.md), [Strips](Strips.md) and
[Authoring](Authoring.md). Indexed from [`CLAUDE.md`](../../../CLAUDE.md).

**The ground truth for a posed subject is `idle/`, and it is a second reference set rather than
a replacement.** `entityAnimationParityVanilla` renders each subject at the `idle` row naming no
frame count, so it draws the strip `StyleResolution` answers, and the harness steps the same subject
over the schedule the roster carries from that same resolution, both from tick zero - and it diffs
frame by frame. It is the only gate that can
see the pose table at all: everything else compares the mesh as authored, where both sides freeze and
agree by construction. Two thirds of the corpus lands under `0.25` there, which is the still sweep's
own bar; what the rest names is listed by the sweep and is a divergence per subject rather than a
property of the mechanism.

**`stride` has a vanilla reference of its own, and it is `walk/`.** The harness drives the same two
figures at the speed and rate the asset side resolves for each subject - the roster
`entityScheduleRoster` writes from `StyleResolution` - under `-Drefharness.walking=true`, which is the
animated run with a stride rather than a run of its own. `StyleCatalogMirrorTest` holds the harness to
arming what the roster names and nothing of its own, because the speed is the swing as well as the
phase and a value that moved on one side puts the two at different points of different strides.
`entityWalkParityVanilla` is the sweep, and it is `entityAnimationParityVanilla`'s own driver at
`asset.parity.gait=walk`: one implementation, so a divergence between the two reports cannot be a
divergence in how they were measured.

- **A stride reaches only what reads one**, which is measured rather than assumed: 27 of the 134
  stems render byte-identical between `idle/` and `walk/`, and `bat` carries the same delta to the
  digit under both gaits.

**A render naming no frame count draws the strip derived for its in-force row.** `PoseStrips.of`
samples every channel the row moves - the pose expressions of the body, every overlay pass and every
equipment mesh, each clip site apart, each scrolling sheet, each pass opacity that follows the clock -
and `StripFit.fit` takes the shortest loop whose restart jumps by no more than one tolerance on any
channel. It steps one tick a frame where a visible motion is too fast for three, and wherever else the
one-tick strip shows every visible part whole inside sixty-six ticks and restarts no worse than the
three-tick one - it closes, or jumps no further - and three ticks a frame otherwise. A strip runs
sixty-six frames at the most, and so 198 ticks at three a frame; the one strip that runs longer is one
too fast for three ticks holding a slower part longer than sixty-six ticks, which runs on up to 198
one-tick frames so that part is not cut. A one-shot that does not end where it starts stops the strip
at its own length, and a row whose motion is too small to see is one frame. It is worked out the
first time a row is drawn through a context and held for as long as the context holds the definition,
so shipped, universal and authored rows take one derivation and the table ships no frame count. Named
alone, a frame count keeps the row's step and a step fills as many frames as cover the loop; named
together, both are the caller's.
`PoseStripsCorpusTest` pins every shipped row's strip at each age, in-force gate set, axis form whose
pose program differs and posed trait subject.

**A pass's opacity may follow the clock.** Where the layer vanilla draws scales its colour by a
function of the render state, the overlay row ships that function as its `opacity`, evaluated at
every posed frame through the frame the bone channels read - so a warden's spots pulse and its heart
beats on the ticks the pose is drawn at. A bind render evaluates it with nothing driven, which is the
frame a never-ticked subject draws. A frame whose opacity is at most `1.0E-5` - where vanilla's own
layer skips its draw - draws no fragment of the pass and measures none of its silhouette. The strip
samples the opacity as it draws it, so a pulse longer than the pose's loop lengthens the strip until
the pulse is shown whole once; where the strip restarts is judged on the body alone, and the glow may
jump there.

**The canvas is a union of silhouettes, each measured over the frames its own render draws.** The
subject is measured across every frame of the render's schedule, each through its own posed mesh and
its own tick's texture. Under the group scope every variant coat, every member and a baby's own adult
is measured over its own strip, resolved through the caller's animation options exactly as the
subject's is - so the canvas is the same whichever member is drawn, and a caller naming both knobs
measures every silhouette at their ticks. A one-frame schedule measures the one frame it draws and
draws the geometry it already built for the empty-canvas early-out; a schedule with frames to spare
builds each of them, nothing about a posed tick being carryable to its neighbour.

- **A canvas is a union, so every silhouette in it is measured in the pose the render draws** - the
  requested subject, each variant coat, and every group member alike. **What that rests on is that
  `Entity.members()` and the harness's `EntityRoster.FAMILY_OVERRIDES` are one set**, and they are
  held together by nothing but the two being written to agree: the tooling derives this side's from
  shared primary geometry and the harness declares its own by hand. A group only one of them carries
  is a canvas one renderer unions and the other does not, worth 333 of animated delta over the piglin
  family while the two disagreed, so a group added on either side is added on both and the evidence
  that they agree is that the two canvases agree.
- **The disagreement was invisible for as long as every subject was measured at rest**, because the
  unposed silhouettes nest inside one another - the still sweep had `java_w == vanilla_w` on every
  grouped row throughout. Reconciling the sets and posing the members re-rendered the whole reference
  tree and moved 48 of 3762 files, all of them the three piglins under the two posed gaits, with
  `entities/` untouched.
