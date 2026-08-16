# lab

The `[PX]` fragment family. **Evidence tooling, never a gate**: it needs an instrumented build, it
is not reproducible by re-running a sweep, and its outputs are probes rather than baselines. The
`lab` command group is registered only when Pillow and numpy are importable.

## The three-dump join, which is the method worth keeping

A fragment java rejected never logs its texel. So the same row is rendered three times and the runs
are zipped **position by position**:

| run | invocation | what it supplies |
|---|---|---|
| all-pass | `-Dasset.depth.range=1e12` | every fragment's texel, nothing occluded |
| raw | `-Dasset.depth.range=0` | every fragment's true unrounded depth |
| landed | unset (the shipped `1000`) | java's own verdict |

Vanilla's verdict is then recovered by finding which subset of that inventory reproduces
`vanilla.png` to within ±1 per channel - the NVIDIA shader-float floor below which the two
implementations cannot be told apart.

**Two warnings travel with the method, and both cost real time.**

- **A debug tag is NOT a unique fragment key.** A bone face reaches a pixel once as the body's
  `NORMAL` fragment and again as the aura's `ADD` one. The runs zip on emission *position*; reading
  them by tag cost two rebuilds.
- **A `SKIP-FILL` fragment is invisible to all three runs.** A pixel it decides cannot be explained
  by this method at all, so those are reported `UNEXPLAINED` rather than forced into a class.

## What is here

| module | capability |
|---|---|
| `frag.py` | the 17-field `[PX] WRITE` grammar, the `NORMAL`/`ADD`/`REPLACE` replay, the subset search |
| `census.py` | the three-dump join, pixel classification, coplanar contest harvest |
| `explain.py` | smallest fragment set to drop that reproduces vanilla, over a region |
| `predict.py` | predictor comparison over a harvested contest table |
| `px.py` | the full composite chain at one named pixel |
| `crop.py` | the zoomed `vanilla \| java \| \|delta\|x4` LOOK image |

`ADDITIVE` and `ADD` are both accepted as the additive blend token: the enum was renamed when the
composition was promoted into the `image` library, so dumps frozen either side of that read alike.

## What could not be ported, and why it is not an oversight

Four capabilities the design lists have **no surviving source anywhere in the tree** - they were
among the scratchpad losses this package exists to stop happening again:

- texture-space rectification onto a 16×16 texel grid (`rectify`, with `fitquad`'s coordinate descent)
- the per-channel affine / pure-scale tint fit (`tint`)
- the depth-quantum probe reader (`depthprobe`)
- re-scoring a frozen probe under a new comparison (`rescore`)

Writing them here would be inventing rather than porting, and nothing on disk is lost by their
absence. What each was for survives as a method rather than as a number - `CLAUDE.md` keeps "read
a suspected tint fault per channel, never through luma", and states the depth grid a quantum probe
reads against - and no figure any of the four produced is recorded anywhere tracked. Recovering one
means measuring again.

The probe **arm/disarm** script is deliberately not here either. A gate tool that edits `src/` is
one crash away from a dirty tree during a measurement; the method - perturb the value, re-render,
and the cells that move name the reach exactly - survives as a procedure executed with an edit and
`git restore`, which is auditable where a script's restore step is not.
