# Known open

Items that are open and unowned: a decision nobody has taken, or a capability something shipped
already claims and does not have. They live here because the alternative is a working note that gets
deleted, and then the same investigation runs a second time.

What does **not** belong here. A refusal that stays refused is a decision - `RENDERER-RULES.md`'s
*Decisions that stay closed*, or `tooling/CLAUDE.md`'s. A measurement belongs in the commit that made
it, and in the `reason` recorded with the baseline it moved.

Delete an entry when it closes.

## PlayerOptions has no style knob, and coining one needs a catalog source for the player

The style axis is a string knob on `EntityOptions` resolved against the entity's shipped catalog.
The player renders through its own pipeline, holds no row in `entity_models.json`, and its sweeps
gauge look rather than bytes - so a style knob on `PlayerOptions` today would be a string with
nothing to resolve against. Deferred deliberately by the owner (2026-09-01), with the axis kept
collision-free: adding the knob later needs a player-side source of catalog rows.

`PoseStyle` names no bag at all - `AppearanceOptions.applies` asks the appearance rather than the
request carrying one - so the row type is answerable for a subject whose options are not an
entity's, and a player catalog shipping age-free rows never reaches it either way. What still spells
`EntityOptions` is the caller: `StyleCatalog.resolve` and the `byId` overload behind it take a
predicate and name no bag; `EntityRenderer` supplies it as `options.getAppearance()::applies`.

**That shape is not an `AppearanceOptions` on `PlayerOptions`.** `AppearanceOptions` stays
entity-specific by decision, so the player does not gain one. What the three bags share is an
appearance concern nothing abstracts yet, and organising that is its own job, deliberately not
attached to this entry - a knob coined by widening the player bag to look like an entity's would be
settling that question by accident.

A styled player exists on the entity path, and it is not the source the knob needs.
`author.install.PlayerRig` synthesizes a `minecraft:player` entity row - the wide-arm humanoid mesh
copied off the shipped zombie row, under `StyleCatalog.BIND_ONLY` and `EntityPose.NONE` - that takes
installed styles through `EntityRenderer`. It sits at tier 17.4 in `TierOrderTest`'s order and
`PlayerRenderer` sits at the root's 16, where an import runs only to a strictly lower tier, so
`PlayerRenderer` may not name it; and `PlayerRenderer` carries no style machinery at all, importing
nothing from `asset.pose`, `bake.pose`, `engine.pose` or `author`.

Taking the knob needs four things: a style knob on `PlayerOptions`; a player catalog source below
tier 16; style resolution the player bag can answer; and `PlayerRenderer` taught to pose. The third
is the smallest of them: `StyleCatalog.resolve` takes the style id, a predicate saying whether a row
applies to the subject, and the subject id its refusal names, so the player bag has only to supply
the predicate. Supplying it is the appearance question the three bags share, though, so taking the
knob settles the abstraction this entry keeps separate by the back door. It reaches the player
sweeps, which are LOOK gauges rather than byte gates, and the entity pose path.

## A named module waits on module names in nine upstream JARs

A consumer on the classpath reads every public type, so it faces every package that holds one. Only
a JPMS `exports` hides a whole package, and only from a consumer that is itself modular. Dropping
`public` from the types nobody outside their package names hides none: every package that holds a
type keeps one another package names, bar `author.audit` and `author.install`, whose types are the
entry points a consumer audits and installs styles through. Parked by the owner.

The blocker is upstream. The nine JitPack libraries the build takes on `api` - six from
simplified-dev, one from simplified-api, two from minecraft-library - carry no `module-info` and no
`Automatic-Module-Name`, so each is an automatic module named off its file name, and a JitPack pin
is a commit sha that is not read as a version. A sha opening with a digit leaves a name segment that
is not a Java identifier, so no name derives at all; one opening with a letter derives a name that
carries the sha and changes with every pin bump. The pins in force derive no name for seven of the
nine and a sha-bearing name for the other two. A named module cannot resolve on the module path
until each of the nine publishes an `Automatic-Module-Name` or its own `module-info` - a JitPack
build and a pin bump apiece.

