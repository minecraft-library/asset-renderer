# The pose table

Load when touching what `entity_poses.json` ships - the `shared` graph, `PoseFold` and the frame it
folds against, a collector over a pose, `PoseStates`, a split key, the poser a body or a baby names
(`EntityPoseClass`, `EntityBoneResolver`'s `bones.pose`, `PoseFlow.BABY_POSER`). How a site's age is
filed is [RestingSubject.md](RestingSubject.md); deleting the walk machinery the fold reads is
refused in [PoseWalk.md](PoseWalk.md#decisions-that-stay-closed). Indexed from
[`tooling/CLAUDE.md`](../../tooling/CLAUDE.md).

- **A pose in `entity_poses.json` is a GRAPH, and the `shared` table is what says so.** A
  `setupAnim` walk follows both arms of everything it cannot decide, so one sub-expression is reached
  down enormously many paths - a humanoid's arms are nine hundred distinct nodes standing for
  twenty-two million, and spelling that out per path could not be written at all. So a node reached
  more than once is written once under `shared` and used as `{"ref": n}`, entries ordered so
  everything a node names is declared above it. Leaves stay inline however often they are reached: a
  reference to a literal costs what the literal costs and reads worse. Both sides depend on this
  being a graph rather than a shorthand for a tree - `PoseJson` interns bottom-up against the numbers
  it has already given a node's children, because asking a map about a node would hash it by walking
  the very tree this exists to avoid; and `EntityPosesTable` resolves every reference to ONE record
  instance, a reader that rebuilt one per reference being a reader that puts the twenty-two million
  back.
- **A pose is folded against the frame its subjects rest in, and a frame is what the ROW can tell
  apart.** `PoseFold` takes every branch an offline subject already decides, so what ships is the
  residual the tick can still move rather than a program over the whole render state. One row folds
  against ONE frame, which is what keeps the result a graph - a node reached down six paths has one
  binding and there is nothing to specialise it to - so a class reached at two frames is emitted
  unfolded and named in the log rather than folded against one of them, which would draw a subject
  vanilla never draws; one that reads `ageScale` and two ages reach as well refuses the flow
  instead. What makes a frame is the two questions a fold asks a resting state, which
  constant a member holds and what number it reads as, asked only of the members that row NAMES:
  comparing the raw resting maps instead refuses three of the corpus's crowded classes over members
  their poses never look at. Any of the raw maps behind one frame folds to one residual, measured by
  regenerating the whole table against the first of them and against the last.

**Every collector over a pose walks the flags as well as the channels.** A member can be named only
by a flag expression - a frog's croak animation is reached through nothing else in its model - and the
member set a collector answers is what `PoseFold.frameOf` projects a frame from and what decides
whether a class reached at two frames splits. A collector blind to the flags merges the two illager
frames, which ships the pillager the crossed-arms mesh.

- **The branch a resting subject does not take is kept as a state silhouette, beside the row and
  read by nothing at render.** The fold drops it whole, and what it placed is evidence the table
  otherwise loses - a sitting wolf's body lowered onto its haunches with the tail and hind legs
  placed by hand where that body carries them says which parts vanilla moves together. `PoseStates`
  folds the walked program once more per question the body asks of its render state - each boolean
  it compares against zero, flipped from what it rests at; each enum constant it tests other than
  the one it rests holding; and each unit figure it reads that rests at zero, moved to one, which is
  the completed stand of an equine's `standAnimation` or a bear's `standScale`, a branch no
  comparison spells - with nothing left free, so every figure the tick drives rests and only a read
  of the mesh stays symbolic. A figure the frame spells as a boolean flips as one however the body
  reads it. Each state's bones are written under `states` keyed
  `member=value`, spelled exactly as the row's bones are over a shared table of their own, holding
  only the position and rotation channels the state places away from the resting row. One answer
  flipped at a time, never a product: linear in what the body asks, and a placement two answers
  decide between them has no single branch to be read from. A float figure the body tests against
  zero for equality is toggled to one under the boolean rule and keyed `member=true`, which is a
  real frame of it and the one place the key's spelling reads oddly; the same figure read
  arithmetically is keyed `member=1`. **A row two ages reach writes each state once, in the one
  spelling that holds at every site**: a channel every age places alike is kept as it stands, a
  position each age places exactly at the rest of every mesh drawn at that age is left out - the
  part then stays where the row leaves it, which is each mesh's own rest - and anything else stops
  the flow. That proof is the one place a state silhouette is measured against a mesh, through the
  renderer's own `EntityMesh`, and only for a part the root holds on a mesh flattened at nothing,
  where a read answers the stored pivot; it never writes a number taken from one. The armour
  stand's finished attack is the one such channel: vanilla places each arm at `5 * ageScale`, 5 on
  the large stand and 2.5 on the small one, each exactly where that stand's mesh rests it, so
  `attackTime=1` names no arm `x`.
- **A class reached at two frames is SPLIT where every site reaching it is a body, and the body names
  the key it takes.** A body - the adult age option, and each coat of a family with them - is the one
  site `EntityIndexBuilder` resolves through the subject's own `bones.pose`, so a class an overlay,
  a size mesh or an equipment layer also reaches has nowhere to carry a second key and stays
  unfolded. The suffix names the members the frames DISAGREE on and nothing else, and a frame
  answering none of them keeps the bare class name. This is why the model table is written AFTER the
  pose flow rather than before it: the pose flow owns the keyspace that table joins on, and a
  `bones.pose` naming a row nothing wrote is silent at render, so the flow refuses one instead.
- **A body key is the poser a subject names, never that key BESIDE the coordinate's own head.**
  Reading both credits a class the subject does not pose through - a zombified piglin stood for
  `AdultPiglinModel` while its body poses as `AdultZombifiedPiglinModel` - and one phantom is enough
  to make a class look reached at two frames and refuse a fold that had nothing to choose between.
- **The class that bakes a mesh is not the class that poses it, and two members say so: `bones.pose`
  for a body, the baby age option's own `pose` for a baby.** A geometry coordinate is headed with the
  baking class, and a model reusing its parent's layer bakes nothing of its own - so the coordinate
  names `HumanoidModel` for a zombie the
  renderer hands a `ZombieModel`, and reading it there loses the arms-out stance. `EntityPoseClass`
  answers it as the first `EntityModel` a renderer's constructor chain allocates: a body's model is
  built into the `super` call's arguments, evaluated before any `addLayer`, and a renderer allocating
  none delegates to the one it extends. `EntityBoneResolver` emits the member wherever that class and
  the mesh's factory disagree - **including when the class declares no undrawn bone and no toggle**,
  which is what the early return used to swallow, and which is why three equine body-armour rows had
  been posing off their mesh. A posing class bakes no mesh, so `PoseFlow` would never have walked it:
  its roster takes the posing classes beside the manifest's and reads each one's top-level bones off
  its nearest baking ancestor, that ancestor being the very class it reuses the layer of.
- **A baby is posed through the class its renderer constructs around the baby's layer**, read off
  the bake triple that pairs the layer with that constructor - which covers a model built in a static
  helper (the cow's `bakeModels`) and one built from a layer handed in as a parameter (the zombified
  piglin's), and no layer a renderer is handed already built (the squids'). Where that class is not
  the one heading the baby's mesh, `EntityAgeAxisResolver` names it on the option under
  `PoseFlow.BABY_POSER`, a generation-only member the pose flow files the baby's site under, folds
  and names by, and takes off with the baby's `pose` written. **It does so only where the two pose
  differently.** One descending from the other with nothing between them but constructors handing
  the root up is posing alike, and the baby keeps its mesh's key: the cow, dolphin and polar bear draw
  their babies through the adult class, and a baby keyed apart folds on a row of its own at its own
  age, where one row reached at both ages refuses the flow for the polar bear, whose standing pose
  places its legs by the age. Three babies move in 26.1: the sniffer onto `SnifferModel` -
  `SniffletModel`, which stores a head transform and plays it, is constructed nowhere - the drowned
  onto `BabyDrownedModel` and the zombified piglin onto `BabyZombifiedPiglinModel`, both walked as
  posing classes.
