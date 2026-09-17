plugins {
    id("java")
}

group = "lib.minecraft"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots")
    maven(url = "https://jitpack.io")
}

dependencies {
    // The renderer's own production types, resolved against the working tree because this is a
    // subproject of that build rather than a build beside it. A generator that re-declares a renderer
    // type drifts from it; one that resolves it cannot.
    // Client-jar acquisition comes with it: `lib.minecraft.renderer.client` is part of that project
    // now, so the coordinate this build used to name resolves to nothing and is not needed.
    implementation(project(":"))

    // The @Parity vocabulary, resolved the same way. `compileOnly` on both source sets because
    // retention is SOURCE: javac needs the types to resolve a declaration and drops the descriptor
    // before it writes the class file, so nothing here can read one at run time and no emitted table
    // is a function of it.
    compileOnly("lib.minecraft:asset-renderer-parity:0.1.0")
    testCompileOnly("lib.minecraft:asset-renderer-parity:0.1.0")

    // 9.8 added support for Java 25 class files (major version 69), which the Minecraft version named
    // by the harness's gradle.properties emits. It is declared here and nowhere else, which is what
    // keeps it off every renderer classpath and out of the published JAR.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")

    compileOnly(libs.simplified.annotations)
    annotationProcessor(libs.simplified.annotations)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.hamcrest)
    testCompileOnly(libs.simplified.annotations)
    testAnnotationProcessor(libs.simplified.annotations)
}

// The renderer's tensor types reference jdk.incubator.vector, so resolving them here needs the module
// for the same reason the renderer's own compilation does. Missing it is a class-not-found at load,
// never a silent fallback, which is why it goes on every compilation and every JVM this build starts
// rather than only where a lane is read.
val addVectorModuleArg = "--add-modules=jdk.incubator.vector"

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add(addVectorModuleArg)
}

/**
 * Where a flow writes its table.
 *
 * <p>Defaults to the renderer's own resource tree, which is what makes a run's output the shipped
 * bytes and a run therefore dirty tracked files - the signal the parity gate reads. `-PtoolingOut`
 * redirects the whole set somewhere else, which is how an A/B is taken without touching the tree:
 * capture the clean side into one directory, apply the change, capture into another, diff.
 *
 * <p>Relative paths resolve against the renderer root rather than this build, so the default reads
 * the same here as it does in every other file that names it.
 */
val toolingOutDir: String =
    (findProperty("toolingOut") as String?)?.takeIf { it.isNotBlank() }
        ?: "src/main/resources/lib/minecraft/renderer"

/** The renderer root - this build sits one level under it. */
val rendererRoot: File = layout.projectDirectory.dir("..").asFile

tasks.withType<JavaExec>().configureEach {
    jvmArgs(addVectorModuleArg)
    // Every flow resolves its output and its cache against the renderer root, so a path typed in
    // Java, in the renderer's build file and in this one all mean the same directory.
    workingDir = rendererRoot
    systemProperty("asset.tooling.out", toolingOutDir)
    // Every `-Dasset.*` in force reaches the flow it was armed for. One build now, so a switch armed
    // on the command line is already on this daemon's own properties; the `-P` spelling is read
    // beside it, because a caller may pass either and the relay used to make them one.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("asset.")) systemProperty(name, value.toString())
    }
    project.properties
        .filterKeys { it.startsWith("asset.") }
        .forEach { (key, value) -> systemProperty(key, value.toString()) }
}

// Every path a test resolves is relative to the renderer root, the same as every flow's.
// There is one Test task here and no tag to filter on: every walk reads the client jar the cache
// already holds and abandons its class where nothing has cached one, so all of them run in `test`
// and the renderer's `check` reaches them through `toolingTest`. A suite of their own is what let
// them go unrun - nothing scheduled it, and an empty tag-filtered run reports success.
// `ToolingJarGuardTest` is what fails when the jar they assume is missing.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(addVectorModuleArg)
    workingDir = rendererRoot
}

/**
 * Registers one generator flow.
 *
 * @param name the task and flow name, which the renderer's parity artifact table names as a producer
 * @param main the flow's entry point
 * @param description what the flow walks and what it emits
 */
fun registerFlow(name: String, main: String, description: String) {
    tasks.register<JavaExec>(name) {
        this.description = description
        group = "tooling"
        mainClass.set(main)
        classpath = sourceSets["main"].runtimeClasspath
    }
}

registerFlow("entityModels", "lib.minecraft.renderer.tooling.ToolingEntityModels",
    "tooling: walks the client jar and generates entity_models.json + entity_geometry.json.")
registerFlow("blockModels", "lib.minecraft.renderer.tooling.ToolingBlockModels",
    "tooling: walks the client jar and generates block_models.json + block_geometry.json.")
registerFlow("blockDefaults", "lib.minecraft.renderer.tooling.ToolingBlockDefaults",
    "tooling: bytewalks registerDefaultState and generates block_defaults.json (default blockstate per block + unresolved[]).")
registerFlow("blockItems", "lib.minecraft.renderer.tooling.ToolingBlockItems",
    "tooling: walks Items.<clinit> and generates block_items.json (secondary block -> standing block item alias map).")
registerFlow("blockTints", "lib.minecraft.renderer.tooling.ToolingBlockTints",
    "tooling: walks BlockColors.createDefault() and generates block_tints.json (tints + dropped[]).")
registerFlow("potionColors", "lib.minecraft.renderer.tooling.ToolingPotionColors",
    "tooling: walks MobEffects.<clinit> and generates potion_colors.json (effect colours, sorted by id).")
registerFlow("glintItems", "lib.minecraft.renderer.tooling.ToolingGlintItems",
    "tooling: walks Items.<clinit> and generates glint_items.json (always-glinted item ids, sorted).")
registerFlow("colorMaps", "lib.minecraft.renderer.tooling.ToolingColorMaps",
    "tooling: reads the biome colormap PNGs from the jar and generates color_maps.json (base64 big-endian ARGB pixels).")

/** Every flow, in the order the renderer's artifact table lists them. */
val flowNames = listOf(
    "entityModels", "blockModels", "blockDefaults", "blockItems",
    "blockTints", "potionColors", "glintItems", "colorMaps"
)

tasks.register("generateTables") {
    description = "Runs every generator flow. -PtoolingOut=<dir> redirects the whole set; -Pflows=a,b runs a subset."
    group = "tooling"
    val selected = (findProperty("flows") as String?)
        ?.split(',')
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() }
        ?: flowNames
    val unknown = selected.filterNot { it in flowNames }
    if (unknown.isNotEmpty())
        throw GradleException("-Pflows names no such flow: ${unknown.joinToString(", ")}. Known: ${flowNames.joinToString(", ")}")
    dependsOn(selected)
    doLast {
        logger.lifecycle("tooling: ${selected.size} flow(s) wrote to $toolingOutDir")
    }
}