Past that, `module-info.java` owes more than its `exports`, whose set is a signature-closure probe
nobody has run over the packages the README's usage section names:

- `provides dev.simplified.gson.GsonContributor with
  lib.minecraft.renderer.content.json.RendererGsonContributor`, because a named module ignores
  `META-INF/services` and a modular consumer would otherwise lose the registered adapters silently;
- `opens ... to com.google.gson` for every package Gson reflects into - `asset.mesh`,
  `asset.model` and every `content` package that decodes JSON at least;
- `requires static` for the annotation processor's vocabulary, for the `@Parity` vocabulary, whose
  JAR needs a module name of its own, and for `jdk.incubator.vector`, which keeps `SimdSupport`'s
  scalar fallback and a consumer owing no flag;
- a `blindness.json` rule or `no_reach` entry covering the file, since the plan refuses a path no
  rule covers.

Nothing in the suite runs on the module path, so a missing `opens` - an
`InaccessibleObjectException` at run time - or a `provides` that drifts goes unseen without a
module-path consumer that renders something.

## The held item's display transform composes in the reverse of vanilla's order

`ItemRenderer.Held3D` reads the model's `thirdperson_righthand` display transform and composes it
as `S * R * T` over column vectors, so a vertex is translated, then rotated, then scaled. Vanilla's
`ItemTransform.apply`, read from the client jar's bytecode, posts `translate(t)`,
`rotate(rotationXYZ)`, `scale(s)` and then `translate(-0.5, -0.5, -0.5)`, so a vertex is centred,
scaled, rotated and translated last - its translation lands neither scaled nor rotated. The two
agree only where the translation is zero and the scale uniform, and whether the renderer's own
centring stands in for vanilla's closing translate has not been traced.

Nothing compares a held render against vanilla's: the harness renders no held item, and no sweep
selects the held view. A held render of an item whose display carries a non-zero translation and a
non-uniform scale, set beside the same item held in the client, settles which order the output
shows; the visual driver's `-Ptype=held` renders one.

## An installed style poses the row and not the forms its axes swap in

`StyleRegistrar.install` compiles a style against the row's own mesh, weaves the row's pose and
overlay passes, and appends the style to the row's catalog. The baby form takes the rebuilt catalog
and nothing else, and the variant, shape and size forms are left as loaded. `AppearanceOptions`'
fold then swaps those forms in:

- a baby render takes the baby form's pose, which carries none of the install's splices, so an
  installed style - a baby-age one included - lists on the baby and plays nothing on it;
- a coat render folds onto the coat as loaded, whose pose carries no splice and whose catalog lacks
  the installed row, so the in-force resolve that follows the fold refuses an installed id;
- the large tropical fish form draws its own overlay passes, unwoven;
- a size form lends its mesh and render scale, so the row's woven pose plays over a mesh the install
  never compiled against.

Which of these is wanted is undecided: an install that compiles against each form's mesh and weaves
each form's pose, or one that refuses a style for a subject whose form it cannot pose, or the row
alone as the documented reach of an install. The answer can differ per axis. A test that renders an
installed style on a baby, a coat, the large fish and a size form pins whichever is chosen.

## A change to a value adapter or the cube factory plans the dump pair alone

Among the adapters `RendererGsonContributor` registers are seven that decode the loaded records'
values - the `Vector2f`, `Vector3f` and `Vector4f` adapters, `EulerRotationAdapter`,
`TextureSizeAdapter`, `ModelTextureAdapter` and `ResourceIdAdapter` - and its one factory,
`CubeGrowFactory`, and no record's code names any of them. The contributor itself is built by a
service loader out of a file no constant pool mentions, so the reference graph reaches none of these
from any producer, each one's reach row is empty, and a plan for a change to any of them selects
`manifest.dump.vanilla` and `manifest.dump.packs` and nothing else. The three union deserializers it
also registers - `MultipartWhenDeserializer`, `ItemModelNodeDeserializer` and
`LayerTintDeserializer` - carry the `pack-resolution` claim, and the first the
`blockstate-multipart` claim as well, so a change to one also plans the block and item sweeps and
`digest.colormap-lut`.

