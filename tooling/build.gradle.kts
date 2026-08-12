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
    // The renderer, resolved through the included build rather than a repository. Tooling reads its
    // client-jar acquisition plus a handful of vocabulary types; nothing in it reads tooling back.
    implementation("lib.minecraft:asset-renderer:0.1.0")

    // 9.8 added support for Java 25 class files (major version 69), which the Minecraft version named
    // by the harness's gradle.properties emits. It is declared here and nowhere else, which is what
    // keeps it off every renderer classpath and out of the published JAR.
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    annotationProcessor(libs.simplified.annotations)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)
    testImplementation(libs.hamcrest)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

// The renderer's SIMD sources reference jdk.incubator.vector, and this build both compiles against
// and launches them. SimdSupport probes for the module and falls back bit-identically when it is
// absent, so the flag is a dev-path choice rather than a correctness one - added for the same reason
// the renderer adds it, that it belongs everywhere it is read.
val addVectorModuleArg = "--add-modules=jdk.incubator.vector"

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(addVectorModuleArg)
    options.encoding = "UTF-8"
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
    // The renderer forwards its own -Dasset.* switches into this build as -P, so a flag armed on the
    // renderer side reaches the flow it was armed for.
    project.properties
        .filterKeys { it.startsWith("asset.") }
        .forEach { (key, value) -> systemProperty(key, value.toString()) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        excludeTags("slow")
    }
    jvmArgs(addVectorModuleArg)
    workingDir = rendererRoot
}

tasks.register<Test>("slowTest") {
    description = "Runs the tooling tests that hit the network or the filesystem cache."
    group = "verification"
    useJUnitPlatform {
        includeTags("slow")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    outputs.upToDateWhen { false }
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
