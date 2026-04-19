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
// dev.sbs.renderer.tensor.Vector3fOps / Matrix4fOps in ModelEngine's Pass 1 and by
// PortalRenderer's layer-transform inner loop. The incubator module must be added to
// the module path at both compile time AND every JVM invocation that loads our code
// (test, JavaExec tooling tasks, JMH forks) - missing it anywhere produces a clean
// class-not-found-at-load failure rather than silent fallback.
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

    // Simplified Libraries (extracted to github.com/simplified-dev)
    api("com.github.simplified-dev:collections") { version { strictly("c399e1dad3") } }
    api("com.github.simplified-dev:utils") { version { strictly("36b2a338ce") } }
    api("com.github.simplified-dev:image") { version { strictly("ba0785c409") } }
    api("com.github.simplified-dev:gson-extras:master-SNAPSHOT")
    api("com.github.simplified-dev:client:master-SNAPSHOT")

    // Minecraft-Library (extracted to github.com/minecraft-library)
    // Owns dev.sbs.renderer.text.**, dev.sbs.renderer.text.font.**, and the
    // RendererException / FontException base classes that the remaining asset-renderer
    // exceptions still extend.
    api("com.github.minecraft-library:text:master-SNAPSHOT")

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
        description = "Generates a block/item atlas PNG + coordinates JSON."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingAtlas")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlas") {
        description = "Slices build/atlas/atlas.png by atlas.json, flags blank tiles to build/atlas/missing.json."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlasTask10") {
        description = "Writes a mini atlas containing only Task 10 (blockstate-only) additions to build/atlas/blockstate_only/."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath, "--source-filter=blockstate_only")
    }

    register<JavaExec>("blockTints") {
        description = "Parses BlockColors out of the cached client jar via ASM and rewrites src/main/resources/lib/minecraft/renderer/block_tints.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingBlockTints")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("entityModels") {
        description = "Downloads the Bedrock Edition vanilla resource pack and generates src/main/resources/lib/minecraft/renderer/entity_models.json from .geo.json files. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingEntityModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockEntities") {
        description = "Parses block entity model classes (chest, sign, bed, etc.) from the client jar via ASM and generates src/main/resources/lib/minecraft/renderer/block_entities.json."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingBlockEntities")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("colorMaps") {
        description = "Reads vanilla biome colormap PNGs and generates src/main/resources/lib/minecraft/renderer/color_maps.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingColorMaps")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("potionColors") {
        description = "Parses MobEffects out of the cached client jar via ASM and rewrites src/main/resources/lib/minecraft/renderer/potion_colors.json. Run on a Minecraft version bump."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.ToolingPotionColors")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("testRender") {
        description = "Renders blocks to cache/test-render/ for visual inspection. -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2"
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestRenderMain")
        classpath = sourceSets["main"].runtimeClasspath
        val blockId = project.findProperty("blockId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
        val ssaa = (project.findProperty("ssaa") as String?) ?: "2"
        args = if (blockId != null) listOf(blockId, renderSize, ssaa) else listOf()
    }

    register<JavaExec>("testRenderItem") {
        description = "Renders items to cache/test-render-item/ for visual inspection. -PitemId=minecraft:diamond_sword -PrenderSize=256"
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestRenderItemMain")
        classpath = sourceSets["main"].runtimeClasspath
        val itemId = project.findProperty("itemId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        args = if (itemId != null) listOf(itemId, renderSize) else listOf()
    }

    register<JavaExec>("testBed") {
        description = "Renders red_bed and white_bed at 1024x1024 for visual comparison. -PrenderSize=1024"
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestBedMain")
        classpath = sourceSets["main"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "1024"
        args = listOf(renderSize)
    }

    register<JavaExec>("testLore") {
        description = "Renders a pair of SkyBlock-style lore tooltips to cache/test-lore/ for visual inspection."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestLoreMain")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("testStackCount") {
        description = "Renders ItemStackKit.drawStackCount over a grey backdrop at several sizes. Use -Plabel=<tag> to write to cache/test-stack-count/<tag>/ or -Pdiff=A,B to pixel-diff two labels."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestStackCountMain")
        classpath = sourceSets["main"].runtimeClasspath
        val label = project.findProperty("label") as String?
        val diff = project.findProperty("diff") as String?
        args = if (diff != null) listOf("diff=$diff") else if (label != null) listOf(label) else listOf()
    }

    register<JavaExec>("testFluid") {
        description = "Renders every FluidRenderer code path (water/lava, iso/2D, static/animated, biome variants, override) to cache/test-fluid/ for visual inspection."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestFluidMain")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("testPortal") {
        description = "Renders every PortalRenderer code path (end_portal/end_gateway, iso/2D, static/animated) to cache/test-portal/ for visual inspection."
        group = "tooling"
        mainClass.set("dev.sbs.renderer.tooling.TestPortalMain")
        classpath = sourceSets["main"].runtimeClasspath
    }

    // `./gradlew fonts` now lives in the minecraft-text build at
    // W:/Workspace/Java/Minecraft-Library/minecraft-text. Run it from there when a
    // Minecraft version bump requires regenerating the OTF files.
}

// JMH benchmark harness. Benchmarks live in src/jmh/java and are run with
// `./gradlew :asset-renderer:jmh`. Each Tier 1-3 parallelization task records
// before/after results against the benchmarks in dev.sbs.renderer.bench.
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