The dump serialises every value those adapters decode, and the fast pins run under every check, so
a decode change is still caught. What the plan does not schedule is the sweeps that render the
decoded values - the entity and block sweeps among them - so a change meant to move a render is
priced at the dump alone. It settles either as a decision that the dump pair is the evidence a
decode change owes, which closes this entry, or as a declaration on the adapters that selects the
renders they feed.

## A cube is bound through a strict tree reader, so a non-finite member throws

`CubeGrowFactory` reads each cube as a tree and binds it through the reflective delegate's
`fromJsonTree`. That tree reader keeps Gson's strict default, while `Gson.fromJson` reads the
document's own stream leniently. So a `NaN` or infinite value in any member of a cube - which
strict JSON cannot spell, but which a lenient parse of a bare `NaN` token produces - throws where
the same value elsewhere in the document reads, and a malformed cube's error path starts at the
cube rather than at the document. No shipped or pack input carries such a value.

Whether a non-finite cube value should read or be refused is undecided. Refused, a test that feeds
one through the renderer's Gson settings pins the refusal; read, the delegate binds through a
lenient reader and the same test pins the value.

## The cape's read frame is unsettled against vanilla's cape pose

`PlayerAssembly` reads the cape's strips through `CAPE_FRAME`: the vanilla cube unwrap with the
`UP` and `DOWN` strips transposed and nothing else moved - a reflection, not a rotation. Its javadoc
holds the transposition as undecided between deliberate compensation and a latent defect. Dropping
it moves the two 10x1 slivers; adopting the armour and shield frame instead would render the cape
lining-outward.

Vanilla's model is `PlayerCapeModel` in the 26.1 client. It hangs the cape off the body part as
`texOffs(0, 0).addBox(-5, 0, -1, 10, 16, 1)` in a 64x64 layer, whose trailing `1.0, 0.5` read a
64x32 sheet, posed at offset `(0, 0, 2)` with a PI yaw; `setupAnim` then rotates it by
`rotateY(-PI)`, `rotateX` over `6 + capeLean / 2 + capeFlap` degrees, `rotateZ` over
`capeLean2 / 2` degrees and `rotateY` over `180 - capeLean2 / 2` degrees. That pose has not been
traced through vanilla's cube unwrap to see which strip it draws on top and which underneath.
Tracing it settles the frame, and so does setting the two slivers beside a client render of a
caped player.

## parityPlan refuses a path the uncommitted change deletes

The plan draws its changed set from git, and the dirty set keeps a deleted path on purpose - on the
premise that a deleting commit is gated while the committed graph still answers for the path. A
deleting change's own preparation breaks the premise before the gate runs. `check` needs
`reach build`, which drops the deleted type's row, and after that the plan refuses the path
wherever a derived rule fires on it, advising a `reach build` that cannot restore it. Regenerating
the triggers, which the same change owes, drops the path from every derived rule's triggers, and
after that no rule covers it and plain `parityPlan` exits 5, refusing it as uncovered. A pre-commit
`plan --gate-exit` meets the same refusals. The clean-tree fallback, which measures a committed
branch from its fork, drops deleted paths by design, so only the uncommitted deletion is refused.

The workaround is `parityPlan -Pchanged=<paths>` over the surviving paths, answered against the
parent commit's graph wherever the change moves a surviving path's reach. It settles when the plan
answers a deleted path from the committed graph and triggers - or drops it from the dirty set and
says so - rather than refusing it.

## Nothing asserts that a quiet id-scan directory exists

