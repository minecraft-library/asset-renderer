plugins {
    id("java-library")
    id("me.champeau.jmh") version "0.7.2"
    idea
}

group = "lib.minecraft"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// JDK 21 Vector API (jdk.incubator.vector) unlocks FloatVector SIMD math used by
// lib.minecraft.renderer.tensor.Vector3fOps / Matrix4fOps - the package-private SIMD
// implementations that Vector3f.transform / Matrix4f.multiply silently dispatch to in
// ModelEngine's Pass 1 hot path.
//
// The flag is required at compile time (the *Ops sources reference jdk.incubator.vector.*)
// and is also added to every JVM this project starts (Test, JavaExec tooling, JMH) so our
// own dev workflow stays on the SIMD path. Downstream consumers of the published JAR do
// NOT need to add the flag themselves: SimdSupport probes for the module via Class.forName
// at runtime and falls back to a bit-identical scalar implementation when it is absent. The
// two-class split keeps the JVM from ever resolving the incubator imports on a consumer
// JVM that runs without the module.
val addVectorModuleArg = "--add-modules=jdk.incubator.vector"

// Forward every -Dasset.* system property to every forked Test + JavaExec JVM, in ONE place, so any
// future asset.* debug flag (e.g. -Dasset.snap.grid, -Dasset.entity.pixel.dump, -Dasset.glint.itemScale)
// auto-propagates to every task without per-task wiring. The CLI -D lands in the gradle daemon's
// System.getProperties(); the `asset.` prefix keeps gradle-internal properties out of the fork.
fun org.gradle.process.JavaForkOptions.forwardAssetProperties() =
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith("asset.")) systemProperty(key, v.toString())
    }

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(addVectorModuleArg)
}
tasks.withType<Test>().configureEach {
    jvmArgs(addVectorModuleArg)
    forwardAssetProperties()
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs(addVectorModuleArg)
    forwardAssetProperties()
}

repositories {
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots")
    maven(url = "https://jitpack.io")
}

dependencies {
    // Simplified Annotations
    annotationProcessor(libs.simplified.annotations)

    // Lombok Annotations
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)
    // JOML for precision-hunt tests: side-by-side bit-comparisons of our tensor math vs
    // vanilla's actual matrix backend, since vanilla's PoseStack.Pose.pose is org.joml.Matrix4f.
    // Test-only - production code uses our own lib.minecraft.renderer.tensor.Matrix4f.
    testImplementation(libs.joml)

    // Simplified Libraries (extracted to github.com/simplified-dev). Temporarily pinned to
    // explicit master commits while JitPack's master-SNAPSHOT cache catches up across the
    // chain after the upstream collections ConcurrentMap class -> interface migration. Each
    // pin below maps to a fresh JitPack rebuild against the latest collections; revert each
    // line back to master-SNAPSHOT once JitPack's master-SNAPSHOT cache settles.
    // strictly() rejects transitive master-SNAPSHOT resolutions that JitPack hasn't yet
    // bumped to the freshly-rebuilt commits below; without it Gradle's conflict resolver
    // picks the stale SNAPSHOT JAR over our pin and produces NoSuchMethodError at runtime.
    // Each upstream lib also strict-pins its own internal deps to these same hashes so
    // master-SNAPSHOT consumers of any single lib see a consistent transitive chain.
    api("com.github.simplified-dev:collections") { version { strictly("2f2aa58") } }
    api("com.github.simplified-dev:utils") { version { strictly("37dc4a8") } }
    api("com.github.simplified-dev:image") { version { strictly("2341d20") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("f42ee07") } }
    api("com.github.simplified-dev:reflection") { version { strictly("b2cf834") } }
    api("com.github.simplified-dev:client") { version { strictly("5a5d32e") } }

    // Simplified API (extracted to github.com/simplified-api) - typed Feign contract for
    // Mojang's launcher / Piston / textures endpoints, owns all renderer HTTP via Pipeline.
    api("com.github.simplified-api:mojang") { version { strictly("71ec2c9") } }

    // Minecraft-Library (extracted to github.com/minecraft-library)
    // Owns lib.minecraft.text.**, lib.minecraft.text.font.**, and the
    // RendererException / FontException base classes that the remaining asset-renderer
    // exceptions still extend.
    api("com.github.minecraft-library:text") { version { strictly("318157a") } }

    // ASM - used by VanillaTintsLoader to parse net.minecraft.client.color.block.BlockColors
    // straight from the extracted client jar, replacing the previously hand-curated tint table.
    // 9.8 added support for Java 25 class files (major version 69) which 26.1 emits.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")

    // Gson
    api(libs.gson)
}

