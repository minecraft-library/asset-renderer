// The visual producers: the `main()` drivers and sweeps under src/visual/java/lib/minecraft/renderer/ and
// the two aggregators over them. Applied from the root build script.
//
// Self-contained by construction - what it shares with the rest of the build is the vanilla sweeps'
// shared JVM, which lives here because this is what reads it, and crosses to the parity script as
// values. Every task registered here is reached by the root script's `withType<JavaExec>` hooks,
// which use configureEach and so cover tasks registered after them, and by the parity script's
// capture wiring, which resolves producers by name.

import java.util.concurrent.atomic.AtomicLong

// An applied script gets no type-safe accessors, so the one extension these tasks read is bound
// here. Named `sourceSets` so every classpath site below reads as it does in the root script.
val sourceSets = the<SourceSetContainer>()

/**
 * The visual producers no other parity artifact covers, each with the `cache/visual` sub-tree it
 * writes.
 *
 * <p>One map rather than a driver list and a parallel comment, because two things read it: the
 * aggregator, which runs every producer's driver in one JVM, in this order, and the clear that runs
 * before it. The directory half is the other end of `manifest.SUBTREES["manifest.visual"]` in the
 * toolkit - these sub-trees ARE that artifact, and `ParityIndexTest` asserts the two maps name the
 * same set. Without that the drift is one-sided: the toolkit raises for a member directory that is not
 * there, so REMOVING a producer is loud, while adding one here alone lands a render inside the store's
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

/**
 * One sweep the vanilla sweep JVM runs for a producer.
 *
 * @param writer the class that writes the sweep's table - what `SweepBundle` runs, and where the
 *   reach graph roots the producer's artifact
 * @param scopedBy the project property naming the subjects a hand run narrows the sweep to, or null
 *   for a sweep whose roster is fixed
 * @param leading what the sweep is handed ahead of that property's value
 */
data class VanillaSweep(val writer: String, val scopedBy: String? = null, val leading: List<String> = emptyList())

/**
 * The sweeps measured over vanilla's own client options, each producer against what the shared JVM
 * runs for it.
 *
 * <p>Every one of them loads the same context from the same options, so whichever of them an
 * invocation schedules run in ONE JVM over ONE context, `vanillaSweepRun`, one after another in this
 * map's order, and the JVM start, the client acquisition and the context load are paid once. Each
 * producer stays a task of its own: a plan names and a capture runs exactly the sweeps a change
 * reaches, a hand run of one runs that one alone, and a sweep that fails fails its own producer and no
 * other's. The pack tooltip sweep is not here, because it loads a context over the pack rather than
 * vanilla's, and keeps a JVM of its own for it.
 *
 * <p>The animation sweep is here twice, at two gaits: ONE driver handed its gait, never a second
 * class, because the subjects, the checks, the naming and every artifact written are the same and
 * each subject's schedule is resolved by the same code, so two copies could only ever differ in how
 * they measured rather than in what they measured.
 */
val vanillaSweeps: Map<String, VanillaSweep> = linkedMapOf(
    "entityParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.EntityParitySweep", "entityId"),
    "entityAnimationParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.EntityAnimationParitySweep", "entityId", listOf("idle")),
    "entityWalkParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.EntityAnimationParitySweep", "entityId", listOf("walk")),
    "blockParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.BlockParitySweep", "blockId"),
    "itemParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.ItemParitySweep", "itemId"),
    "playerParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.PlayerParitySweep"),
    "armorParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.ArmorParitySweep"),
    "glintParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.GlintParitySweep", "itemId"),
    "menuParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.MenuParitySweep", "menuId"),
    "tooltipParityVanilla" to VanillaSweep("lib.minecraft.renderer.sweep.TooltipParitySweep", "tooltipId", listOf("vanilla"))
)