The parity toolkit's blindness suite scans a roster of surfaces for refusal-id citations, and
declares two of them quiet - the `guard` and `store` test packages under
`src/test/java/lib/minecraft/renderer/` - which it walks but holds to no reached file, neither
holding a citation. The walk opens a directory with `rglob`, which answers nothing for a directory
that does not exist, and the case over the quiet roster asserts it by name and by membership in the
surfaces, never that either directory is there. A rename or move of either package drops it from
the walk while every case stays green. An assertion that every directory surface exists settles
it.

## An edit to ModelUnits plans the fluid manifest and two CRC pins through the camera

`Camera.fromTransform` reads `ModelUnits.PIXELS_PER_BLOCK`, which puts `ModelUnits` in `Camera`'s
constant pool. The fluid and portal producers reach `Camera`, so the reference graph walks each of
them on to `ModelUnits`, and that walk is what puts `manifest.fluid`, `manifest.portal`,
`pin.fluid-crc` and `pin.portal-crc` in its reach row. Only `BlockRenderer` calls `fromTransform`; neither the fluid
nor the portal renderer does. `manifest.portal` is selected on a `ModelUnits` edit whatever the
camera reads, because `ModelUnits` carries the `box-builder` claim and that rule sees the portal
manifest - `PortalRenderer` builds its slab and its gateway cube through `BoxKit`. The other three,
`manifest.fluid`, `pin.fluid-crc` and `pin.portal-crc`, are scheduled for a change that reaches them
only through a method their producers never call. The plan schedules more rather than less, which
is the safe side of the error. It settles either by a reference graph that follows the members a
producer calls rather than whole classes, which drops those three, or by the owner accepting the
three as the price of reading the shared constant, which closes this entry.

## A change to the CIT grammar plans eleven producers that never meet a CIT rule

The CIT grammar - `CitRule` and the filter types under `asset/rule/filter/` - reaches fourteen
artifacts, because `ItemContext.matches(CitRule)` puts every item, armour and player producer that
builds an `ItemContext` on the path. Eleven of those producers render with no pack carrying a `cit/`
tree, which is what B2's own reason says of every pack fixture, so no CIT rule is ever parsed or
matched in them; B2's blind list names only `manifest.dump.vanilla`. The plan schedules the eleven on
every grammar edit, which is the safe side of the error. It settles by recording the eleven as blind
on B2, measured the way B2's other entries were, or by the owner accepting the cost.

## The parity guide and the pointer table disagree on edits above a cited line

`parity/CLAUDE.md` says every edit above the anchor in the five line-cited files -
`EntityModelLoaderArmorRosterTest`, `HumanoidPartCropTest`, `ArmorParitySweep`, `GlintParitySweep` and
`PlayerParitySweep` - must keep the line count, so a cited range keeps opening on its anchor. The
same guide calls `index.json`'s `sources` section a hand-kept pointer table edited by hand, and
removing imports from three of those files moved their rosters, so the pointer rows were moved to
follow them rather than the files being held to their line counts. `ParityIndexTest` accepts either
way. It settles when the guide names which one gives way: the line count, or the pointer row.

## A demoting claim is not checked against what the type reaches

`TableEnvelope` carries `@Parity(claim = "tooling-blindness", mode = Mode.DEMOTE)`, which subtracts
what B13 names from its selection, and nothing under `src/` calls it, so today the demote removes
nothing a renderer reaches. No check holds that true: if a renderer type starts to call the
envelope, its reach widens to renderer artifacts, and the demote silently subtracts the ones B13
lists from every plan that selects it. It settles with a check that a demoting type reaches no
artifact its demote subtracts, or by the owner accepting it as a thing to watch at review.

## No byte proof draws the elytra on an entity or a baby

