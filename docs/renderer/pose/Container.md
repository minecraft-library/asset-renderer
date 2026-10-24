# What a renderer composes, and the container

Load when touching the renderer steps a pose row's container carries, `PosePlayer`'s container and
`$container`, a clip channel named `root`, `EntityMeshFacing`, `EntityMeshShift`, `PoseEvaluator`'s
bone lookup or `ClipPlayer`'s answer for a flattened container. Posing is five files -
[Catalog](Catalog.md), [Container](Container.md), [Drivers](Drivers.md), [Strips](Strips.md) and
[Authoring](Authoring.md). Indexed from [`CLAUDE.md`](../../../CLAUDE.md).

**What a subject's RENDERER composes goes above every mesh it submits, and it arrives composed.**
Vanilla runs `setupRotations` on the pose stack before it submits the body or any layer, so the
emitter seats those steps at the FRONT of every pose row's container - the body's, each overlay
pass's, and the alternate a suppressed pass carries - and the loaded container is the whole
sequence, read as one keyspace with nothing joined at load. A transform reaching the body alone
swims a tropical fish out from under its own pattern overlays, which is why the seat is per mesh
rather than per subject.

- **A renderer composes in the frame of the GROUND its subject stands on, and a mesh's origin is not
  it.** `LivingEntityRenderer.submit` translates the stack `1.501` blocks along the flipped y between
  the `setupRotations` it has just run and the mesh it submits, so the sequence reaches the roots
  through a translate of `1.501 * 16` model pixels or it turns the subject about its neck.
  The emitter closes every composed sequence with it, that being the one place a renderer's steps
  and a model's own container write are both in hand. It is INSIDE the chirality flip, so unlike
  every step above it it crosses no frame and is not conjugated.
- **That offset is rigid, so it moves no silhouette and only ever moves where one stands** - which
  reads as a canvas of the wrong size rather than as a transform of the wrong shape, and is what
  makes it a canvas question until it is read. It is inert wherever the angle is settled at
  generation: a constant turn is a constant displacement and the fit centres whatever it measured. So
  it reaches only a container angle a tick changes about an axis it is not parallel to, which today
  is the iron golem's lurch alone - the fish turn about y, which this offset lies along.

- **A world transform crosses a frame to reach the container.** `setupRotations` runs OUTSIDE
  vanilla's own `scale(-1, -1, 1)` where a container sits inside it, so a step is
  `M . <world step> . M` at `M = diag(-1, -1, 1)`: x and y negate and z is kept, for a translate and
  for an Euler angle alike, `M` being a half turn about z rather than a reflection. A translate also
  crosses units, a `PoseStack` moving in BLOCKS where a pivot is model pixels. Measured rather than
  read off the algebra: the cod's yaw step took it 28.17 to 0.17 and the same step un-negated gives
  57.56.
- **The base delegation emits nothing and is still required.** At the frozen pose
  `LivingEntityRenderer.setupRotations` is exactly one turn about y, which this renderer applies as
  the subject's facing, so following it would apply it twice. A body that never runs it composes
  around a different base and is refused rather than read against this one. A step emitted BEFORE the
  delegation has to be about y alone, which is what makes its position in the sequence immaterial: a
  turn about y and a translate along y both commute with the base's turn and nothing else does.
- **A leading constant turn about y is facing, not container.** It is the addend a renderer folds
  into the delegation's own body rotation - the shulker's `+ 180f` - and the base applies the body
  rotation as the subject's facing, so `EntityMeshFacing` writes it into the CUBE rotation slot of
  every root bone of the meshes that renderer turns, which reaches every render mode where a
  container step never reaches BIND.
- **A shift and a transform are two spellings of one `setupRotations` and only one may answer.**
  The shift is baked into the mesh because the bounds walk reads the mesh; the transform composes
  above it at render, and both together move the subject twice. `EntityMeshShift` refuses a subject
  carrying both, where the shift is still known and the renderer's steps are already walked. Every
  renderer the shift claims is one the walk declines - the squid's turn is by a method parameter the
  grammar has no term for - which the guard keeps a fact rather than a coincidence.

