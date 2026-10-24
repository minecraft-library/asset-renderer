# The pose walk

Load when touching `PoseWalk`, its fork machinery and `EnumConstantTable`, `Held`, `PosePartIndex`,
`RenderTransformWalk` (a renderer's `setupRotations`), a write to a flattened container, a carried
figure (`PoseExpr.Answered.Carried`), `Vec3`, or a static table's fill. Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

- **A pose the walk restores has to be copied, not handed over.** `PoseWalk.replace` takes a fresh
  channel map per bone; sharing the snapshot's own map means the next write lands in the snapshot,
  and then a fork's second arm - or an enum split's third - starts from what the one before it wrote.
  That is silent: the arms come out EQUAL, so the merge collapses them and drops the guard that told
  them apart. It shipped a parrot flapping its wings while sitting, and a party parrot's head pose
  standing in for a shoulder parrot's.
- **Everything a fork restores rides on one `Held`.** The machine, the pose, what the body has
  assigned and the clips played are restored together at four points, and the split beside it folds
  the same record n-ways. A part left out of that record does not fail - both arms end up holding
  whatever the last of them wrote, so they agree, the merge collapses them, and the fork reports one
  arm's answer under both arms' name. Add the next one to `Held`, never beside it.
- **The root every model inherits is two different things and the field's own name says which
  neither time.** A constructor either hands its root parameter up the chain unchanged, leaving
  `Model.root` standing for the baked mesh root - a container the mesh flattens and names nowhere -
  or narrows it with one `getChild` first, leaving it standing for that named bone like any other.
  Only the argument to `super` tells them apart, so `PosePartIndex` reads it there. Reading the class
  named on the instruction answers nothing either way: javac writes the LEAF as the owner of an
  inherited field's reference, so it names the class that used the field rather than the one that
  declares it. `PoseWalk.isModelRoot` resolves instead, the way `isReset` does.
- **Every `<init>` feeds the field-to-bone map, not the first**, because a model offering both a
  `(root)` and a `(root, Function)` form builds its parts in the wider one. A miss falls back to
  `StringUtil.toSnakeCase`; a raw Java field name is never a bone name.
- **A renderer's `setupRotations` is a self-refusing walk of its own, not an extension of
  `PoseWalk`.** It reads ten statements against a closed grammar and refuses the whole body on
  anything outside it, where the pose walk follows both arms of everything it cannot decide - so the
  fork machinery and the one-`Held` rule are not involved and would not help. The one branch shape is
  a forward jump over a contiguous block, which is structural rather than a fork: the block runs
  unconditionally and each value it produces becomes a select, a step's channels against that
  channel's identity and a local against what it held before the jump. It drives the same
  `Interpreter` chassis every other walk here does, with the render state and the pose stack bound as
  the only two references a member may be read of, so a read of the body rotation or the render scale
  arrives unmodelled and refuses where it is used rather than resolving to a confident zero. The
  grammar is the render state's own float and boolean fields, float and double arithmetic, `Mth.sin`
  and `Mth.cos`, a translate, a `mulPose` of one of the three positive axes turned by degrees, the
  delegation carrying at most a literal addend folded into its body rotation, and a `rotateAround`
  about the direction the subjects rest attached at - an enum member the walk settles at generation,
  because what it decides is which steps exist rather than what a channel holds, and the identity
  rotation folds the whole call away. **Eight of the fourteen renderers that override refuse today
  and each refusal is a row**, because a transform read in half places a subject somewhere vanilla
  never puts it and renders as though it were deliberate. Reading a local off the block's own arm
  rather than reconciling it at the block's end gives every salmon its out-of-water amplitude.
- **A write to a flattened container is carried as the pose's own container, never pushed down onto
  the bones it held.** It is a parent transform above every bone the mesh names at top level, and
  `EntityPose.container` is where it lives on both sides. Only a translation could be pushed down at
  all - that composes by addition, so every child taking the same amount lands where the container
  would have put it - and a rotation cannot: it turns each child's POSITION about the container's
  pivot as well as composing with the child's own rotation, and the three Euler angles a bone carries
  are applied in one fixed order, so the two together are not a triple. Two models pose one and they
  are why both halves are needed: `AdultTurtleModel` moves it, `EnderDragonModel` places it twice and
  turns it once.
- **A flattened container starts at REST, where a bone starts at its authored pose.** The geometry
  flow dissolves the root's pose into the bones below it - the plain root vanilla builds at
  `PartPose.ZERO`, and equally the transformed root a whole-mesh scale or an aged-down proportion
  rewrites, which `applyMeshTransformerScaling` pushes onto the top-level bones as a translate and
  onto every bone as a factor. So every bone a mesh names at top level already carries whatever the
  container was holding, and a read of a container channel nothing has written is a NUMBER rather than
  a name. `PoseWalk.unwritten` is the one place that is decided, for the channel read and for the arm
  a fork did not write alike, which is what keeps the internal sentinel out of the shipped bytes.
  Which bones the container held is a fact only the mesh has, so `GeometryFlow.rootBones` answers it
  and `PoseFlow` takes it; a class whose derivations disagree gets no answer rather than one of them.
- **A figure the model carries between poses is named, and only when it is stepped along.** A field a
  body accumulates - `FoxModel.legMotionPos += 0.67` - is not a function of the render state, so it
  is named rather than derived, on the ground that binding it to nothing reproduces a real vanilla
  frame: the first one after the model was built. The only write allowed is a step ADDED to what the
  field already held, checked at the write and again over the emitted pose, because a field assigned
  outright has no starting point a caller could be handed and one never stepped along is a number a
  constructor settled. It gets its own arm rather than riding `input`: a caller answers the two in
  different places, and a render-state field of the same name would otherwise become the same input
  in silence. The arm is the walk's, not the table's - the fold settles every one of them against the
  frame before emission, and no shipped table carries the token.
- **`Vec3` is the one value type a pose body allocates**, carried as its three components. Vanilla's
  shape for building one leaves two references and the constructor consumes one, so the finished
  value has to reach every place the unbuilt one did - found by what they hold, since nothing on the
  stack says where those places are. Two arms answering two vectors choose component by component.
  Everything else allocated while posing is still refused.
- **A static table of numbers is read off the initialiser that fills it**, the way the switch tables
  are: a Java array has no declared contents. What an entry belongs to is unknown until the store
  that closes the fill names a field, so entries are gathered against whichever field closes them,
  and every position the allocation declares has to have been filled - a table short by one is a
  spike at an angle nothing authored, which reads as zero and renders.

## Decisions that stay closed

- Do not delete the pose walk's fork machinery or `EnumConstantTable` - they are the frame fold's
  input, not its residue. The fold projects a frame from what a walked program names, and tells a
  two-frame class apart over ONE frame-free walk, so a walk that resolved its environment inline
  would re-earn both from scratch as read-set tracking and per-rest-map walks; a resolved constant
  still needs the ordinal and `$SwitchMap` decode to pick a switch arm, so the table survives the
  rewrite it was priced with; the unfolded-row arm - a two-frame class no split key can carry,
  shipped as the frame-free program - would become a refusal; and the `shared` numbering follows
  walk order, so both pose tables' bytes move at identical arithmetic. What it buys is tooling
  lines alone, and renders nothing.