`ElytraKit`'s entity path (`buildWings3D`) and its baby wing mesh (`WINGS_BABY`) are drawn by
`EntityOverlayFitTest`, which asserts silhouette coverage and canvas fit and pins no byte, and no
sweep enumerates an entity wearing an elytra. A change to either path moves no gated byte, so a
regression there shows up nowhere. It settles when a pin or a sweep row draws an elytra-wearing entity at both ages.

## A Gson built without the renderer's contributor misreads four JSON forms

The array forms of `TextureSize` and `EulerRotation`, `ModelTexture`'s string form and a cube's
scalar `grow` are read by adapters `RendererGsonContributor` registers, together with
`CubeGrowFactory`; the records carry no `@JsonAdapter` of their own. Every Gson in this repository
comes from `GsonSettings.defaults()`, which installs the contributor, so none misreads them. A
downstream consumer that builds its own Gson without the contributor binds these types reflectively
and reads those forms wrongly or not at all. It settles by stating on the consumer surface that
these records decode through `GsonSettings.defaults()`, or by the owner deciding consumers never
decode them directly.

## `git log --follow` loses the entity loader's history at the commit that split its reads

`git log --follow src/main/java/lib/minecraft/renderer/content/index/EntityModelLoader.java` stops
at the commit whose subject is *Package redesign: the entity index is joined above its table
reads*, and reports the file as added there. That one commit moved the loader from `content/table/`
to `content/index/` and split its table reads out into `content/table/EntityTables.java`, so the
old `content/table/EntityModelLoader.java` is closer to `EntityTables.java` than to the moved
loader, and git's rename detection pairs those two. `git log --follow` on `EntityTables.java` walks
the loader's whole history. Plain `git blame` on the loader credits every line to that commit, and
only `git blame -C -C`, which searches other files for copied lines, recovers the older origins of
most of them.

It settles by a history rewrite that lands the loader's move and the split of its reads as two
commits, which gives that commit and every one above it a new sha.

## The iso-pose pin is written by the entity kit's golden test rather than beside the camera

`pose_isDet_positive` and `pose_matchesGolden`, the two cases that hold `Projection.VANILLA_ISO`'s
resolved camera pose to a positive determinant and to `pin.vanilla-iso-pose`, sit in
`src/test/java/lib/minecraft/renderer/bake/mesh/VanillaEntityTransformGoldenTest.java` beside the
corners case, which builds the single-cube fixture through `EntityGeometryKit` and writes
`pin.kit-corners`. The two pose cases read nothing but the camera; the class is filed in
`bake.mesh` for the corners case. `engine.camera`'s own tests assert the iso member's base Euler
angles, its lighting pose and its lens, and pin neither the determinant nor the sixteen floats of
its resolved pose, so a reader looking beside the camera for the pin does not find it. The class
name and the pin's root in `parity/reach.json` are what point to it.

It settles by splitting the two pose cases into `engine.camera`'s tests, which gives
`pin.vanilla-iso-pose` a root of its own in `ROOTS` in `parity/scripts/parity/reach.py` and adds
that test to B38's authored paths, or by the owner accepting the class name and the reach root as
the pointer.

## The woven-wave fixture is filed under a package no source set has

`woven_wave_parity.json`, the hand-authored table spelling the woven wave, sits at
`src/test/resources/lib/minecraft/renderer/pose/install/`, and both tests that read it spell that
classpath path in a `FIXTURE` constant: `author/install/BuilderLoaderParityTest` in the renderer's
tests, and `animation/PoseEmitterTest` in tooling's, whose test set takes the renderer's test output
and so reaches the file. No source set holds a `pose.install` package; the renderer-side reader is
in `author.install`, where a path mirroring its package would put the file. The path is absolute
and spelled the same in both tests, so both reads resolve and nothing fails.

It settles by moving the file under `author/install/` together with both constants, or by the owner
deciding that a test resource need not mirror the package that reads it.

## The time-dispatch search is tested from the request package

