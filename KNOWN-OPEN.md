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

## No byte proof draws the elytra on an entity or a baby

`ElytraKit`'s entity path (`buildWings3D`) and its baby wing mesh (`WINGS_BABY`) are drawn by
`EntityOverlayFitTest`, which asserts silhouette coverage and canvas fit and pins no byte, and no
sweep enumerates an entity wearing an elytra. A change to either path moves no gated byte, so a
regression there shows up nowhere. It settles when a pin or a sweep row draws an elytra-wearing entity at both ages.
