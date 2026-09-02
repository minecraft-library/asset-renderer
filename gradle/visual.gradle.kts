// The visual producers: the `main()` drivers under src/test/java/lib/minecraft/renderer/visual/ and
// the two aggregators over them. Applied from the root build script.
//
// Self-contained by construction - the only declaration it shares with the rest of the build is
// visualSweepProducers, which lives here because this is what reads it. Every task registered here
// is reached by the root script's `withType<JavaExec>` hooks, which use configureEach and so cover
// tasks registered after them, and by the parity script's capture wiring, which resolves producers
// by name.

// An applied script gets no type-safe accessors, so the one extension these tasks read is bound
// here. Named `sourceSets` so every classpath site below reads as it does in the root script.
val sourceSets = the<SourceSetContainer>()

/**
 * The visual producers no other parity artifact covers, each with the `cache/visual` sub-tree it
 * writes.
 *
 * <p>One map rather than a dependency list and a parallel comment, because three things read it: the
 * aggregator's dependencies, the clear that runs before them, and the ordering edge between the two.
 * The directory half is the other end of `manifest.SUBTREES["manifest.visual"]` in the toolkit -
 * these sub-trees ARE that artifact, and `ParityIndexTest` asserts the two maps name the same set.
 * Without that the drift is one-sided: the toolkit raises for a member directory that is not there,
 * so REMOVING a producer is loud, while adding one here alone lands a render inside the store's
 * declared coverage and outside the only gate that covers it.
 */
val visualSweepProducers = mapOf(
    "blockRender3D" to "block-render-3d",
    "entityProjections" to "entity-projections",
    "entityRender3D" to "entity-render-3d",
    "itemDayCycle" to "item-day-cycle",
    "itemRender2D" to "item-render-2d",
    "loreTooltip" to "lore-tooltip",
    "menuRender" to "menu-render",
    "projectionSmoke" to "projection-smoke"
)

// The names `parity.gradle.kts` sums for `visualSweepSet`'s own wall time. An aggregator does its
// work through `dependsOn`, so the span between its own `doFirst` and `doLast` opens only once every
// one of these has finished and rounds to zero - which is why `manifest.visual` carried no duration
// and the budget printed as a floor rather than a cost. It crosses as a VALUE because an applied
// script sees no declaration of a sibling's, and this one is applied first.
extra["visualSweepProducerNames"] = visualSweepProducers.keys.toList()

