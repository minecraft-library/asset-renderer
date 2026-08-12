// The generator flows and their suite, as shells into the tooling build's own wrapper. That build
// resolves nothing from this one, so nothing here compiles or runs its sources - these tasks are the
// whole of how this build reaches them. Applied from the root build script.
//
// The task NAMES are the contract: the parity artifact table lists them as the shipped tables'
// producers and resolves each with `named`, so a shim keeps the name its flow had.

/**
 * Every `-Dasset.*` in force, as the root script resolved them, forwarded to each flow so a flag
 * armed on this side reaches the run it was armed for. Read as a value because a function cannot
 * cross an `apply(from = ...)` boundary.
 */
@Suppress("UNCHECKED_CAST")
val assetFlagsInForce: Map<String, String> = extra["assetFlagsInForce"] as Map<String, String>

// The bytecode-walking generators are their own Gradle build under tooling/, a sibling of the
// harness. Nothing here reads them: ASM and the walkers are on no renderer classpath and in no
// published JAR, and this build reaches them only by shelling into their wrapper, the shape
// harnessClasses already uses.
//
// The directory that tooling writes its tables into is tooling's own `-PtoolingOut`, defaulting to
// this project's resource tree. Every flow task below forwards it.
val toolingDir: Directory = layout.projectDirectory.dir("tooling")

tasks {
    // The tooling build's own suite, through its wrapper. Nothing in this build compiles or runs
    // those sources, so without this edge a mistake in them waits for a version bump - the same hole
    // harnessClasses closes for the harness, and the reason `check` schedules both.
    register<Exec>("toolingTest") {
        description = "Runs the tooling build's unit tests through its own wrapper - the ASM walkers, the kernel and the policy purity check."
        group = "verification"
        workingDir = toolingDir.asFile
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val gradlew = toolingDir.file(if (isWindows) "gradlew.bat" else "gradlew").asFile.absolutePath
        commandLine = buildList {
            if (isWindows) { add("cmd"); add("/c") }
            add(gradlew)
            add("test")
            add("--no-daemon")
        }
    }

    // tooling - the generator flows, each an Exec into the tooling build's own wrapper. The task
    // NAMES are the contract: parityArtifacts lists them as manifest.tooling-tables' producers and
    // the parity graph resolves each with named(<string>), so a shim under the same name is what
    // keeps the artifact table, the producer tee and the wall-time stamp working unchanged.
    //
    // -PtoolingOut forwards through to where the flows write, defaulting on the far side to this
    // project's resource tree. Every -Dasset.* switch in force forwards with it, so a flag armed
    // here reaches the flow.

    /**
     * Registers one generator flow as a shell into the tooling build.
     *
     * @param name the flow's task name on both sides
     * @param description what the flow walks and what it emits
     */
    fun registerToolingFlow(name: String, description: String) {
        register<Exec>(name) {
            this.description = description
            group = "tooling"
            workingDir = toolingDir.asFile
            val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
            val gradlew = toolingDir.file(if (isWindows) "gradlew.bat" else "gradlew").asFile.absolutePath
            val forwarded = assetFlagsInForce.map { (key, value) -> "-P$key=$value" }
            val outDir = providers.gradleProperty("toolingOut").orNull
            commandLine = buildList {
                if (isWindows) { add("cmd"); add("/c") }
                add(gradlew)
                add(name)
                if (outDir != null) add("-PtoolingOut=$outDir")
                addAll(forwarded)
                // As the harness runs are launched, and for the same reason: a separate build with
                // its own toolchain must not leave a second daemon behind it.
                add("--no-daemon")
            }
        }
    }

    registerToolingFlow("entityModels",
        "tooling: walks the client jar and generates src/main/resources/lib/minecraft/renderer/entity_models.json + entity_geometry.json.")
    registerToolingFlow("blockModels",
        "tooling: walks the client jar and generates src/main/resources/lib/minecraft/renderer/block_models.json + block_geometry.json.")
    registerToolingFlow("blockDefaults",
        "tooling: bytewalks registerDefaultState and generates src/main/resources/lib/minecraft/renderer/block_defaults.json (default blockstate per block + unresolved[]).")
    registerToolingFlow("blockItems",
        "tooling: walks Items.<clinit> and generates src/main/resources/lib/minecraft/renderer/block_items.json (secondary block -> standing block item alias map).")
    registerToolingFlow("blockTints",
        "tooling: walks BlockColors.createDefault() and generates src/main/resources/lib/minecraft/renderer/block_tints.json (tints + dropped[]).")
    registerToolingFlow("potionColors",
        "tooling: walks MobEffects.<clinit> and generates src/main/resources/lib/minecraft/renderer/potion_colors.json (effect colours, sorted by id).")
    registerToolingFlow("glintItems",
        "tooling: walks Items.<clinit> and generates src/main/resources/lib/minecraft/renderer/glint_items.json (always-glinted item ids, sorted).")
    registerToolingFlow("colorMaps",
        "tooling: reads the biome colormap PNGs from the jar and generates src/main/resources/lib/minecraft/renderer/color_maps.json (base64 big-endian ARGB pixels).")
}