// What the shared JVM answers for each producer, filled as it ends: how long that producer's sweep
// took, plus an equal share of the JVM's own start and context load, so the times sum to the JVM's
// wall - and what a failed one threw. Each producer reads its own failure here; the
// parity script reads the times as each producer's wall, and hands back the log each sweep's stdout is
// copied into, the file it tees every other producer's stdout into. They cross as VALUES because an
// applied script sees no declaration of a sibling's, and this one is applied first.
val vanillaSweepWallMs: MutableMap<String, Long> = linkedMapOf()
val vanillaSweepFailures: MutableMap<String, String> = linkedMapOf()
val vanillaSweepLogs: MutableMap<String, File> = linkedMapOf()
extra["vanillaSweepProducerNames"] = vanillaSweeps.keys.toList()
extra["vanillaSweepWallMs"] = vanillaSweepWallMs
extra["vanillaSweepLogs"] = vanillaSweepLogs

tasks {
// Visual diagnostics - main() entry points in src/visual/java/lib/minecraft/renderer/.
// Run with `./gradlew tasks --group visual` to list. Outputs land under cache/visual/.

register<JavaExec>("blockRender3D") {
    description = "Renders blocks to cache/visual/block-render-3d/ for visual inspection. -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2 -PhideTextures=minecraft:block/oak_planks. -PrenderSize, -Pssaa and -PhideTextures are only forwarded when -PblockId is also supplied; with no block id the task runs its default list at the built-in defaults. -PhideTextures forces the named ids absent, which is the only way to reach a texture miss on a vanilla-only stack."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.BlockRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val blockId = project.findProperty("blockId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    val ssaa = (project.findProperty("ssaa") as String?) ?: "2"
    val hideTextures = (project.findProperty("hideTextures") as String?) ?: ""
    args = if (blockId != null) listOf(blockId, renderSize, ssaa, hideTextures) else listOf()
}

register<JavaExec>("blockFlipbook") {
    description = "Renders the vanilla animated-texture blocks (fire/magma/prismarine/sea_lantern/water) with animation opted in (deriveTimeline AUTO) to cache/visual/block-flipbook/ as GIFs - the flipbook LOOK gate. -PrenderSize=256"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.BlockFlipbookDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    args = listOf(renderSize)
}

register<JavaExec>("itemDayCycle") {
    description = "Bakes a whole in-game day for the time-driven item icons (clock, plus the bearing-driven compass and a plain sword as controls) to cache/visual/item-day-cycle/ as GIFs + quarter-day stills - the animated-clock LOOK gate. -PrenderSize=256 -PdayFrames=<n> overrides the frame count; the default is 0, which derives it per item from the item's own dispatch table and is the more faithful path."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.ItemDayCycleDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    val dayFrames = (project.findProperty("dayFrames") as String?) ?: "0"
    args = listOf(renderSize, dayFrames)
}

register<JavaExec>("projectionSmoke") {
    description = "Renders a block under every GraphicalProjection + facing to cache/visual/projection-smoke/. -PblockId=minecraft:tnt -PrenderSize=512"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.BlockProjectionsDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val blockId = (project.findProperty("blockId") as String?) ?: "minecraft:tnt"
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    args = listOf(blockId, renderSize)
}

register<JavaExec>("itemRender2D") {
    description = "Renders items to cache/visual/item-render-2d/ for visual inspection. -PitemId=minecraft:diamond_sword -PrenderSize=256 -Ptype=gui|held|icon -Psupersample=2 -PantiAlias=true -PhideTextures=minecraft:item/stick. -Psupersample only affects -Ptype=held (the GUI icon is a sprite blit and ignores it); -PantiAlias (FXAA) applies to both. -Ptype=icon is the faithful inventory icon; -Ptype=held also draws a block-backed id whose item definition names its block model; -Ptype=gui draws item-model ids only. -PhideTextures forces the named ids absent, which is the only way to reach a texture miss on a vanilla-only stack."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.ItemRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val itemId = project.findProperty("itemId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    val supersample = (project.findProperty("supersample") as String?) ?: "1"
    val antiAlias = (project.findProperty("antiAlias") as String?) ?: "false"
    val type = (project.findProperty("type") as String?) ?: "gui"
    val hideTextures = (project.findProperty("hideTextures") as String?) ?: ""
    args = if (itemId != null) listOf(itemId, renderSize, supersample, antiAlias, type, hideTextures) else listOf()
}

register<JavaExec>("playerRender") {
    description = "Renders the full PlayerRenderer option matrix (scope x dimension, overlay/cape/aa/rotation/background, armor materials per slot, dyed leather, trims) to cache/visual/player-render/ as labelled contact sheets. -PrenderSize=256 -Psheets=core-matrix,toggles,... The live-account sheet, a Mojang fetch, renders only when asked for: -Psheets=account or -Paccount=<username>."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.PlayerRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val argv = mutableListOf<String>()
    (project.findProperty("renderSize") as String?)?.let { argv.add("size=$it") }
    (project.findProperty("sheets") as String?)?.let { argv.add("sheets=$it") }
    (project.findProperty("account") as String?)?.let { argv.add("account=$it") }
    args = argv
}

register<JavaExec>("loreTooltip") {
    description = "Renders a pair of SkyBlock-style lore tooltips to cache/visual/lore-tooltip/ for visual inspection."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.LoreTooltipDriver")
    classpath = sourceSets["visual"].runtimeClasspath
}

register<JavaExec>("menuRender") {
    description = "Renders the menu subjects no shipped client screen can be checked against - the anvil bare with its caret declined, a server-style menu, an animated one, a re-inked one and an oversized one - to cache/visual/menu-render/ for visual inspection. The shipped screens are menuParityVanilla's subjects."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.MenuRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
}

register<JavaExec>("stackCountBadge") {
    description = "Renders ItemStackKit.drawStackCount over a grey backdrop at several sizes. Use -Plabel=<tag> to write to cache/visual/stack-count-badge/<tag>/ or -Pdiff=A,B to pixel-diff two labels."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.StackCountBadgeDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val label = project.findProperty("label") as String?
    val diff = project.findProperty("diff") as String?
    args = if (diff != null) listOf("diff=$diff") else if (label != null) listOf(label) else listOf()
}

register<JavaExec>("poseShowcase") {
    description = "Renders the custom-pose cookbook through StyleRegistrar - moving styles as animated GIFs, statues as PNGs - to cache/visual/pose-showcase/ for visual inspection. -PrenderSize=512 -Ppose=wave limits to one style."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.PoseShowcaseDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
    val pose = project.findProperty("pose") as String?
    args = buildList {
        add(renderSize)
        if (pose != null) add(pose)
    }
}

register<JavaExec>("entityRender3D") {
    description = "Renders every entity in entity_models.json via EntityRenderer (3D) to cache/visual/entity-render-3d/ for visual inspection. -PrenderSize=512 -PentityId=minecraft:zombie -Pprojection=ISOMETRIC. The -Dasset.entity.* appearance, lighting and dump knobs it reads are listed in EntityRenderDriver's javadoc."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.EntityRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
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
    mainClass.set("lib.minecraft.renderer.driver.EntityProjectionsDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    val entityId = project.findProperty("entityId") as String?
    val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
    args = buildList {
        add(entityId ?: "")
        add(renderSize)
    }
}

register("entityParityVanilla") {
    description = "Per-entity parity report comparing Java pipeline vs vanilla-reference-harness ground truth (mean ARGB delta + per-entity vanilla/java PNGs, with a diff and a panel where the two differ). Output -> cache/visual/entity-parity-vanilla/<entity>/. Run renderVanillaReferences first if the cache is missing. -PentityId=minecraft:zombie"
    group = "visual"
    // -Dasset.* sysprops (e.g. -Dasset.entity.pixel.dump, -Dasset.entity.bounds.dump, -Dasset.snap.grid)
    // auto-forward to the shared JVM via the global JavaExec forwarder in the root script.
}

register("entityAnimationParityVanilla") {
    description = "Per-entity animated parity report comparing the Java pipeline posed at each tick of the strip it derives, held to the harness's schedule sidecar, against the harness idle references at cache/.../references/idle/<entity>/frame_NNN.png. Writes per-frame java PNGs beside a copy of the reference frames, a per-subject contact sheet and GIF, and a TSV to cache/visual/entity-animation-parity-vanilla/. Run renderVanillaAnimationReferences first. -PentityId=minecraft:zombie"
    group = "visual"
}

register("entityWalkParityVanilla") {
    description = "Per-entity WALK parity report: the same driver as entityAnimationParityVanilla with each subject walking at its own pace on both sides, comparing the Java pipeline at the walk gait against cache/.../references/walk/<entity>/frame_NNN.png. Writes to cache/visual/entity-walk-parity-vanilla/. Run renderVanillaWalkReferences first. -PentityId=minecraft:zombie"
    group = "visual"
}

register("blockParityVanilla") {
    description = "Per-block parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/block-parity-vanilla/<block>/. Run renderVanillaReferences first if the cache is missing. -PblockId=minecraft:tnt"
    group = "visual"
}

register("itemParityVanilla") {
    description = "Per-item parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/item-parity-vanilla/<item>/. Run renderVanillaReferences first if the cache is missing. -PitemId=minecraft:diamond_sword"
    group = "visual"
}

register("playerParityVanilla") {
    description = "Per-scope player parity report (FULL + SKULL) comparing Java PlayerRenderer 3D vs vanilla-reference-harness ground truth (ENTITY_IN_UI lighting). Bbox-aligned diff panels -> cache/visual/player-parity-vanilla/<scope>/. Run renderVanillaPlayerReferences first if the cache is missing."
    group = "visual"
}

register("armorParityVanilla") {
    description = "Per-subject worn-armor parity report (adult + baby zombie / piglin, iron + dyed leather) comparing Java EntityRenderer vs the vanilla-reference-harness armor references. Bbox-aligned diff panels -> cache/visual/armor-parity-vanilla/<subject>/. Run renderVanillaArmorReferences first if the cache is missing."
    group = "visual"
}

register("glintParityVanilla") {
    description = "Animated enchantment-glint parity: renders the 7 always-foil GUI items (+ 4 worn leather-armor diagnostics) frame-by-frame against the harness glint references at cache/.../references/glint/. Writes per-frame java PNGs beside a copy of the reference frames, contact sheets, GIFs, and a TSV to cache/visual/glint-parity-vanilla/. Run renderVanillaGlintReferences first. -PitemId=minecraft:nether_star"
    group = "visual"
    // -Dasset.glint.* sysprops (e.g. -Dasset.glint.atlasUv=true) auto-forward to the shared JVM via
    // the global JavaExec forwarder in the root script.
}

register("menuParityVanilla") {
    description = "Per-subject container-screen parity report comparing Java MenuRenderer against the vanilla-reference-harness menu references at cache/.../references/menus/. Both sides share a canvas, so this is a direct diff rather than a bbox-aligned one. Writes per-subject renders, a diff panel where the two differ, and a TSV to cache/visual/menu-parity-vanilla/. Run renderVanillaMenuReferences first. -PmenuId=chest_3row"
    group = "visual"
}

register("tooltipParityVanilla") {
    description = "Per-subject tooltip parity report comparing Java TextRenderer against the vanilla-reference-harness tooltip references at cache/.../references/tooltips/, every subject of the tracked roster whole and as its box alone, and every plain line as one line of chat text. Each reference - a tooltip's whole sprite rect, a plain line's text on nothing - is taken to its own first pixel and compared with the renderer's canvas as drawn, so this is a direct diff rather than a bbox-aligned one; a size that disagrees fails the row. Writes per-subject renders, a diff panel where the two differ, and a TSV to cache/visual/tooltip-parity-vanilla/. Run renderVanillaTooltipReferences first. -PtooltipId=one_line"
    group = "visual"
}

// The vanilla sweeps' one JVM. Each producer above is its sweep's slot in it: it depends on this,
// and the run takes whichever of them the invocation scheduled, so naming one sweep runs that one
// and naming this alone runs nothing.
register<JavaExec>("vanillaSweepRun") {
    description = "Runs, in one JVM over one renderer context, the vanilla parity sweeps whose producers the invocation schedules - entityParityVanilla, blockParityVanilla and the rest of the sweeps over vanilla's own client options. Name the sweeps rather than this: alone it runs none."
    mainClass.set("lib.minecraft.renderer.sweep.SweepBundle")
    classpath = sourceSets["visual"].runtimeClasspath
    val plan = layout.buildDirectory.file("parity/vanilla-sweeps.json").get().asFile
    val status = layout.buildDirectory.file("parity/vanilla-sweeps.tsv").get().asFile
    args(plan.absolutePath, status.absolutePath)
    // A sweep that fails is reported by its own producer, which reads the status; the JVM's exit value
    // is read there too, for the sweeps it never reached.
    isIgnoreExitValue = true
    // Read at configuration, as each sweep's own task once read it, so a hand run's scope rides into
    // the plan the JVM is handed.
    val scopes = vanillaSweeps.mapValues { (_, sweep) -> sweep.scopedBy?.let { project.findProperty(it) as String? } }
    val scheduled = mutableListOf<String>()
    gradle.taskGraph.whenReady {
        scheduled += vanillaSweeps.keys.filter { producer -> hasTask(project.tasks.getByName(producer)) }
    }
    val startedAt = AtomicLong()
    doFirst {
        plan.parentFile.mkdirs()
        status.delete()
        vanillaSweepWallMs.clear()
        vanillaSweepFailures.clear()
        plan.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(scheduled.map { producer ->
            val sweep = vanillaSweeps.getValue(producer)
            mapOf(
                "key" to producer,
                "writer" to sweep.writer,
                "args" to sweep.leading + listOfNotNull(scopes[producer]),
                "log" to vanillaSweepLogs[producer]?.absolutePath)
        })))
        startedAt.set(System.nanoTime())
    }
    doLast {
        val wall = (System.nanoTime() - startedAt.get()) / 1_000_000L
        val reported = if (status.isFile)
            status.readLines().filter(String::isNotBlank).map { it.split('\t', limit = 4) }.associateBy { it[0] }
        else emptyMap()
        val exit = executionResult.get().exitValue
        scheduled.forEach { producer ->
            val line = reported[producer]
            if (line == null) {
                vanillaSweepFailures[producer] = "the JVM exited $exit before its sweep reported"
            } else {
                vanillaSweepWallMs[producer] = line[2].toLong()
                if (line[1] != "ok") vanillaSweepFailures[producer] = line.getOrElse(3) { "" }
            }
        }
        // The JVM's start, the client acquisition and the context load are no one sweep's, so every
        // sweep that reported carries an equal share of them - the first the division's remainder -
        // and the times sum to the JVM's own wall. Equal rather than all on one, so what a sweep
        // records does not hang on where the schedule put it: a later plan naming every sweep prices
        // the shared start once, and one naming fewer prices their shares of it.
        val timed = scheduled.filter { it in vanillaSweepWallMs }
        if (timed.isNotEmpty()) {
            val shared = (wall - timed.sumOf { vanillaSweepWallMs.getValue(it) }).coerceAtLeast(0L)
            timed.forEachIndexed { at, producer ->
                val share = shared / timed.size + if (at == 0) shared % timed.size else 0L
                vanillaSweepWallMs[producer] = vanillaSweepWallMs.getValue(producer) + share
            }
        }
    }
}

vanillaSweeps.keys.forEach { producer ->
    named(producer) {
        dependsOn("vanillaSweepRun")
        doLast {
            vanillaSweepFailures[name]?.let { throw GradleException("Sweep '$name' failed in vanillaSweepRun: $it") }
        }
    }
}

register<JavaExec>("tooltipHypixelSkyblockParityVanilla") {
    description = "Per-subject tooltip parity report over the hypixel-skyblock pack: renders the tooltip roster's hypixel-skyblock section through Java TextRenderer with the packs the harness handshake at cache/.../pack-tooltips/hypixel-skyblock/stack.json names, against the client's drawings beside it, whole and as their box alone. Refuses first a configuration whose chrome sprites the renderer resolves from another pack, other bytes or another scaling than the client did. Writes per-subject renders, a diff panel where the two differ, and a TSV to cache/visual/tooltip-hypixel-skyblock-parity-vanilla/. Run renderVanillaPackTooltipsHypixelSkyblock first. -PtooltipId=one_line"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.sweep.TooltipParitySweep")
    classpath = sourceSets["visual"].runtimeClasspath
    val tooltipId = project.findProperty("tooltipId") as String?
    args = listOf("hypixel-skyblock") + listOfNotNull(tooltipId)
}

register<JavaExec>("packIconParityVanilla") {
    description = "Per-stack parity report for the block items a resource pack shadows: the renderer's GUI_ICON of every pack-icon roster stack, the roster's packs loaded, against the client's slot drawing of the same stack at cache/.../pack-icons/. Writes per-subject renders, a diff panel where the two differ, and a TSV to cache/visual/pack-icon-parity-vanilla/. Run renderVanillaPackIcons first. -PitemId=minecraft:oak_log"
    group = "visual"
    mainClass.set("lib.minecraft.renderer.sweep.PackIconParity")
    classpath = sourceSets["visual"].runtimeClasspath
    val itemId = project.findProperty("itemId") as String?
    args = if (itemId != null) listOf(itemId) else listOf()
}

register<JavaExec>("packSourceParityVanilla") {
    description = "Pack-resolution parity: loads the packs renderVanillaPackSources recorded, from the same folder in the same order, and compares which pack supplies each resource under atlases/, blockstates/, equipment/, items/, models/ and textures/, and with what bytes, against the client's own answer at cache/.../pack-sources/sources.json. Writes cache/visual/pack-source-parity-vanilla/report.json. Run renderVanillaPackSources first."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.dump.PackSourceParity")
    classpath = sourceSets["visual"].runtimeClasspath
}

register<JavaExec>("fluidRenderer") {
    description = "Renders FluidRenderer's water and lava - iso static and animated, a sloped flow, biome variants and an override - to cache/visual/fluid-renderer/, the tree manifest.fluid hashes. The flat 2D face is not written here: pin.fluid-crc holds its code path, frame by frame, at a canvas of its own."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.FluidRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
}

register<JavaExec>("portalRenderer") {
    description = "Renders PortalRenderer's end_portal and end_gateway - an iso static, and an iso and a 2D animated strip sampled three times a tick - to cache/visual/portal-renderer/, the tree manifest.portal hashes. The flat 2D static is not written here: pin.portal-crc holds its code path, at a canvas of its own. -PportalLattice also writes each strip sampled once a tick, to cache/visual/portal-lattice/ outside that tree - the render that tells a sub-tick frame from a duplicate - and a run given it is not captured."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.PortalRenderDriver")
    classpath = sourceSets["visual"].runtimeClasspath
    if (project.hasProperty("portalLattice")) args("lattice")
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
// writes build/atlas/, outside the root entirely, and the reach map registers the atlas as no
// artifact.
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
    // No group: it is visualSweepSet's first act, and a task of its own so a hand run of the
    // aggregator and a capture both take it the same way.
    description = "Erases the sub-trees visualSweepSet produces, so manifest.visual's population is what the run wrote."
    delete(visualSweepProducers.values.map { "cache/visual/$it" })
}

// The producers' drivers in ONE JVM over ONE context, each handed the arguments its own task hands
// its main - so the default the task reads off a -P property reaches the render here as it does
// there, and what a driver writes here is what its own task writes. The JVM start, the client
// acquisition and the context load are paid once rather than once a producer. Each producer stays a
// task of its own for a hand run, which is its own JVM, and a driver that throws here is reported
// once every other one has run, so its siblings' output is still captured.
register<JavaExec>("visualSweepSet") {
    description = "Runs, in one JVM over one renderer context, the visual renders whose output cache/visual sub-tree no other parity artifact covers - the producer of manifest.visual."
    group = "visual"
    mainClass.set("lib.minecraft.renderer.driver.DriverBundle")
    classpath = sourceSets["visual"].runtimeClasspath
    // The clear runs first. Without it the member sub-trees accumulate across sessions and the
    // artifact's population becomes a function of session history: measured over the six members
    // of the day at 255 files where the run itself wrote 153, with entity-render-3d 90 fresh beside
    // 14 stale and block-render-3d 35 beside 83.
    dependsOn("visualSweepClean")
    // Each driver as its class name, its argument count and its arguments, in the map's order.
    args(visualSweepProducers.keys.map { project.tasks.named<JavaExec>(it).get() }
        .flatMap { listOf(it.mainClass.get(), it.args.orEmpty().size.toString()) + it.args.orEmpty() })
}

// manifest.player-raw's producer. Both sweeps rescale both sides before diffing, so their delta is
// a LOOK gauge; the raw java.png each writes beside it is not, and this is what captures the two
// sweeps' renders together.
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
