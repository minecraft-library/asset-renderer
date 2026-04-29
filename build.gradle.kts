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

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(addVectorModuleArg)
}
tasks.withType<Test>().configureEach {
    jvmArgs(addVectorModuleArg)
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs(addVectorModuleArg)
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

    // Simplified Libraries (extracted to github.com/simplified-dev). Temporarily pinned to
    // explicit master commits so JitPack rebuilds each artifact against the latest
    // collections snapshot (ConcurrentMap was flipped from class to interface; cached
    // master-SNAPSHOT jars of gson-extras / reflection were still bound to the old class
    // form, surfacing as IncompatibleClassChangeError under Gson reflection). Revert each
    // line back to master-SNAPSHOT once JitPack's master-SNAPSHOT cache catches up.
    api("com.github.simplified-dev:collections:afa6fb1")
    api("com.github.simplified-dev:utils:70529fc")
    api("com.github.simplified-dev:image:31d5c38")
    // gson-extras:1b65ed3 is master HEAD but JitPack failed to build it (SDKMan Java 21
    // install glitch); 939e783 is the prior commit, also carries the Concurrent* factory
    // wiring needed against the new collections interface.
    api("com.github.simplified-dev:gson-extras:939e783")
    // reflection is pulled in transitively by gson-extras as master-SNAPSHOT - JitPack's
    // stale cache is the binary that triggers IncompatibleClassChangeError against the
    // new collections interface. Direct-pinning to a freshly-built commit overrides the
    // transitive resolution.
    api("com.github.simplified-dev:reflection:2fb4888")
    api("com.github.simplified-dev:client:2ac6479")

    // Simplified API (extracted to github.com/simplified-api) - typed Feign contract for
    // Mojang's launcher / Piston / textures endpoints, owns all renderer HTTP via AssetPipeline.
    // Pinned for the same reason as the simplified-dev block above.
    api("com.github.simplified-api:mojang:46af96a")

    // Minecraft-Library (extracted to github.com/minecraft-library)
    // Owns lib.minecraft.text.**, lib.minecraft.text.font.**, and the
    // RendererException / FontException base classes that the remaining asset-renderer
    // exceptions still extend. Pinned for the same reason as the simplified-dev block above.
    api("com.github.minecraft-library:text:5968fd9")

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
        description = "Downloads the Bedrock Edition vanilla resource pack and generates src/main/resources/lib/minecraft/renderer/entity_models.json from .geo.json files. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingEntityModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("bindPoses") {
        description = "Parses Java Edition Model subclasses via ASM and generates src/main/resources/lib/minecraft/renderer/entity_bind_poses.json - per-bone static rotations the Bedrock 1.21+ geometry expects an animation to apply. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBindPoses")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockEntities") {
        description = "Parses block entity model classes (chest, sign, bed, etc.) from the client jar via ASM and generates src/main/resources/lib/minecraft/renderer/block_entities.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockEntities")
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

    register<JavaExec>("itemRender2D") {
        description = "Renders items to cache/visual/item-render-2d/ for visual inspection. -PitemId=minecraft:diamond_sword -PrenderSize=256"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestItemRender2D")
        classpath = sourceSets["test"].runtimeClasspath
        val itemId = project.findProperty("itemId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        args = if (itemId != null) listOf(itemId, renderSize) else listOf()
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
        description = "Renders every entity in entity_models.json via EntityRenderer (3D) to cache/visual/entity-render-3d/ for visual inspection. -PrenderSize=512 -PentityId=minecraft:zombie"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestEntityRender3D")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
        val entityId = project.findProperty("entityId") as String?
        args = if (entityId != null) listOf(renderSize, entityId) else listOf(renderSize)
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
