import org.gradle.api.DefaultTask
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations

import javax.inject.Inject

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong

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


// The namespace every custom flag of this build lives under. Written once because two places read
// the same table for the same set: the forwarder below puts them on a fork, and a parity capture
// records the ones in force as the conditions that fork ran under. The two answering with different
// sets means a capture claims a fork carried flags it did not, or omits ones it did, and neither is
// visible by inspection - so ParityTaskWiringTest relates the two spellings.
val assetPropertyPrefix: String = "asset."

// Forward every -Dasset.* system property to every forked Test + JavaExec JVM, in ONE place, so any
// future asset.* debug flag (e.g. -Dasset.snap.grid, -Dasset.entity.pixel.dump, -Dasset.glint.itemScale)
// auto-propagates to every task without per-task wiring. The CLI -D lands in the gradle daemon's
// System.getProperties(); the `asset.` prefix keeps gradle-internal properties out of the fork.
fun org.gradle.process.JavaForkOptions.forwardAssetProperties() =
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith(assetPropertyPrefix)) systemProperty(key, v.toString())
    }

/**
 * Returns every `-Dasset.*` in force, which is exactly the set `forwardAssetProperties` puts on a
 * fork.
 *
 * <p>Sorted, so a caller's argv is a function of the properties alone and two invocations under the
 * same flags build the same command line. That is all the sort buys, and the limit is worth writing
 * down: the record the flags land in folds the pairs into an object that canonical JSON sorts, so
 * the stored bytes are identical whatever order they arrive in, and the ordering shows only in the
 * command a run prints.
 *
 * @return the resolved flags by name
 */
fun assetPropertiesInForce(): Map<String, String> =
    sortedMapOf<String, String>().also { found ->
        System.getProperties().forEach { key, value ->
            val name = key.toString()
            if (name.startsWith(assetPropertyPrefix)) found[name] = value.toString()
        }
    }

/**
 * The resolved `-Dasset.*` set, published for the applied scripts.
 *
 * <p>`gradle/parity.gradle.kts` stamps these onto every capture it takes and `gradle/tooling.gradle.kts`
 * forwards them to each flow. A function cannot cross an
 * `apply(from = ...)` boundary, so the VALUE crosses instead - computed once here, where the walk and
 * the prefix it reads both live.
 */
extra["assetFlagsInForce"] = assetPropertiesInForce()


tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(addVectorModuleArg)
}
// The fifth consumer, and the only one that is not a JVM launch: javadoc resolves the incubator
// module at doclet time, so without this `SimdOps` reports the package as not visible. The task is
// red at HEAD for an unrelated reason - Lombok generates the builders it cannot see - so this makes
// two of its errors go away and no gate become usable; wiring it is about the flag being wired
// everywhere it is read rather than about the exit code.
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
// The two parity roots go AFTER forwardAssetProperties() so the resolved value wins whether or not
// one was also forwarded from the command line. The working root on the Test hook is what lets the
// self-capturing pin and digest rows write their observed value into it from inside the test JVM; the
// references path is the single owner of the reference tree, which the Java side reads instead of
// holding one VANILLA_DIR literal per sweep main.
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
    compileOnly(libs.simplified.annotations)
    annotationProcessor(libs.simplified.annotations)
    testCompileOnly(libs.simplified.annotations)
    testAnnotationProcessor(libs.simplified.annotations)

    // Tests
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.junit.platform.launcher)
    // JOML for tensor/Matrix4fTest: its 0-ULP parity assertion compares our matrix math against
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
    api("com.github.simplified-dev:collections") { version { strictly("8ca6cb8") } }
    api("com.github.simplified-dev:utils") { version { strictly("821499b") } }
    api("com.github.simplified-dev:image") { version { strictly("a4d0ad8") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("f143dc1") } }
    api("com.github.simplified-dev:reflection") { version { strictly("6c3b7c5") } }
    api("com.github.simplified-dev:client") { version { strictly("1ca9934") } }

    // Simplified API (extracted to github.com/simplified-api) - typed Feign contract for
    // Mojang's launcher / Piston / textures endpoints, owns all renderer HTTP via Pipeline.
    api("com.github.simplified-api:mojang") { version { strictly("d678198") } }

    // Minecraft-Library (extracted to github.com/minecraft-library)
    // Owns lib.minecraft.text.**, lib.minecraft.text.font.**, and the
    // RendererException / FontException base classes that the remaining asset-renderer
    // exceptions still extend.
    api("com.github.minecraft-library:text") { version { strictly("929dab6") } }

    // nbt-factory (github.com/minecraft-library/nbt-factory, group dev.sbs rewritten by jitpack).
    // Supplies the NBT tag model (CompoundTag/ListTag/NumericalTag) + parse surface
    // (fromBase64/fromByteArray/fromSnbt) the pipeline.pack.rule CIT nbt-conditional layer walks;
    // the built-in getPath is compound-only, so the rule layer supplies its own list/wildcard walker.
    api("com.github.minecraft-library:nbt-factory") { version { strictly("1fee2e2") } }

    // Gson
    api(libs.gson)

    // Client-jar acquisition, resolved through the included build. `api` because ClientAssets is the
    // argument PipelineRendererContext.of takes, so a consumer standing a context up names the type.
    // It is the one module the generators share with this one, and it depends on neither.
    api("lib.minecraft:asset-renderer-client:0.1.0")

    // The @Parity vocabulary, resolved through the included build. `compileOnly` because retention is
    // SOURCE: javac needs the types to resolve a declaration and drops the descriptor before it
    // writes the class file, so nothing downstream can read one and the published JAR carries none of
    // the five. The test tree takes it as an ordinary dependency instead - BlindnessMapTest reads
    // Subject.values() to hold the roster to the renderers this library ships, and that is a live
    // enum at test runtime rather than an annotation javac erased.
    compileOnly("lib.minecraft:asset-renderer-parity:0.1.0")
    testImplementation("lib.minecraft:asset-renderer-parity:0.1.0")
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

    // The parity store is read by filesystem path, never off a classpath, so a copy under
    // build/resources/test would only ever be a second answer - and a stale one, since a value
    // promoted seconds ago would not be in it. Excluding it also keeps roughly half a megabyte of
    // baselines out of anything this build packages.
    // The developer scripts beside it are authored to be run by hand, and no test opens one, so the
    // directory is excluded whole rather than file by file - a script added next year is a script,
    // not a fixture, and inheriting the exclusion is the answer that needs no edit here.
    processTestResources {
        exclude("lib/minecraft/renderer/parity/**")
        exclude("scripts/**")
    }



    // atlas render job - a render over the texture pack, not a client-jar extraction, so it is not a
    // tooling flow and nothing wires it; output stays scratch under build/atlas/. One manual task,
    // its passes selected by property: -Pdiagnose adds the slice-and-flag pass over the rendered
    // atlas, -PsourceFilter=<source> writes the mini atlas of that source alone, -PskipRender reads
    // the atlas already on disk instead of rendering a fresh one.

    register<JavaExec>("generateAtlas") {
        description = "Renders a block/item atlas PNG + the typed AtlasSidecar JSON to build/atlas/, as a worked example of driving AtlasRenderer. -Pdiagnose -PsourceFilter=blockstate_only -PskipRender"
        group = "build"
        mainClass.set("lib.minecraft.renderer.example.AtlasGenerator")
        classpath = sourceSets["test"].runtimeClasspath
        val sourceFilter = project.findProperty("sourceFilter") as String?
        args = buildList {
            add(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
            if (project.hasProperty("diagnose")) add("--diagnose")
            if (sourceFilter != null) add("--source-filter=$sourceFilter")
            if (project.hasProperty("skipRender")) add("--skip-render")
        }
    }


}

// JMH benchmark harness. Benchmarks live in src/jmh/java and are run with
// `./gradlew jmh`. Each Tier 1-3 parallelization task records
// before/after results against the benchmarks in lib.minecraft.renderer.bench.
dependencies {
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
    jmhAnnotationProcessor(libs.jmh.generator.annprocess)
    jmhCompileOnly(libs.simplified.annotations)
    jmhAnnotationProcessor(libs.simplified.annotations)
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

// Applied LAST, and in this order. Each resolves tasks by name - the visual aggregators over
// their own producers, and the parity capture wiring over every producer in the build - and `named`
// answers only for a task already registered. Everything this file registers is above.
apply(from = "gradle/tooling.gradle.kts")
apply(from = "gradle/visual.gradle.kts")
apply(from = "gradle/parity.gradle.kts")
