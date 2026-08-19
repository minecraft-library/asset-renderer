plugins {
    // Version resolved in settings.gradle.kts, where the gradle.properties entry is readable.
    id("net.fabricmc.fabric-loom")
    id("maven-publish")
}

val minecraft_version: String by project
val loader_version: String by project
val fabric_api_version: String by project
val mod_version: String by project
val maven_group: String by project
val archives_base_name: String by project

version = mod_version
group = maven_group

base {
    archivesName = archives_base_name
}

/**
 * Reads an optional `-P` switch this build forwards into the run config as a system property.
 *
 * @param name the property name
 * @return its value, or null when it was not supplied
 */
fun optionalProperty(name: String): String? = providers.gradleProperty(name).orNull

loom {
    splitEnvironmentSourceSets()

    mods {
        create("refharness") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    runs {
        // Headless reference-render run. The mod detects the TitleScreen, programmatically
        // creates a fresh flat world, waits for it to load, sweeps blocks + entities to PNG,
        // then asks the client to stop. The world is deleted between runs (resetRefharnessWorld)
        // so each invocation starts clean.
        //
        // Filter to a specific subset with:
        //   ./gradlew runRenderReferences -PrefharnessTargets=minecraft:stone,minecraft:cow
        create("renderReferences") {
            client()
            configName = "Render References"
            // The Minecraft game directory. Loom defaults it to run/ inside this project;
            // pointing it at asset-renderer's cache/ keeps every untracked runtime artifact
            // this build produces under the one cache root, covered by the root .gitignore.
            runDir = "../cache/run"
            property("refharness.headless", "true")
            property("refharness.outputDir", optionalProperty("refharnessOutputDir")
                ?: "${rootProject.layout.buildDirectory.get().asFile}/refharness-output")
            property("refharness.size", "512")
            optionalProperty("refharnessTargets")?.let { property("refharness.targets", it) }
            optionalProperty("refharnessPitchRollSweep")?.let { property("refharness.pitchRollSweep", it) }
            // Depth-quantum probe: two overlapping quads a swept distance apart, measuring how
            // finely the depth test can tell two surfaces apart. Writes outside the reference tree
            // and re-renders nothing.
            //   ./gradlew runRenderReferences -PrefharnessDepthQuantumProbe=true
            optionalProperty("refharnessDepthQuantumProbe")?.let { property("refharness.depthQuantumProbe", it) }
            // Glint-only fast path: render just the animated-glint references under glint/,
            // skipping the full block/item/entity sweep. Driven by renderVanillaGlintReferences.
            optionalProperty("refharnessGlintOnly")?.let { property("refharness.glintOnly", it) }
            // Players-only fast path: render just the vanilla player references under players/,
            // skipping the full block/item/entity sweep. Driven by renderVanillaPlayerReferences.
            optionalProperty("refharnessPlayersOnly")?.let { property("refharness.playersOnly", it) }
            // Armor-only fast path: render just the armored-mob diagnostics under armor/,
            // skipping the full block/item/entity sweep. Driven by renderVanillaArmorReferences.
            optionalProperty("refharnessArmorOnly")?.let { property("refharness.armorOnly", it) }
            // Menus-only fast path: render just the container screens under menus/, skipping the
            // full block/item/entity sweep. Driven by renderVanillaMenuReferences.
            optionalProperty("refharnessMenusOnly")?.let { property("refharness.menusOnly", it) }
            // Whole-tree sweep: every reference sweep in one client boot, so a change to a
            // renderer two sweeps share cannot leave one of them behind. Driven by
            // renderVanillaAllReferences.
            optionalProperty("refharnessEverySweep")?.let { property("refharness.everySweep", it) }
            optionalProperty("refharnessBoundsDump")?.let { property("refharness.boundsDump", it) }
            // Per-triangle screen-coord dump - mirror of asset-renderer ModelEngine's prop.
            // Usage: -PentityPixelDump=21,0,21,800 (one column slice for witch x=21 hunt)
            optionalProperty("entityPixelDump")?.let { property("entity.pixel.dump", it) }
        }
    }
}

repositories {
    // Loom adds Mojang/Fabric maven automatically; Central carries the annotation processor.
    mavenCentral()
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraft_version")
    // No `mappings` line: Minecraft 26.1+ ships unobfuscated.
    implementation("net.fabricmc:fabric-loader:$loader_version")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabric_api_version")

    // The @Parity vocabulary, on the source set that holds every Java file here. `compileOnly`
    // because retention is SOURCE: javac needs the types to resolve a declaration and drops the
    // descriptor before it writes the class file, so nothing reaches the mod jar or a running client.
    "clientCompileOnly"("lib.minecraft:asset-renderer-parity:0.1.0")

    // Simplified Annotations
    "clientCompileOnly"("io.github.simplified-dev:annotations:2.6.1")
    "clientAnnotationProcessor"("io.github.simplified-dev:annotations:2.6.1")
}

tasks.processResources {
    val v = version
    inputs.property("version", v)
    filesMatching("fabric.mod.json") {
        expand("version" to v)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Wipe the render world before each renderReferences run so the mod can always
// createFreshLevel(...) without colliding with stale state. The path tracks the run
// config's runDir above; the two are the same directory and must move together.
val resetRefharnessWorld = tasks.register<Delete>("resetRefharnessWorld") {
    group = "refharness"
    description = "Removes the refharness_world save so the next renderReferences starts clean."
    delete(layout.projectDirectory.dir("../cache/run/saves/refharness_world"))
}

// A run directory Minecraft has never started in gets a fresh options.txt carrying
// onboardAccessibility:true, and the client then opens the accessibility onboarding screen
// INSTEAD of the title screen. WorldBootstrap reacts only to a TitleScreen, so the sweep is
// never triggered and the client sits at the menu until it is killed - no error, no output,
// no timeout. This never surfaced while the run directory was a long-lived untracked folder
// someone had clicked through once, which made a hand-warmed options.txt an undeclared
// precondition of the harness working at all. The two settings the harness actually depends
// on are tracked beside this file and seeded when the file is absent; everything else
// Minecraft fills in with its own defaults and rewrites on exit.
val seedRunProfile = tasks.register<Copy>("seedRunProfile") {
    group = "refharness"
    description = "Seeds options.txt into the run directory when absent, so a cold run directory reaches the title screen."
    from(layout.projectDirectory.file("run-profile/options.txt"))
    into(layout.projectDirectory.dir("../cache/run"))
    // Fires when the run directory has not cleared Minecraft's first-run onboarding - either
    // no options.txt at all, or one still asking for it. Seeding only on absence would leave a
    // directory that already hung once permanently stuck, because the hung client writes the
    // very file that would suppress the seed. Once onboarding is cleared Minecraft owns the
    // file and its own values win.
    onlyIf {
        val opts = layout.projectDirectory.file("../cache/run/options.txt").asFile
        !opts.exists() || opts.readText().contains("onboardAccessibility:true")
    }
}

tasks.matching { it.name == "runRenderReferences" }.configureEach {
    dependsOn(resetRefharnessWorld, seedRunProfile)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
