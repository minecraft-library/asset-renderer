// The generator flows and their suite, as aliases onto the `:tooling` subproject's own tasks. Applied
// from the root build script.
//
// The task NAMES are the contract: the parity artifact table lists them as the shipped tables'
// producers and resolves each with `named` on THIS project, so an alias keeps the name its flow had
// while the work happens in `:tooling`.
//
// The generators are a subproject rather than a build beside this one, so they resolve this build's
// production types instead of re-declaring them. That is why these are `dependsOn` edges rather than
// the `Exec` shells they were: there is one build, one daemon and one execution graph, so a flow is
// scheduled rather than shelled, `-PtoolingOut` and every `-Dasset.*` are already in scope on the far
// side, and a renderer change that breaks a generator fails at compile instead of at the next flow run.
//
// The direction stays one-way: `:tooling` takes `project(":")` on `implementation`, so ASM and the
// walkers are on no classpath of this project and in no published JAR.

tasks {
    // The tooling subproject's own suite. `check` schedules it under this name, which is the name it
    // had when the suite lived behind a wrapper.
    register("toolingTest") {
        description = "Runs the tooling subproject's unit tests - the ASM walkers, the kernel and the policy purity check."
        group = "verification"
        dependsOn(":tooling:test")
    }

    /**
     * Registers one generator flow as an alias onto the subproject's task of the same name.
     *
     * @param name the flow's task name on both sides
     * @param description what the flow walks and what it emits
     */
    fun registerToolingFlow(name: String, description: String) {
        register(name) {
            this.description = description
            group = "tooling"
            dependsOn(":tooling:$name")
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