idea {
    module {
        excludeDirs.addAll(listOf(
            layout.projectDirectory.dir("cache").asFile,
            layout.projectDirectory.dir("texturepacks").asFile
        ))
    }
}

tasks {
    test {
        useJUnitPlatform {
            excludeTags("slow")
        }
    }

    register<Test>("slowTest") {
        description = "Runs slow integration tests that hit the network or the filesystem cache (e.g. downloading the Minecraft client jar)."
        group = "verification"
        useJUnitPlatform {
            includeTags("slow")
        }
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        outputs.upToDateWhen { false }
    }

    withType<JavaExec>().configureEach {
        workingDir = layout.projectDirectory.asFile
    }

    // Dev-time reference snapshots (entity_geometry.upstream.json etc.) sit beside their working
    // counterparts under src/main/resources for easy diffing but never load at runtime - keep
    // them out of the published JAR.
    processResources {
        exclude("**/*.upstream.json")
    }

    // Tooling

    register<JavaExec>("atlas") {
        description = "Generates a block/item atlas PNG + coordinates JSON to build/atlas/."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlas")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlas") {
        description = "Slices build/atlas/atlas.png by atlas.json, flags blank tiles to build/atlas/missing.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlasTask10") {
        description = "Writes a mini atlas containing only blockstate additions to build/atlas/blockstate_only/."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath, "--source-filter=blockstate_only")
    }

    register<JavaExec>("blockTints") {
        description = "Parses BlockColors out of the cached client jar via ASM and rewrites src/main/resources/lib/minecraft/renderer/block_tints.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockTints")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("entityModels") {
        description = "Walks the Java client jar via ASM and generates src/main/resources/lib/minecraft/renderer/entity_models.json + entity_geometry.json (per-entity bone trees + variant metadata). Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingEntityModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockModels") {
        description = "Parses block-entity model classes (chest, sign, bed, etc.) from the client jar via ASM and generates src/main/resources/lib/minecraft/renderer/block_models.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockDefaults") {
        description = "Bytewalks registerDefaultState in the Blocks registry via ASM and rewrites src/main/resources/lib/minecraft/renderer/block_defaults.json (per-block default state key). Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockDefaults")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("colorMaps") {
        description = "Reads vanilla biome colormap PNGs and generates src/main/resources/lib/minecraft/renderer/color_maps.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingColorMaps")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("potionColors") {
        description = "Parses MobEffects out of the cached client jar via ASM and rewrites src/main/resources/lib/minecraft/renderer/potion_colors.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingPotionColors")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("glintItems") {
        description = "Parses the Items registry out of the cached client jar via ASM and rewrites src/main/resources/lib/minecraft/renderer/glint_items.json (always-glinted item ids). Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingGlintItems")
        classpath = sourceSets["main"].runtimeClasspath
    }

    // tooling2 - the rewrite flows (decision 30 naming); outputs land under
    // src/main/resources/lib/minecraft/renderer/v2/, never the legacy top-level JSONs.

    register<JavaExec>("entityModels2") {
        description = "tooling2: walks the client jar and generates src/main/resources/lib/minecraft/renderer/v2/entity_models.json + v2/entity_geometry.json."
        group = "tooling2"
        mainClass.set("lib.minecraft.renderer.tooling2.ToolingEntityModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    // Visual diagnostics - main() entry points in src/test/java/lib/minecraft/renderer/visual/.
    // Run with `./gradlew tasks --group visual` to list. Outputs land under cache/visual/.

    register<JavaExec>("blockRender3D") {
        description = "Renders blocks to cache/visual/block-render-3d/ for visual inspection. -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestBlockRender3D")
        classpath = sourceSets["test"].runtimeClasspath
        val blockId = project.findProperty("blockId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
        val ssaa = (project.findProperty("ssaa") as String?) ?: "2"
        args = if (blockId != null) listOf(blockId, renderSize, ssaa) else listOf()
    }

    register<JavaExec>("projectionSmoke") {
        description = "Renders a block under every GraphicalProjection + facing to cache/visual/projection-smoke/. -PblockId=minecraft:tnt -PrenderSize=512"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestProjectionSmoke")
        classpath = sourceSets["test"].runtimeClasspath
        val blockId = (project.findProperty("blockId") as String?) ?: "minecraft:tnt"
        val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
        args = listOf(blockId, renderSize)
    }

    register<JavaExec>("itemRender2D") {
        description = "Renders items to cache/visual/item-render-2d/ for visual inspection. -PitemId=minecraft:diamond_sword -PrenderSize=256 -Ptype=gui|held -Psupersample=2 -PantiAlias=true. -Psupersample only affects -Ptype=held (the GUI icon is a sprite blit and ignores it); -PantiAlias (FXAA) applies to both."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestItemRender2D")
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

    register<JavaExec>("bedParity") {
        description = "Renders beds and chest via pipeline vs mc-assets ground truth side-by-side at cache/visual/bed-parity/. -PrenderSize=1024"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestBedParity")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "1024"
        args = listOf(renderSize)
    }

    register<JavaExec>("loreTooltip") {
        description = "Renders a pair of SkyBlock-style lore tooltips to cache/visual/lore-tooltip/ for visual inspection."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestLoreTooltip")
        classpath = sourceSets["test"].runtimeClasspath
    }

    register<JavaExec>("stackCountBadge") {
        description = "Renders ItemStackKit.drawStackCount over a grey backdrop at several sizes. Use -Plabel=<tag> to write to cache/visual/stack-count-badge/<tag>/ or -Pdiff=A,B to pixel-diff two labels."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestStackCountBadge")
        classpath = sourceSets["test"].runtimeClasspath
        val label = project.findProperty("label") as String?
        val diff = project.findProperty("diff") as String?
        args = if (diff != null) listOf("diff=$diff") else if (label != null) listOf(label) else listOf()
    }

    register<JavaExec>("entityRender3D") {
        description = "Renders every entity in entity_models.json via EntityRenderer (3D) to cache/visual/entity-render-3d/ for visual inspection. -PrenderSize=512 -PentityId=minecraft:zombie -Pprojection=ISOMETRIC"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestEntityRender3D")
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
        mainClass.set("lib.minecraft.renderer.visual.TestEntityProjections")
        classpath = sourceSets["test"].runtimeClasspath
        val entityId = project.findProperty("entityId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        args = buildList {
            add(entityId ?: "")
            add(renderSize)
        }
    }

    register<JavaExec>("entityParityVanilla") {
        description = "Per-entity parity report comparing Java pipeline vs vanilla-reference-harness ground truth (mean ARGB delta + per-entity vanilla/java/diff PNGs). Output -> cache/visual/entity-parity-vanilla/<entity>/. Run :asset-renderer:renderVanillaReferences first if the cache is missing. -PentityId=minecraft:zombie"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestEntityParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
        val entityId = project.findProperty("entityId") as String?
        args = if (entityId != null) listOf(entityId) else listOf()
        // -Dasset.* sysprops (e.g. -Dasset.entity.pixel.dump, -Dasset.entity.bounds.dump, -Dasset.snap.grid)
        // auto-forward to this fork via the global JavaExec forwarder near the top of this file.
    }

    register<JavaExec>("blockParityVanilla") {
        description = "Per-block parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/block-parity-vanilla/<block>/. Run :asset-renderer:renderVanillaReferences first if the cache is missing. -PblockId=minecraft:tnt"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestBlockParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
        val blockId = project.findProperty("blockId") as String?
        args = if (blockId != null) listOf(blockId) else listOf()
        // -Dasset.* sysprops (e.g. -Dasset.snap.grid, -Dasset.entity.pixel.dump) auto-forward to this
        // fork via the global JavaExec forwarder near the top of this file.
    }

    register<JavaExec>("itemParityVanilla") {
        description = "Per-item parity report comparing Java pipeline vs vanilla-reference-harness ground truth. Output -> cache/visual/item-parity-vanilla/<item>/. Run :asset-renderer:renderVanillaReferences first if the cache is missing. -PitemId=minecraft:diamond_sword"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestItemParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
        val itemId = project.findProperty("itemId") as String?
        args = if (itemId != null) listOf(itemId) else listOf()
    }

    register<JavaExec>("glintParityVanilla") {
        description = "Animated enchantment-glint parity: renders the 7 always-foil GUI items (+ 4 worn leather-armor diagnostics) frame-by-frame against the harness glint references at cache/.../references/glint/. Writes per-frame diffs, contact sheets, GIFs, and a TSV to cache/visual/glint-parity-vanilla/. Run :asset-renderer:renderVanillaGlintReferences first. -PitemId=minecraft:nether_star"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestGlintParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
        val itemId = project.findProperty("itemId") as String?
        args = if (itemId != null) listOf(itemId) else listOf()
        // -Dasset.glint.* sysprops (e.g. -Dasset.glint.itemScale=1.0) auto-forward to this fork via the
        // global JavaExec forwarder near the top of this file.
    }

    register<JavaExec>("fluidRenderer") {
        description = "Renders every FluidRenderer code path (water/lava, iso/2D, static/animated, biome variants, override) to cache/visual/fluid-renderer/ for visual inspection."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestFluidRenderer")
        classpath = sourceSets["test"].runtimeClasspath
    }

    register<JavaExec>("portalRenderer") {
        description = "Renders every PortalRenderer code path (end_portal/end_gateway, iso/2D, static/animated) to cache/visual/portal-renderer/ for visual inspection."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestPortalRenderer")
        classpath = sourceSets["test"].runtimeClasspath
    }

    register<JavaExec>("packOverlay") {
        description = "Downloads the Defrosted 16x pack and renders items / tools / armor side-by-side (vanilla vs pack) at cache/visual/pack-overlay/. -PrenderSize=256"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestPackOverlay")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        args = listOf(renderSize)
    }

    register<JavaExec>("redstoneTints") {
        description = "Renders the 16 redstone power-level swatches twice (vanilla / synthetic-override pack) to cache/visual/redstone-tints/. -PrenderSize=64"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestRedstoneTints")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "64"
        args = listOf(renderSize)
    }

    // Delegates to the sibling vanilla-reference-harness Fabric mod, which boots a
    // headless Minecraft 26.1.2 client, renders every block + living entity to PNG
    // via the in-game vanilla pipeline, then exits. Output lands under
    // cache/asset-renderer/vanilla/<version>/references/{blocks,entities}/ so
    // parity tests can diff against ground truth.
    //
    // Run on a Minecraft version bump (~5 minutes total). Regenerated PNGs are
    // gitignored via the cache/ exclusion.
    //
    // Filter to a subset for iteration:
    //   ./gradlew :asset-renderer:renderVanillaReferences -PrefharnessTargets=minecraft:cow,minecraft:stone
    register<Exec>("renderVanillaReferences") {
        description = "Runs the sibling vanilla-reference-harness mod and copies its PNG output into asset-renderer's vanilla cache. Re-run on Minecraft version bump."
        group = "tooling"
        workingDir = file("../vanilla-reference-harness")
        // The sibling project's renderReferences run-config writes PNGs into its own
        // build/refharness-output/. We point it at asset-renderer's vanilla cache
        // directly via -Drefharness.outputDir, no copy step needed.
        val outputDir = layout.projectDirectory.dir("cache/asset-renderer/vanilla/26.1/references")
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val gradlewPath = file("../vanilla-reference-harness/${if (isWindows) "gradlew.bat" else "gradlew"}").absolutePath
        val baseArgs = mutableListOf<String>()
        if (isWindows) {
            baseArgs.add("cmd")
            baseArgs.add("/c")
        }
        baseArgs.add(gradlewPath)
        baseArgs.add("runRenderReferences")
        baseArgs.add("--no-daemon")
        // -P (project property) propagates through the sibling's build.gradle to its
        // Loom run config, which sets the system property the mod actually reads.
        // -D would only affect the wrapper's JVM, not the forked Minecraft process.
        baseArgs.add("-PrefharnessOutputDir=${outputDir.asFile.absolutePath}")
        if (project.hasProperty("refharnessTargets")) {
            baseArgs.add("-PrefharnessTargets=${project.property("refharnessTargets")}")
        }
        if (project.hasProperty("refharnessPitchRollSweep")) {
            baseArgs.add("-PrefharnessPitchRollSweep=${project.property("refharnessPitchRollSweep")}")
        }
        commandLine = baseArgs
        doFirst {
            println("renderVanillaReferences: writing to ${outputDir.asFile.absolutePath}")
            outputDir.asFile.mkdirs()
        }
    }

    // Glint-only variant: drives the harness with -PrefharnessGlintOnly=true so it renders ONLY the
    // animated-glint references (7 GUI items + 4 worn leather-armor diagnostics) under
    // references/glint/<id>/frame_NNN.png, skipping the ~5-minute full sweep. Then run glintParityVanilla.
    //   ./gradlew :asset-renderer:renderVanillaGlintReferences [-PrefharnessTargets=minecraft:nether_star]
    register<Exec>("renderVanillaGlintReferences") {
        description = "Runs the sibling vanilla-reference-harness mod in glint-only mode, writing animated glint references to asset-renderer's vanilla cache (references/glint/). Then run glintParityVanilla."
        group = "tooling"
        workingDir = file("../vanilla-reference-harness")
        val outputDir = layout.projectDirectory.dir("cache/asset-renderer/vanilla/26.1/references")
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val gradlewPath = file("../vanilla-reference-harness/${if (isWindows) "gradlew.bat" else "gradlew"}").absolutePath
        val baseArgs = mutableListOf<String>()
        if (isWindows) {
            baseArgs.add("cmd")
            baseArgs.add("/c")
        }
        baseArgs.add(gradlewPath)
        baseArgs.add("runRenderReferences")
        baseArgs.add("--no-daemon")
        baseArgs.add("-PrefharnessOutputDir=${outputDir.asFile.absolutePath}")
        baseArgs.add("-PrefharnessGlintOnly=true")
        if (project.hasProperty("refharnessTargets")) {
            baseArgs.add("-PrefharnessTargets=${project.property("refharnessTargets")}")
        }
        commandLine = baseArgs
        doFirst {
            println("renderVanillaGlintReferences: writing glint refs to ${outputDir.asFile.absolutePath}/glint")
            outputDir.asFile.mkdirs()
        }
    }

    // `./gradlew fonts` now lives in the minecraft-text build at
    // W:/Workspace/Java/Minecraft-Library/minecraft-text. Run it from there when a
    // Minecraft version bump requires regenerating the OTF files.
}

// JMH benchmark harness. Benchmarks live in src/jmh/java and are run with
// `./gradlew :asset-renderer:jmh`. Each Tier 1-3 parallelization task records
// before/after results against the benchmarks in lib.minecraft.renderer.bench.
dependencies {
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
    jmhCompileOnly(libs.lombok)
    jmhAnnotationProcessor(libs.lombok)
}

jmh {
    // Iteration counts default to the plan spec (3 warmup + 5 measurement + 2 forks).
    // Quick signal runs override via -PjmhWarmup -PjmhIters -PjmhForks; production
    // parity runs take the defaults.
    warmupIterations.set(((project.findProperty("jmhWarmup") as String?)?.toInt()) ?: 3)
    iterations.set(((project.findProperty("jmhIters") as String?)?.toInt()) ?: 5)
    fork.set(((project.findProperty("jmhForks") as String?)?.toInt()) ?: 2)
    timeUnit.set("ms")
    benchmarkMode.set(listOf("avgt"))
    // Include pattern honours -PjmhInclude=...; smoke-runs override with e.g.
    // -PjmhInclude=FluidAnimationBenchmark to limit to a single class.
    includes.set(listOf((project.findProperty("jmhInclude") as String?) ?: ".*"))
    // Optional JMH built-in profilers, comma-separated. Examples:
    //   -PjmhProfilers=gc           GC stats (allocation rate, GC time %)
    //   -PjmhProfilers=stack        sampling stack profile
    //   -PjmhProfilers=gc,stack     both
    (project.findProperty("jmhProfilers") as String?)?.let { spec ->
        profilers.set(spec.split(","))
    }
    // Keep the JVM small for benchmarks so allocator/GC behaviour is representative
    // of the CLI workload rather than a bloated dev-only heap. Include the incubator
    // Vector API module so FloatVector classes resolve in JMH forks.
    jvmArgs.set(listOf("-Xmx2g", "--add-modules=jdk.incubator.vector"))
}