- **The container enters as a synthetic cubeless bone** named `$container`, every top-level bone
  re-parented onto it, which reuses the chain composition instead of needing quaternion-to-Euler
  algebra a rotation above the roots would otherwise want. Top-level is read the way `BoneKit` reads
  it, which is wider than a null parent: **a bone naming a parent its mesh does not declare hangs
  from the root too**, and the breeze ships two of them.

**A bone the MESH does not declare is not evaluated.** A pose belongs to a model class where a mesh
belongs to a subject, so the two part company wherever a bone rests undrawn and took its subtree with
it: an illager resting with its arms crossed has no arm to hang, and vanilla's own `setupAnim` writes
those fields on parts nothing renders. `PoseEvaluator.evaluate` passes them over - without that it
throws on all four crossed-arm illagers and the armour stand. It still throws where the disagreement
is real: a bone the mesh does have, reading one it does not.

- **A clip channel named `root` is the CONTAINER and not a bone.** `ModelPart.createPartLookup` seeds
  its map with `root -> this` - the model's own root part, which the geometry flow flattens away and
  names nowhere - and adds the named children only afterwards, so a mesh that declares a bone of that
  name takes the entry back and ten of the corpus's meshes do. `ClipPlayer.target` reads the precedence
  off that map rather than assuming it either way; passed over as an undeclared bone the channel is
  silently nothing, which is a camel that walks without leaning into its stride and a canvas measured
  around one. `PosePlayer.displacedContainer` folds what the clips displace the container's position
  and rotation by onto the INNERMOST step rather than hanging a step of its own, because vanilla holds
  one part pose for the root and `offsetPos` and `offsetRotation` add into the very fields a body
  assigned. The seat carries what the clips scale the container by on that step's own pose scale, as
  one plus the displacement - `offsetScale`'s `+=` on a root reset to one, put on after the step's
  rotation and skipped at one as `translateAndRotate` does - so the baby camel's sit pose, which keys
  the root's scale at vanilla's identity, draws unscaled. Two container scales refuse, and nothing
  shipped reaches either: one the pose writes, which assigns the root's own field - on a mesh
  flattened at a factor other than one that field holds the factor inside the feet anchor the seat
  stands above, and no shipped model or style writes one - and a clip's non-zero one on such a
  mesh, whose root scales inside the same anchor.

**A CLIP channel is the exception, because vanilla resolved it before the flattening.**
`createPartLookup` seeds `root -> this` and adds every named DESCENDANT, so a model whose root holds
one named part above the rest resolves a channel at that part's own name - and the geometry flow
dissolves exactly such a part into the bones below it, leaving the name spelled nowhere a bone lookup
reaches. **`ClipPlayer` answers it as the container, read off the mesh rather than carried beside it**:
the flow leaves every bone that hung from the container naming it as a PARENT, so a dangling parent
reference IS a flattened container, which is `PosePlayer.isTopLevel`'s own test asked of the parent
instead of the child. It is an answer rather than a guess only while one such name exists per mesh -
exactly one of the shipped geometries carries a dangling parent and none carries two - and
`ClipPlayerContainerTest` holds the corpus to that, a second meaning a surgery dropped an intermediate
bone and left its children pointing at it.

- **Only a dangling PARENT is a container; an absent bone is still absent.** A clip channel naming a
  bone the mesh simply drops stays passed over: an armadillo's `cube` is its shell body, a flag-only
  bone the mesh drops because nothing can draw it.
- **It was silent, and only a clip could reach it.** The breeze's slide shoves its body six model
  pixels and the reference had that shove where the render did not, which reads as a canvas
  disagreement rather than a dropped channel - widths and x offsets agreeing on all eight frames
  while the two contents grew and shrank apart. Worth `82.3441` of walk delta against `0.01` with the
  channel landing.
