plugins {
    id("java-library")
    idea
}

group = "dev.sbs"
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
    api("com.github.simplified-dev:collections:master-SNAPSHOT")
    api("com.github.simplified-dev:utils:master-SNAPSHOT")
    api("com.github.simplified-dev:reflection:master-SNAPSHOT")
    api("com.github.simplified-dev:image:master-SNAPSHOT")
    api("com.github.simplified-dev:gson-extras:master-SNAPSHOT")
    api("com.github.simplified-dev:client:master-SNAPSHOT")
    api("com.github.simplified-dev:persistence:master-SNAPSHOT")

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

// Generated font files live outside src/main/resources so the font-generator tool never has
// to write into the source tree. processResources picks them up and copies them onto the
// runtime classpath at build time so MinecraftFont.initFont("fonts/X.otf", size) keeps
// resolving via getResourceAsStream without any runtime code changes.
val fontsCacheDir = layout.projectDirectory.dir("cache/fonts")

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

    processResources {
        // INCLUDE lets the generated fonts win over anything that might already sit in
        // src/main/resources/fonts/ (that directory is gitignored, but a stale dev-machine
        // copy could otherwise collide under the default FAIL strategy).
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(fontsCacheDir) {
            into("fonts")
        }
    }

    register<JavaExec>("generateAtlas") {
        description = "Generates a block/item atlas PNG + coordinates JSON."
        group = "renderer"
        mainClass.set("dev.sbs.renderer.gradle.AtlasGeneratorMain")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("generateVanillaTints") {
        description = "Parses BlockColors out of the cached client jar via ASM and rewrites src/main/resources/renderer/vanilla_tints.json. Run on a Minecraft version bump."
        group = "renderer"
        mainClass.set("dev.sbs.renderer.gradle.GenerateVanillaTintsMain")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("testRender") {
        description = "Renders a single block to cache/test-render/ for visual inspection. -PblockId=minecraft:tnt -PrenderSize=512 -Pssaa=2"
        group = "renderer"
        mainClass.set("dev.sbs.renderer.gradle.TestRenderMain")
        classpath = sourceSets["main"].runtimeClasspath
        val blockId = project.findProperty("blockId") as String?
        val renderSize = (project.findProperty("renderSize") as String?) ?: "512"
        val ssaa = (project.findProperty("ssaa") as String?) ?: "2"
        args = if (blockId != null) listOf(blockId, renderSize, ssaa) else listOf()
    }

    register<JavaExec>("generateEntityModels") {
        description = "Downloads the Bedrock Edition vanilla resource pack and generates src/main/resources/renderer/entity_models.json from .geo.json files."
        group = "renderer"
        mainClass.set("dev.sbs.renderer.gradle.GenerateEntityModelsMain")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("generateFonts") {
        description = "Clones minecraft-library/font-generator into cache/font-generator, sets up a Python venv, and runs the generator against the given MC version (-PfontVersion=26.1 by default). Writes .otf files to cache/fonts/ - run processResources afterwards to copy them onto the classpath."
        group = "renderer"
        mainClass.set("dev.sbs.renderer.gradle.GenerateFontsMain")
        classpath = sourceSets["main"].runtimeClasspath
        workingDir = layout.projectDirectory.asFile
        val version = (project.findProperty("fontVersion") as String?) ?: "26.1"
        args = listOf(version)
    }
}