tasks {
// Visual diagnostics - main() entry points in src/test/java/lib/minecraft/renderer/visual/.
// Run with `./gradlew tasks --group visual` to list. Outputs land under cache/visual/.

register<JavaExec>("blockRender3D") {
    description = "Renders blocks to cache/visual/block-render-3d/ for visual inspection. -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2. -PrenderSize and -Pssaa are only forwarded when -PblockId is also supplied; with no block id the task runs its default list at the built-in defaults."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.BlockRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val blockId = project.findProperty("blockId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    val ssaa = (project.findProperty("ssaa") as String?) ?: "2"
    args = if (blockId != null) listOf(blockId, renderSize, ssaa) else listOf()
}

register<JavaExec>("blockFlipbook") {
    description = "Renders the vanilla animated-texture blocks (fire/magma/prismarine/sea_lantern/water) with animation opted in (deriveTimeline AUTO) to cache/visual/block-flipbook/ as GIFs - the flipbook LOOK gate. -PrenderSize=256"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.BlockFlipbookDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    args = listOf(renderSize)
}

register<JavaExec>("itemDayCycle") {
    description = "Bakes a whole in-game day for the time-driven item icons (clock, plus the bearing-driven compass and a plain sword as controls) to cache/visual/item-day-cycle/ as GIFs + quarter-day stills - the animated-clock LOOK gate. -PrenderSize=256 -PdayFrames=<n> overrides the frame count; the default is 0, which derives it per item from the item's own dispatch table and is the more faithful path."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.ItemDayCycleDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    val dayFrames = (project.findProperty("dayFrames") as String?) ?: "0"
    args = listOf(renderSize, dayFrames)
}

register<JavaExec>("projectionSmoke") {
    description = "Renders a block under every GraphicalProjection + facing to cache/visual/projection-smoke/. -PblockId=minecraft:tnt -PrenderSize=512"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.BlockProjectionsDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val blockId = (project.findProperty("blockId") as String?) ?: "minecraft:tnt"
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    args = listOf(blockId, renderSize)
}

register<JavaExec>("itemRender2D") {
    description = "Renders items to cache/visual/item-render-2d/ for visual inspection. -PitemId=minecraft:diamond_sword -PrenderSize=256 -Ptype=gui|held -Psupersample=2 -PantiAlias=true. -Psupersample only affects -Ptype=held (the GUI icon is a sprite blit and ignores it); -PantiAlias (FXAA) applies to both."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.ItemRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val itemId = project.findProperty("itemId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    val supersample = (project.findProperty("supersample") as String?) ?: "1"
    val antiAlias = (project.findProperty("antiAlias") as String?) ?: "false"
    val type = (project.findProperty("type") as String?) ?: "gui"
    args = if (itemId != null) listOf(itemId, renderSize, supersample, antiAlias, type) else listOf()
}

register<JavaExec>("playerRender") {
    description = "Renders the full PlayerRenderer option matrix (scope x dimension, overlay/cape/aa/rotation/background, armor materials per slot, dyed leather, trims) to cache/visual/player-render/ as labelled contact sheets. -PrenderSize=256 -Psheets=core-matrix,toggles,... -Ppack[=<url>]"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestPlayerRender")
    classpath = sourceSets["test"].runtimeClasspath
    val argv = mutableListOf<String>()
    (project.findProperty("renderSize") as String?)?.let { argv.add("size=$it") }
    (project.findProperty("sheets") as String?)?.let { argv.add("sheets=$it") }
    if (project.hasProperty("pack")) argv.add("pack=" + ((project.findProperty("pack") as String?) ?: "defrosted"))
    (project.findProperty("account") as String?)?.let { argv.add("account=$it") }
    args = argv
}

register<JavaExec>("loreTooltip") {
    description = "Renders a pair of SkyBlock-style lore tooltips to cache/visual/lore-tooltip/ for visual inspection."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.LoreTooltipDriver")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("menuRender") {
    description = "Renders every menu subject - the eight shipped container screens, a server-style menu, an animated one, a re-inked one and an oversized one - to cache/visual/menu-render/ for visual inspection."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.MenuRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("stackCountBadge") {
    description = "Renders ItemStackKit.drawStackCount over a grey backdrop at several sizes. Use -Plabel=<tag> to write to cache/visual/stack-count-badge/<tag>/ or -Pdiff=A,B to pixel-diff two labels."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.StackCountBadgeDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val label = project.findProperty("label") as String?
    val diff = project.findProperty("diff") as String?
    args = if (diff != null) listOf("diff=$diff") else if (label != null) listOf(label) else listOf()
}

register<JavaExec>("entityRender3D") {
    description = "Renders every entity in entity_models.json via EntityRenderer (3D) to cache/visual/entity-render-3d/ for visual inspection. -PrenderSize=512 -PentityId=minecraft:zombie -Pprojection=ISOMETRIC. The -Dasset.entity.* appearance, lighting and dump knobs it reads are listed in EntityRenderDriver's javadoc."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.EntityRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    val entityId = project.findProperty("entityId") as String?
    val projection = project.findProperty("projection") as String?
    args = buildList {
        add(renderSize)
        if (entityId != null || projection != null) add(entityId ?: "")
        if (projection != null) add(projection)
    }
}

register<JavaExec>("entityProjections") {
    description = "Renders one entity under every Projection as a labelled contact sheet to cache/visual/entity-projections/. -PentityId=minecraft:zombie -PrenderSize=256"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.EntityProjectionsDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val entityId = project.findProperty("entityId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    args = buildList {
        add(entityId ?: "")
        add(renderSize)
    }
}

register<JavaExec>("entityParityVanilla") {
    description = "Per-entity parity report comparing Java pipeline vs vanilla-reference-harness ground truth (mean ARGB delta + per-entity vanilla/java/diff PNGs). Output -> cache/visual/entity-parity-vanilla/<entity>/. Run renderVanillaReferences first if the cache is missing. -PentityId=minecraft:zombie"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestEntityParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val entityId = project.findProperty("entityId") as String?
    args = if (entityId != null) listOf(entityId) else listOf()
    // -Dasset.* sysprops (e.g. -Dasset.entity.pixel.dump, -Dasset.entity.bounds.dump, -Dasset.snap.grid)
    // auto-forward to this fork via the global JavaExec forwarder near the top of this file.
}

register<JavaExec>("entityAnimationParityVanilla") {
    description = "Per-entity animated parity report comparing the Java pipeline posed at each tick of the shared schedule against the harness idle references at cache/.../references/idle/<entity>/frame_NNN.png. Writes per-frame vanilla/java/diff PNGs, a per-subject contact sheet and a TSV to cache/visual/entity-animation-parity-vanilla/. Run renderVanillaAnimationReferences first. -PentityId=minecraft:zombie"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestEntityAnimationParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val entityId = project.findProperty("entityId") as String?
    args = if (entityId != null) listOf(entityId) else listOf()
    // -Dasset.* sysprops auto-forward to this fork via the global JavaExec forwarder in the root
    // script, the same way they reach the still sweep beside it.
}

register<JavaExec>("entityWalkParityVanilla") {
    description = "Per-entity WALK parity report: the same driver as entityAnimationParityVanilla with the stride driven on both sides, comparing the Java pipeline at PoseMode.WALK against cache/.../references/walk/<entity>/frame_NNN.png. Writes to cache/visual/entity-walk-parity-vanilla/. Run renderVanillaWalkReferences first. -PentityId=minecraft:zombie"
    group = "visual"
    // ONE driver and a gait property, never a second class: the subjects, the schedule, the naming
    // and every artifact written are the same, so two copies could only ever differ in how they
    // measured rather than in what they measured.
    mainClass.set("lib.minecraft.renderer.visual.TestEntityAnimationParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("asset.parity.gait", "walk")
    val entityId = project.findProperty("entityId") as String?
    args = if (entityId != null) listOf(entityId) else listOf()
}

register<JavaExec>("blockParityVanilla") {
    description = "Per-block parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/block-parity-vanilla/<block>/. Run renderVanillaReferences first if the cache is missing. -PblockId=minecraft:tnt"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestBlockParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val blockId = project.findProperty("blockId") as String?
    args = if (blockId != null) listOf(blockId) else listOf()
    // -Dasset.* sysprops (e.g. -Dasset.snap.grid, -Dasset.entity.pixel.dump) auto-forward to this
    // fork via the global JavaExec forwarder near the top of this file.
}

register<JavaExec>("itemParityVanilla") {
    description = "Per-item parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/item-parity-vanilla/<item>/. Run renderVanillaReferences first if the cache is missing. -PitemId=minecraft:diamond_sword"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestItemParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val itemId = project.findProperty("itemId") as String?
    args = if (itemId != null) listOf(itemId) else listOf()
}

register<JavaExec>("playerParityVanilla") {
    description = "Per-scope player parity report (FULL + SKULL) comparing Java PlayerRenderer 3D vs vanilla-reference-harness ground truth (ENTITY_IN_UI lighting). Bbox-aligned diff panels -> cache/visual/player-parity-vanilla/<scope>/. Run renderVanillaPlayerReferences first if the cache is missing."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestPlayerParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("armorParityVanilla") {
    description = "Per-subject worn-armor parity report (adult + baby zombie / piglin, iron + dyed leather) comparing Java EntityRenderer vs the vanilla-reference-harness armor references. Bbox-aligned diff panels -> cache/visual/armor-parity-vanilla/<subject>/. Run renderVanillaArmorReferences first if the cache is missing."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestArmorParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("glintParityVanilla") {
    description = "Animated enchantment-glint parity: renders the 7 always-foil GUI items (+ 4 worn leather-armor diagnostics) frame-by-frame against the harness glint references at cache/.../references/glint/. Writes per-frame diffs, contact sheets, GIFs, and a TSV to cache/visual/glint-parity-vanilla/. Run renderVanillaGlintReferences first. -PitemId=minecraft:nether_star"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestGlintParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val itemId = project.findProperty("itemId") as String?
    args = if (itemId != null) listOf(itemId) else listOf()
    // -Dasset.glint.* sysprops (e.g. -Dasset.glint.itemScale=1.0) auto-forward to this fork via the
    // global JavaExec forwarder near the top of this file.
}

register<JavaExec>("menuParityVanilla") {
    description = "Per-subject container-screen parity report comparing Java MenuRenderer against the vanilla-reference-harness menu references at cache/.../references/menus/. Both sides share a canvas, so this is a direct diff rather than a bbox-aligned one. Writes per-subject diff panels and a TSV to cache/visual/menu-parity-vanilla/. Run renderVanillaMenuReferences first. -PmenuId=chest_3row"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.TestMenuParityVanilla")
    classpath = sourceSets["test"].runtimeClasspath
    val menuId = project.findProperty("menuId") as String?
    args = if (menuId != null) listOf(menuId) else listOf()
}

register<JavaExec>("fluidRenderer") {
    description = "Renders every FluidRenderer code path (water/lava, iso/2D, static/animated, biome variants, override) to cache/visual/fluid-renderer/ for visual inspection."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.FluidRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("portalRenderer") {
    description = "Renders every PortalRenderer code path (end_portal/end_gateway, iso/2D, static/animated) to cache/visual/portal-renderer/ for visual inspection."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.PortalRenderDriver")
    classpath = sourceSets["test"].runtimeClasspath
}

register<JavaExec>("redstoneTints") {
    description = "Renders the 16 redstone power-level swatches twice (vanilla / synthetic-override pack) to cache/visual/redstone-tints/. -PrenderSize=64"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.visual.RedstoneTintsDriver")
    classpath = sourceSets["test"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "64"
    args = listOf(renderSize)
}

// manifest.visual's producer, and the reason it exists: that artifact had a store file and no
// producer, so nothing could capture it.
//
// The membership is an ALLOWLIST of producer directories rather than a denylist of everything
// else. A denylist would have to be extended every time someone runs an A/B and leaves a
// directory behind under cache/visual, and forgetting to extend it silently bakes scratch into a
// baseline - there are nine such session-leftover directories on disk today.
//
// Three producers writing into cache/visual are deliberately NOT members because each already
// IS an artifact of its own (playerRender, fluidRenderer, portalRenderer), and the six
// *-parity-vanilla trees are not members because they are per-subject diff panels keyed by a
// sweep table rather than a byte-gate population. `generateAtlas` is not a member either: it
// writes build/atlas/, outside the root entirely, and its parallel tile dispatch makes its
// output permanently unhashable.
//
// One more is a non-member for a reason of its own, written down rather than left as an
// omission, because an unrecorded exclusion is indistinguishable from an oversight:
//
//  - `blockFlipbook` is an authoring tool: it opts the animation in for four blocks, writing an
//    animated GIF plus three sampled stills where the derivation finds a flipbook and a static
//    PNG where it does not, so a human can see whether consecutive frames differ. Its four
//    subjects are gated - sweep.block carries a per-subject row for each of them - and what no
//    artifact holds is the other axis: this is the only block render that turns deriveTimeline
//    on, so the frame count that derivation produces and the pixels of the frames it samples are
//    measured nowhere. What keeps it out even so is that its main catches a render failure,
//    prints it and carries on, so the task exits 0 having written nothing for that subject; a
//    capture over it can be short while the build is green, and a row here needs that made loud
//    first.
register<Delete>("visualSweepClean") {
    // No group: it is visualSweepSet's first act. A TASK rather than a doFirst on the aggregator,
    // because a doFirst runs after that task's dependencies - which are the very producers whose
    // output it has to clear.
    description = "Erases the sub-trees visualSweepSet produces, so manifest.visual's population is what the run wrote."
    delete(visualSweepProducers.values.map { "cache/visual/$it" })
}

register("visualSweepSet") {
    description = "Runs the visual renders whose output cache/visual sub-tree no other parity artifact covers - the producer of manifest.visual."
    group = "visual"
    // The clear runs first, and the producers are ordered after it. Without that the member
    // sub-trees accumulate across sessions and the artifact's population becomes a function of
    // session history: measured over the six members of the day at 255 files where the run
    // itself wrote 153, with entity-render-3d 90 fresh beside 14 stale and block-render-3d 35
    // beside 83.
    dependsOn("visualSweepClean")
    dependsOn(visualSweepProducers.keys)
}
visualSweepProducers.keys.forEach { producer ->
    named(producer) { mustRunAfter("visualSweepClean") }
}

// manifest.player-raw's producer. Both sweeps rescale both sides before diffing, so their delta is
// a LOOK gauge; the raw renders they now also write are not, and this is what captures the pair of
// them together.
//
// There is deliberately NO clean beside it, where visualSweepSet has one. That clean exists
// because its producers are parameterised (-PblockId, -PrenderSize) and accumulate across
// sessions - measured at 255 files where the run wrote 153. These two take a fixed Java roster,
// two scopes and seven subjects, and rewrite every file every run; they are also the only two
// sweep rows with an empty `scopedBy`, which is that property stated where the capture reads it.
// So the population is not a function of session history and there is nothing to erase.
register("playerRawSweepSet") {
    description = "Runs the player and armour parity sweeps together - the producer of manifest.player-raw, whose two members are one sweep's output each."
    group = "visual"
    dependsOn("playerParityVanilla", "armorParityVanilla")
}
}