`ItemModelContextResolveTest`, in `src/test/java/lib/minecraft/renderer/request/`, nests
`TimeDispatchSearch`, whose seven cases pin `ItemModelNode.timeDispatchSteps` - the search a
caller's animated-item request derives its frame count from, declared on `asset.item`'s node type.
Six of them assert that search alone; `seesIntoUnselectableCase` also resolves its tree through
`ItemModelContext`, to show resolution walking past the branch the search sees into. `asset.item`'s
own test package holds `ItemModelNodeSpecialTest` and `SpecialTransformTest` and nothing on the
search.

Moving the nested class whole into `asset.item`'s tests carries that one case's `ItemModelContext`
call with it, an import of `request` (tier 9) from `asset.item` (tier 8.3), and `TierOrderTest`
reads test sources as well. It settles by moving the six and deciding where the seventh's contrast
lives, or by the owner accepting the search's cases where they stand.

## EntityBoundsWalker cites a kit method nothing declares

The harness's `frame/EntityBoundsWalker` says twice - on `PIXEL_DUMP_RECT` and on
`dumpTrianglesIfRequested` - that its `(v0,v1,v2)+(v0,v2,v3)` triangulation matches
`EntityGeometryKit.contributeTriangles`. `EntityGeometryKit` declares no such method: it declares
`buildTriangles`, and every cube face it emits goes through `BoxKit.addQuad`, whose `(0, 1, 2)` and
`(0, 2, 3)` fan is the split the dump reproduces. The harness cannot link a renderer type, so the
name is code text that no compiler checks.

It settles when both javadocs name `BoxKit.addQuad`.

## The harness says its pixel dump and the renderer's read one property, and they read two

`EntityBoundsWalker`'s `PIXEL_DUMP_RECT` javadoc says its parse of `-Dentity.pixel.dump` mirrors
the renderer's `DebugChannel.PIXEL_DUMP_RECT` parser "so both sides share one prop", and the comment
in `harness/build.gradle.kts` over the `entity.pixel.dump` it sets from `-PentityPixelDump` calls
it a "mirror of asset-renderer ModelEngine's prop", naming a type the renderer does not declare.
`DebugChannel` reads `asset.entity.pixel.dump`. The two parsers take the same `x0,y0,x1,y1`
rectangle under two names, so arming one side's trace leaves the other's off: the harness takes
`-PentityPixelDump`, which `gradle/parity.gradle.kts` relays to the harness build as one of its
diagnostics, and the renderer takes `-Dasset.entity.pixel.dump`.

It settles either by both comments naming the two properties and the side each one arms, or by the
harness reading `asset.entity.pixel.dump`, which makes the one name they describe real.

## Two harness files cite a BlockStateLoader method that is not declared

`frame/BlockFrameRenderer`'s comment on its pinned random source and
`frame/FirstVariantRandomSource`'s class javadoc both say the harness takes a weighted variant
list's first entry to match `BlockStateLoader.parseVariants`, which "always takes `variants[0]`".
`BlockStateLoader` declares no `parseVariants`. The pick they mean is made by
`BlockStateLoader.ApplyDto.Adapter`, whose read keeps a weighted list's first entry as the apply
and carries the list beside it; that first entry is what the block renderer draws.

It settles when both citations name that adapter.

## The tooling build comment calls a missing Vector flag a load failure

The comment over `addVectorModuleArg` in `tooling/build.gradle.kts` says the renderer's math types
need `--add-modules=jdk.incubator.vector` here, and that missing it "is a class-not-found at load,
never a silent fallback". `SimdSupport` probes `FloatVector` once with a non-initialising
`Class.forName` inside `catch (Throwable)` and answers false on a JVM without the module, and
`Vector3f` and `Matrix4f` call into `SimdOps`, the one source importing the incubator, only when
that probe answered true. A tooling JVM launched without the flag runs its math on the scalar path,
which is what the root `CLAUDE.md` says of every launch.

It settles when the comment states the silent scalar fallback and gives the flag a reason that
follows from it.
