import org.gradle.api.DefaultTask
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

/**
 * Writes every byte to two sinks, so a producer's stdout reaches the console and a log file in one
 * pass. The harness prints its `failed=` line and one fit line per cohort to stdout and nothing
 * else records either, so a gate reading only the exit code cannot tell a partial sweep from a whole
 * one. The log is scratch under `build/`, never a store file.
 *
 * @param console the sink written first, flushed but never closed - normally `System.out`
 * @param log the sink written second, and closed with this stream
 */
class TeeStream(private val console: OutputStream, private val log: OutputStream) : OutputStream() {
    override fun write(byte: Int) {
        console.write(byte)
        log.write(byte)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        console.write(bytes, offset, length)
        log.write(bytes, offset, length)
    }

    override fun flush() {
        console.flush()
        log.flush()
    }

    override fun close() {
        console.flush()
        log.close()
    }
}

// Forward every -Dasset.* system property to every forked Test + JavaExec JVM, in ONE place, so any
// future asset.* debug flag (e.g. -Dasset.snap.grid, -Dasset.entity.pixel.dump, -Dasset.glint.itemScale)
// auto-propagates to every task without per-task wiring. The CLI -D lands in the gradle daemon's
// System.getProperties(); the `asset.` prefix keeps gradle-internal properties out of the fork.
fun org.gradle.process.JavaForkOptions.forwardAssetProperties() =
    System.getProperties().forEach { k, v ->
        val key = k.toString()
        if (key.startsWith("asset.")) systemProperty(key, v.toString())
    }

// The vanilla-reference-harness Fabric mod, which lives inside this repo at harness/ and stays its
// own Gradle build - its own gradlew, its own JDK 25 toolchain, and no Loom in settings.gradle.kts.
// The five reference-render tasks below drive it externally through that wrapper, so this is the one
// place the path is written and the only thing a future relocation has to edit.
val harnessDir = layout.projectDirectory.dir("harness")

// ---- reading a -P without hitting a Project bean property ----------------------------------------
// Project.findProperty resolves a project's BEAN properties as well as its project properties, and
// three of the names the parity tasks need are taken: `artifacts` answers with the ArtifactHandler,
// `base` with the base plugin's extension and `class` with java.lang.Class. The first is a
// ClassCastException at task creation; the other two would be silently wrong, which is worse. Reading
// the start parameter answers with exactly what was typed on the command line and nothing else.
//
// The producers' own properties (-PblockId, -PrenderSize, ...) keep findProperty: none of them
// collides, and rewriting 40 task bodies to fix a problem they do not have would be its own risk.

/**
 * Returns a command-line project property, or null when it was not given or was given blank.
 *
 * @param name the -P name
 * @return its value
 */
fun parityProperty(name: String): String? =
    gradle.startParameter.projectProperties[name]?.takeIf { it.isNotBlank() }

/**
 * Returns whether a command-line project property was given at all, valueless or not.
 *
 * @param name the -P name
 * @return whether it was given
 */
fun parityFlag(name: String): Boolean = gradle.startParameter.projectProperties.containsKey(name)

/**
 * Returns whether this invocation actually asked for the named task.
 *
 * <p>A parity task refuses a missing `-Partifacts` or `-Preason` at CONFIGURATION time, which is
 * where a refusal belongs: it fails before a producer runs rather than after one has. But
 * `gradle tasks` and `gradle help` realize every registered task to read its description, so an
 * unconditional throw makes the task report unrunnable and the parity group uncountable - measured,
 * not anticipated. Gating on the requested names keeps the refusal exactly where it was protecting
 * something and nowhere else.
 *
 * @param name the task name
 * @return whether the command line asked for it
 */
fun parityTaskRequested(name: String): Boolean =
    gradle.startParameter.taskNames.any { it == name || it.endsWith(":$name") }

// ---- parity roots -------------------------------------------------------------------------------
// The working root is SINGLE-SLOT and self-overwriting: one root, one capture in it, and the next
// capture replaces the previous. An A/B before-side is a REDIRECTED ROOT
// (-PparityRoot=cache/parity/base), never a second slot name a plan could keep alive.
//
// Precedence: -PparityRoot (a task-level choice) beats the forwarded -Dasset.parity.* root (which
// forwardAssetProperties may already have put on a fork) beats the default, and the resolved value is
// then set on every fork so the two can never disagree.
//
// Configuration-cache note: the System.getProperty read is a mutable global read at configuration
// time - the same class as forwardAssetProperties()' own System.getProperties() walk above, and one
// key rather than a whole table. It adds no new class of exposure.
val parityWorkingRoot: String =
    parityProperty("parityRoot")
        ?: System.getProperty("asset.parity.root")
        ?: "cache/parity/current"

// The tracked production store - the last known value of every artifact. It lives inside the test
// source set's resources, so moving it is this one line on the Gradle side, one constant on the Java
// side and one on the Python side.
val parityProductionStore: String = "src/test/resources/lib/minecraft/renderer/parity"

/**
 * Refuses a working root a `cache/` clean would not reach.
 *
 * A redirected root must stay relative and under `cache/`, so a temporary capture can never be
 * written into a tracked directory and always dies with a cache clean. That is what makes long-lived
 * temp output unrepresentable rather than merely discouraged.
 *
 * It is a pure string test on purpose - nothing here resolves a path, so nothing here can resolve one
 * against the JVM's working directory - and it is called from each parity task's configuration block
 * rather than at the top level, so a malformed -PparityRoot fails the parity tasks and not
 * `./gradlew test`.
 */
fun requireParityRootUnderCache() {
    val normalized = parityWorkingRoot.replace('\\', '/')
    val absolute = normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:/.*"))
    require(!absolute && normalized.startsWith("cache/")) {
        "-PparityRoot must be a relative path under cache/ (got '$parityWorkingRoot')"
    }
}

// -Plabel keeps its meaning and its blank-check: a valueless -Plabel arrives as "" rather than null,
// which would write the dump to cache/parity-dump//.
val parityDumpLabel: String =
    (project.findProperty("label") as String?)?.takeIf { it.isNotBlank() } ?: "head"

// ---- the Minecraft version, and the reference tree it names -------------------------------------
// ONE owner for the version segment of the reference tree path, which this file used to hardcode
// five times over. The harness's gradle.properties is the authority, and the tree path takes its
// major.minor, which is the existing convention and is preserved. providers.fileContents is a
// DECLARED input where a raw File.readText would be a new undeclared configuration-time read, and the
// fallback is what lets a checkout without the harness still configure.
val minecraftVersion: String = providers
    .fileContents(harnessDir.file("gradle.properties"))
    .asText.orNull
    ?.lineSequence()
    ?.firstOrNull { it.startsWith("minecraft_version") }
    ?.substringAfter('=')?.trim()
    ?: "26.1.2"

val parityReferenceRoot: String =
    "cache/asset-renderer/vanilla/${minecraftVersion.split(".").take(2).joinToString(".")}/references"

/**
 * The six visual producers no other parity artifact covers, each with the `cache/visual` sub-tree it
 * writes.
 *
 * <p>One map rather than a dependency list and a parallel comment, because three things read it: the
 * aggregator's dependencies, the clear that runs before them, and the ordering edge between the two.
 * The directory half is the other end of `manifest.SUBTREES["manifest.visual"]` in the toolkit -
 * these six sub-trees ARE that artifact.
 */
val visualSweepProducers = mapOf(
    "blockRender3D" to "block-render-3d",
    "entityRender3D" to "entity-render-3d",
    "itemDayCycle" to "item-day-cycle",
    "itemRender2D" to "item-render-2d",
    "loreTooltip" to "lore-tooltip",
    "menuRender" to "menu-render"
)

/**
 * Where every parity producer's stdout is teed, as a project-relative path the toolkit can read.
 *
 * <p>DERIVED from the build directory rather than written out, because two things have to agree on
 * it: the tee that writes the logs and the artifact row whose stored form digests them. A literal
 * would agree with `layout.buildDirectory` only for as long as nobody moves the build directory.
 */
val parityProducerLogDir: String = layout.buildDirectory.dir("parity").get().asFile
    .relativeTo(layout.projectDirectory.asFile).invariantSeparatorsPath

// ---- the parity toolkit invocation --------------------------------------------------------------
// The default is RESOLVED against PATH rather than left as the bare name, because a bare name is not
// startable here. Windows puts a Microsoft Store app-execution alias at
// %LOCALAPPDATA%\Microsoft\WindowsApps\python.exe and it usually comes first on PATH; it is a reparse
// stub the shell can launch and ProcessBuilder cannot, so Gradle's Exec fails with the uninformative
// "A problem occurred starting process 'command 'python''" while `python` works in every terminal.
// Skipping that one directory finds the real interpreter sitting behind it. -PpythonExe and
// PARITY_PYTHON remain the escape for anything this does not resolve.
val parityPythonExe: String = parityProperty("pythonExe")
    ?: System.getenv("PARITY_PYTHON")
    ?: run {
        val windows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val names = if (windows) listOf("python.exe", "python3.exe") else listOf("python3", "python")
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .filter { it.isNotBlank() && !it.replace('\\', '/').contains("/WindowsApps") }
            .firstNotNullOfOrNull { dir ->
                names.map { File(dir, it) }.firstOrNull { it.isFile }?.absolutePath
            }
            ?: if (windows) "python" else "python3"
    }

/**
 * Points an Exec task at the parity toolkit. Nothing in this build file computes a sum, a bucket, a
 * join or a digest - that is the toolkit's job and there is one of it.
 *
 * `PYTHONUTF8=1` forces UTF-8 on a Windows host whose default codepage is 1252. There is deliberately
 * no `PYTHONPATH`: the directory form `python scripts/parity <command>` needs none, and one
 * invocation form is what the build, the skill and a human all type.
 *
 * @receiver the Exec task being pointed at the toolkit
 * @param argv the toolkit command and its arguments, after `scripts/parity`
 */
fun org.gradle.process.ExecSpec.parityToolkit(vararg argv: String) {
    executable = parityPythonExe
    args(listOf("scripts/parity") + argv)
    environment("PYTHONUTF8", "1")
}

// ---- the artifact roster ------------------------------------------------------------------------
/**
 * One parity artifact and the task that produces it.
 *
 * @param artifact the artifact id, which is also the capture step's name suffix
 * @param producers every task whose run can produce this artifact
 * @param source the producer's own output directory, relative to the project directory; the working
 *   root itself for a row whose producer self-captures from inside the test JVM
 * @param scopedBy project properties that narrow the producer - any one present suppresses the
 *   capture, because a scoped run is a hole rather than a sample
 * @param logSource the directory holding this artifact's producers' teed diagnostics logs, for a row
 *   whose stored form carries a digest per flow beside its file digests; null for every other row
 */
data class ParityArtifact(
    val artifact: String,
    val producers: List<String>,
    val source: String,
    val scopedBy: List<String> = emptyList(),
    val logSource: String? = null
)

// Rows 15 to 23 carry the WORKING ROOT ITSELF as their source, with no sub-directory: those producers
// self-capture from inside the test JVM, so the capture step validates and stamps the already-canonical
// file at its own production-relative path rather than reading a producer directory. `--source` naming
// the root IS what marks a row self-captured, so `$parityWorkingRoot/pins` refuses exactly as the
// shipped-tables directory does. Row 15 is one of them and not an exception: `table-canonical` is
// Gson's number formatting, which Python cannot reproduce for the seven float-bearing tables, so no
// reader outside the test JVM can take that digest at all.
// Row 15 is `test` and row 16 is `slowTest` because ResourceShaTest runs in the fast suite while
// PipelineIntegrationTest is @Tag("slow") - naming `test` for the latter would let a fast-suite run
// credit itself with a value it never computed.
//
// manifest.references names ONE producer where five tasks write into that tree: the four narrow runs
// leave sub-trees stale, and a whole-tree manifest taken after one of them hashes a mix of fresh and
// stale that is indistinguishable from a whole-tree run. manifest.tooling-tables names eight
// producers and one source directory, so any single flow's run captures the whole ten-file table
// state - a per-flow manifest would be four files here and six there and the two would not compare.
val parityArtifacts = listOf(
    ParityArtifact("sweep.entity", listOf("entityParityVanilla"), "cache/visual/entity-parity-vanilla", listOf("entityId")),
    ParityArtifact("sweep.block", listOf("blockParityVanilla"), "cache/visual/block-parity-vanilla", listOf("blockId")),
    ParityArtifact("sweep.item", listOf("itemParityVanilla"), "cache/visual/item-parity-vanilla", listOf("itemId")),
    ParityArtifact("sweep.player", listOf("playerParityVanilla"), "cache/visual/player-parity-vanilla"),
    ParityArtifact("sweep.armor", listOf("armorParityVanilla"), "cache/visual/armor-parity-vanilla"),
    ParityArtifact("sweep.glint", listOf("glintParityVanilla"), "cache/visual/glint-parity-vanilla", listOf("itemId")),
    ParityArtifact("manifest.references", listOf("renderVanillaAllReferences"), parityReferenceRoot, listOf("refharnessTargets")),
    ParityArtifact("manifest.visual", listOf("visualSweepSet"), "cache/visual"),
    ParityArtifact("manifest.dump.vanilla", listOf("parityDump"), "cache/parity-dump/$parityDumpLabel/vanilla"),
    ParityArtifact("manifest.dump.packs", listOf("parityDump"), "cache/parity-dump/$parityDumpLabel/packs"),
    ParityArtifact("manifest.player-sheets", listOf("playerRender"), "cache/visual/player-render",
        listOf("sheets", "account", "renderSize", "pack")),
    ParityArtifact("manifest.fluid", listOf("fluidRenderer"), "cache/visual/fluid-renderer"),
    ParityArtifact("manifest.portal", listOf("portalRenderer"), "cache/visual/portal-renderer"),
    // The only row with a logSource: a shipped table reproducing byte for byte is not the same claim
    // as the run that produced it being unchanged, and the entity flow has already moved an INFO line
    // from position 9 to 6 with every emitted byte identical.
    ParityArtifact("manifest.tooling-tables",
        listOf("entityModels", "blockModels", "blockDefaults", "blockItems", "blockTints", "potionColors", "glintItems", "colorMaps"),
        "src/main/resources/lib/minecraft/renderer",
        logSource = parityProducerLogDir),
    ParityArtifact("digest.shipped-tables", listOf("test"), parityWorkingRoot),
    ParityArtifact("digest.colormap-lut", listOf("slowTest"), parityWorkingRoot),
    ParityArtifact("pin.vanilla-iso-pose", listOf("test"), parityWorkingRoot),
    ParityArtifact("pin.kit-corners", listOf("test"), parityWorkingRoot),
    ParityArtifact("pin.corpus-count", listOf("test"), parityWorkingRoot),
    ParityArtifact("pin.player-crc", listOf("slowTest"), parityWorkingRoot),
    ParityArtifact("pin.block-crc", listOf("slowTest"), parityWorkingRoot),
    ParityArtifact("pin.portal-crc", listOf("slowTest"), parityWorkingRoot),
    ParityArtifact("pin.fluid-crc", listOf("slowTest"), parityWorkingRoot)
)

/** Every task the artifact table names, so a producer's stdout is captured wherever it runs. */
val parityProducerNames: Set<String> = parityArtifacts.flatMap { it.producers }.toSet()

// Every alias that can be derived from the table is derived from it, so a new row joins its alias
// without a second edit. `renders`, `dump` and `tables` name their members because those three are
// groupings rather than kinds. manifest.references is deliberately in `all`: naming it means booting
// the Minecraft client for 152 seconds, which is correct, because the alternative is a reference
// manifest captured against whatever happened to be on disk.
val parityArtifactAliases: Map<String, List<String>> = mapOf(
    "all" to parityArtifacts.map { it.artifact },
    "sweeps" to parityArtifacts.map { it.artifact }.filter { it.startsWith("sweep.") },
    "renders" to listOf("manifest.player-sheets", "manifest.fluid", "manifest.portal"),
    "dump" to listOf("manifest.dump.vanilla", "manifest.dump.packs"),
    "tables" to listOf("manifest.tooling-tables"),
    "pins" to parityArtifacts.map { it.artifact }.filter { it.startsWith("pin.") },
    "digests" to parityArtifacts.map { it.artifact }.filter { it.startsWith("digest.") }
)

/**
 * The artifact ids a plan resolved the changed paths to, or null when no plan has been written.
 *
 * Read through `providers.fileContents` so an absent plan is an absent value rather than an
 * exception, and so the read is a declared input.
 *
 * @return the plan's SEES set, or null when the working root holds no plan
 */
fun parityPlannedArtifacts(): List<String>? {
    val text = providers
        .fileContents(layout.projectDirectory.file("$parityWorkingRoot/_run/plan.json"))
        .asText.orNull ?: return null
    val plan = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
    return (plan["sees"] as List<*>?)?.map { it.toString() }
}

/**
 * Expands `-Partifacts` into the rows to capture.
 *
 * Three branches in order: an explicit comma list of ids and aliases; absent with a plan, which
 * resolves to the plan's SEES set so a human never has to know the ids; absent with no plan, which
 * throws carrying the full id list.
 *
 * @param spec the -Partifacts value, or null when it was not given
 * @return the named rows, in table order and without duplicates
 */
fun resolveParityArtifacts(spec: String?): List<ParityArtifact> {
    val known = parityArtifacts.associateBy { it.artifact }
    val roster = "known artifacts: ${known.keys.joinToString(", ")}\n" +
        "known aliases: ${parityArtifactAliases.keys.joinToString(", ")}"
    val requested = spec?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
        ?: parityPlannedArtifacts()
        ?: throw GradleException(
            "-Partifacts is required when no plan has been written: run parityPlan first, or name " +
                "the artifacts.\n$roster")
    val ids = requested.flatMap { token -> parityArtifactAliases[token] ?: listOf(token) }.distinct()
    ids.firstOrNull { it !in known }?.let {
        throw GradleException("unknown artifact id '$it'.\n$roster")
    }
    return parityArtifacts.filter { it.artifact in ids }
}

// ---- the harness runs, and the capture steps ----------------------------------------------------
/** Composable harness diagnostics: stdout only, so a run carrying one still produces the same bytes. */
val harnessDiagnosticProperties = listOf("refharnessBoundsDump", "entityPixelDump")

/** Every sub-tree the reference tree holds, so a partial run can name what it did NOT refresh. */
val referenceSubTrees = listOf("blocks", "items", "entities", "players", "glint", "armor")

/** The whole-suite producers, which order a capture step but are never finalized by one. */
val paritySuiteProducers = setOf("test", "slowTest")

/**
 * Registers one harness run. A mode is a task, never a pass-through property: a mode flag changes
 * what the run produces, and a task named `renderVanillaReferences` that renders no reference is the
 * recorded trap.
 *
 * @receiver the task container the run joins
 * @param name the task name
 * @param modeFlag the harness -P selecting the mode, or null for the harness's full mode
 * @param forwardsTargets whether -PrefharnessTargets is honoured by the sweeps this mode runs
 * @param refreshes the reference sub-trees this run rewrites; empty means it writes outside the tree
 * @param describe the task description
 * @return the registered task
 */
fun TaskContainer.registerHarnessRun(
    name: String,
    modeFlag: String?,
    forwardsTargets: Boolean,
    refreshes: List<String>,
    describe: String
) = register<Exec>(name) {
    description = describe
    group = "tooling"
    workingDir = harnessDir.asFile
    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
    val gradlew = harnessDir.file(if (isWindows) "gradlew.bat" else "gradlew").asFile.absolutePath
    // layout.projectDirectory, never File(relative): a bare File resolves against the JVM's working
    // directory, which in a long-lived daemon is usually but not guaranteed the project directory -
    // and this value is what the harness writes its whole reference tree into.
    val referenceDir = layout.projectDirectory.dir(parityReferenceRoot).asFile
    val argv = mutableListOf<String>()
    if (isWindows) {
        argv += "cmd"
        argv += "/c"
    }
    argv += gradlew
    argv += "runRenderReferences"
    argv += "--no-daemon"
    // -P propagates through the harness's build.gradle to its Loom run config, which sets the system
    // property the mod reads. -D would only reach the wrapper's JVM, never the forked client.
    argv += "-PrefharnessOutputDir=${referenceDir.absolutePath}"
    if (modeFlag != null) argv += "-P$modeFlag=true"
    if (forwardsTargets && project.hasProperty("refharnessTargets"))
        argv += "-PrefharnessTargets=${project.property("refharnessTargets")}"
    harnessDiagnosticProperties.filter { project.hasProperty(it) }
        .forEach { argv += "-P$it=${project.property(it)}" }
    commandLine = argv
    val log = layout.buildDirectory.file("parity/harness-$name.log").get().asFile
    val stale = referenceSubTrees - refreshes.toSet()
    doFirst {
        log.parentFile.mkdirs()
        // The harness prints "<sweep>: done. rendered= skipped= failed= total=" and one fit line per
        // cohort, and both are discarded with its gitignored logs. failed= is the only signal a
        // partially failed sweep leaves, so the stream is tee'd rather than swallowed.
        standardOutput = TeeStream(System.out, FileOutputStream(log))
        referenceDir.mkdirs()
        println("$name -> $parityReferenceRoot")
        if (refreshes.isNotEmpty() && stale.isNotEmpty())
            println("$name does NOT refresh ${stale.joinToString()} - those sub-trees keep whatever " +
                "produced them. After any harness render change run renderVanillaAllReferences.")
    }
}

/**
 * The task whose action is the once-per-invocation erase of the parity working root.
 *
 * <p>Named rather than repeated because three things order against it: every capture step, every
 * producer, and `parityCapture` itself.
 */
val parityCaptureBeginTask = "parityCaptureBegin"

/**
 * Returns the capture step's task name for an artifact.
 *
 * <p>Shared by the registration and by `parityCapture`'s own `dependsOn`, because the umbrella has to
 * name the steps: a finalizer is only guaranteed to run after the task it finalizes, not before a
 * third task that depends on that same producer. Without the explicit edge, `capture-index` could
 * write its COMPLETE marker before the artifacts it is meant to be marking complete.
 *
 * @param artifact the artifact id
 * @return the capture step's task name
 */
fun parityCaptureTaskName(artifact: String): String =
    "parityCapture" + artifact.split('.')
        .joinToString("") { it.replaceFirstChar(Char::titlecase) }
        .replace("-", "")

/**
 * Registers one capture step and finalizes every producer of its artifact with it.
 *
 * @receiver the task container the capture step joins
 * @param spec the artifact this step captures
 */
fun TaskContainer.registerParityCapture(spec: ParityArtifact) {
    val scoped = spec.scopedBy.filter { project.hasProperty(it) }
    val runs = parityProperty("runs")
    val step = register<Exec>(parityCaptureTaskName(spec.artifact)) {
        // No group: these are finalizers, not an entry point. The group = "parity" tasks are.
        description = "Captures ${spec.artifact} from ${spec.source} into the parity working root."
        parityToolkit(*buildList {
            add("capture-normalize")
            add("--artifact"); add(spec.artifact)
            add("--source"); add(spec.source)
            add("--root"); add(parityWorkingRoot)
            add("--producer"); add(spec.producers.joinToString(","))
            // -Pruns is the ONLY spelling of the determinism-run count, and it is forwarded here
            // rather than registered and dropped. Absent means the artifact's declared floor, which
            // the toolkit owns because the floor is a property of the artifact, not of the build.
            runs?.let { add("--runs"); add(it) }
            spec.logSource?.let { add("--logs"); add(it) }
        }.toTypedArray())
        // Never up to date, for the reason parityDump's own comment gives: a capture reported
        // UP-TO-DATE is a stale capture served as fresh evidence.
        outputs.upToDateWhen { false }
        onlyIf {
            if (scoped.isNotEmpty()) {
                logger.lifecycle("parity: NOT capturing ${spec.artifact} - scoped by " +
                    "${scoped.joinToString { "-P$it" }}. A scoped run is a hole, not a sample; " +
                    "re-run without it to capture.")
            }
            scoped.isEmpty()
        }
    }
    // Ordering first, and it holds for every producer: a capture reads what a producer wrote, so it
    // runs after one whenever both are in the graph. That is what a finalizer edge was implicitly
    // giving, and the two suites below do not get one.
    step.configure {
        mustRunAfter(spec.producers)
        mustRunAfter(parityCaptureBeginTask)
    }

    // A hand-run producer captures without anyone remembering a flag - EXCEPT the two whole-suite
    // producers. `test` and `slowTest` produce their nine rows from inside the test JVM, and a
    // finalizer on them would make every ordinary `./gradlew test` write into the parity working root,
    // wiping whatever capture was sitting there waiting to be compared. It would also fail the repo's
    // primary gate outright until those rows have a writer, which was measured rather than foreseen.
    // Their rows are still rows: parityCapture -Partifacts=pins runs the suite and then the step, and
    // the step still fails on an absent file, which is the backstop a --tests-filtered run needs.
    spec.producers.forEach { producer ->
        named(producer) {
            // Every producer is ordered after the erase, not merely every capture step. Rows whose
            // source IS the working root are written there by the test JVM, so an erase on the far
            // side of `slowTest` deletes the pins that run just wrote. mustRunAfter binds only when
            // both tasks are in the graph, so a hand-run producer is unaffected by this edge.
            mustRunAfter(parityCaptureBeginTask)
            if (producer !in paritySuiteProducers) finalizedBy(step)
        }
    }
}

/**
 * Runs one parity toolkit command at execution time.
 *
 * <p>Gradle 9 removed `Project.exec` and `Project.delete` from the execution phase, so both arrive as
 * injected services rather than off the project. That is not only a compatibility fix: an injected
 * service is a declared dependency of the task where a project reference is the classic
 * configuration-cache violation, so this shape is strictly better than the one it replaces.
 */
abstract class ParityToolkitTask @Inject constructor(
    private val execOps: ExecOperations,
    private val fsOps: FileSystemOperations
) : DefaultTask() {

    /** The interpreter to run the toolkit with. */
    @get:Input
    abstract val pythonExe: Property<String>

    /** The toolkit command and its arguments, after `scripts/parity`. */
    @get:Input
    abstract val argv: ListProperty<String>

    /** Directories to delete before the command runs; empty for every task that does not wipe. */
    @get:Input
    abstract val wipe: ListProperty<String>

    @TaskAction
    fun run() {
        val doomed = wipe.get()
        if (doomed.isNotEmpty()) fsOps.delete { delete(*doomed.toTypedArray()) }
        execOps.exec {
            executable = pythonExe.get()
            args(listOf("scripts/parity") + argv.get())
            environment("PYTHONUTF8", "1")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(addVectorModuleArg)
}
// The two parity roots go AFTER forwardAssetProperties() so the resolved value wins whether or not
// one was also forwarded from the command line. The working root on the Test hook is what lets the
// self-capturing pin and digest rows write their observed value into it from inside the test JVM; the
// references path is the single owner of the reference tree, which the Java side reads instead of
// holding one VANILLA_DIR literal per sweep main.
tasks.withType<Test>().configureEach {
    jvmArgs(addVectorModuleArg)
    forwardAssetProperties()
    systemProperty("asset.parity.root", parityWorkingRoot)
    systemProperty("asset.parity.references", parityReferenceRoot)
}
tasks.withType<JavaExec>().configureEach {
    jvmArgs(addVectorModuleArg)
    forwardAssetProperties()
    systemProperty("asset.parity.root", parityWorkingRoot)
    systemProperty("asset.parity.references", parityReferenceRoot)
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
    api("com.github.simplified-dev:image") { version { strictly("953ca92") } }
    api("com.github.simplified-dev:gson-extras") { version { strictly("2ba8143") } }
    api("com.github.simplified-dev:reflection") { version { strictly("b2cf834") } }
    api("com.github.simplified-dev:client") { version { strictly("5a5d32e") } }

    // Simplified API (extracted to github.com/simplified-api) - typed Feign contract for
    // Mojang's launcher / Piston / textures endpoints, owns all renderer HTTP via Pipeline.
    api("com.github.simplified-api:mojang") { version { strictly("5c2bda6") } }

    // Minecraft-Library (extracted to github.com/minecraft-library)
    // Owns lib.minecraft.text.**, lib.minecraft.text.font.**, and the
    // RendererException / FontException base classes that the remaining asset-renderer
    // exceptions still extend.
    api("com.github.minecraft-library:text") { version { strictly("b2fbe0d") } }

    // nbt-factory (github.com/minecraft-library/nbt-factory, group dev.sbs rewritten by jitpack).
    // Supplies the NBT tag model (CompoundTag/ListTag/NumericalTag) + parse surface
    // (fromBase64/fromByteArray/fromSnbt) the pipeline.pack.rule CIT nbt-conditional layer walks;
    // the built-in getPath is compound-only, so the rule layer supplies its own list/wildcard walker.
    api("com.github.minecraft-library:nbt-factory") { version { strictly("f8b5f52") } }

    // ASM - used by VanillaTintsLoader to parse net.minecraft.client.color.block.BlockColors
    // straight from the extracted client jar, replacing the previously hand-curated tint table.
    // 9.8 added support for Java 25 class files (major version 69), which the Minecraft version
    // named by the harness's gradle.properties emits.
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

    register<JavaExec>("parityDump") {
        description = "pipeline-cleanup gate: loads the full pipeline + renderer context and writes the canonical semantic dump to cache/parity-dump/<label>/{vanilla,packs}/. Diff two labels to prove a phase moved no render input. -Plabel=base"
        group = "verification"
        mainClass.set("lib.minecraft.renderer.pipeline.dump.PipelineParityDump")
        classpath = sourceSets["test"].runtimeClasspath
        // parityDumpLabel carries the blank-check a valueless -Plabel needs: it arrives as ""
        // rather than null, which would write the dump to cache/parity-dump//.
        args = listOf(parityDumpLabel)
        // Deliberately declares no outputs: the dump must re-run every invocation. Declaring
        // outputs without also declaring the label AND the forwarded asset.* set as inputs would let
        // Gradle report UP-TO-DATE and serve a stale dump as if it were fresh evidence.
        //
        // The label directory is wiped first, which is the other half of the same rule. A dump that
        // failed on load used to leave the previous run's tree standing under the same label, and the
        // `&& diff` that followed then reported it byte-identical - a stale tree served as agreement.
        // An empty directory cannot do that. FileSystemOperations rather than Project.delete, which
        // Gradle 9 removed from the execution phase; the service and the directory are resolved into
        // locals here so the action captures neither the project nor the layout.
        val fsOps = project.serviceOf<FileSystemOperations>()
        val labelDir = layout.projectDirectory.dir("cache/parity-dump/$parityDumpLabel").asFile
        doFirst {
            fsOps.delete { delete(labelDir) }
        }
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
    processTestResources {
        exclude("lib/minecraft/renderer/parity/**")
    }

    // tooling - the generator flows; every output lands under
    // src/main/resources/lib/minecraft/renderer/.

    register<JavaExec>("entityModels") {
        description = "tooling: walks the client jar and generates src/main/resources/lib/minecraft/renderer/entity_models.json + entity_geometry.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingEntityModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockModels") {
        description = "tooling: walks the client jar and generates src/main/resources/lib/minecraft/renderer/block_models.json + block_geometry.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockModels")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockDefaults") {
        description = "tooling: bytewalks registerDefaultState and generates src/main/resources/lib/minecraft/renderer/block_defaults.json (default blockstate per block + unresolved[])."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockDefaults")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockItems") {
        description = "tooling: walks Items.<clinit> and generates src/main/resources/lib/minecraft/renderer/block_items.json (secondary block -> standing block item alias map)."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockItems")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("blockTints") {
        description = "tooling: walks BlockColors.createDefault() and generates src/main/resources/lib/minecraft/renderer/block_tints.json (tints + dropped[])."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingBlockTints")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("potionColors") {
        description = "tooling: walks MobEffects.<clinit> and generates src/main/resources/lib/minecraft/renderer/potion_colors.json (effect colours, sorted by id)."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingPotionColors")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("glintItems") {
        description = "tooling: walks Items.<clinit> and generates src/main/resources/lib/minecraft/renderer/glint_items.json (always-glinted item ids, sorted)."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingGlintItems")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("colorMaps") {
        description = "tooling: reads the biome colormap PNGs from the jar and generates src/main/resources/lib/minecraft/renderer/color_maps.json (base64 big-endian ARGB pixels)."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingColorMaps")
        classpath = sourceSets["main"].runtimeClasspath
    }

    // atlas render job - a render over the texture pack, not a client-jar extraction; output stays
    // scratch build/atlas/. diagnoseAtlas and diagnoseAtlasBlockstates share one main class,
    // ToolingAtlasDiagnose, split by the --source-filter arg.

    register<JavaExec>("atlas") {
        description = "tooling: renders a block/item atlas PNG + typed AtlasSidecar JSON to build/atlas/."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlas")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlas") {
        description = "tooling: slices build/atlas/atlas.png by the AtlasSidecar, flags blank/sparse tiles to build/atlas/missing.json."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath)
    }

    register<JavaExec>("diagnoseAtlasBlockstates") {
        description = "tooling: writes a mini atlas containing only blockstate additions to build/atlas/blockstate_only/."
        group = "tooling"
        mainClass.set("lib.minecraft.renderer.tooling.ToolingAtlasDiagnose")
        classpath = sourceSets["main"].runtimeClasspath
        args = listOf(layout.buildDirectory.dir("atlas").get().asFile.absolutePath, "--source-filter=blockstate_only")
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

    register<JavaExec>("blockFlipbook") {
        description = "Renders the vanilla animated-texture blocks (fire/magma/prismarine/sea_lantern/water) with animation opted in (deriveTimeline AUTO) to cache/visual/block-flipbook/ as GIFs - the phase-4 flipbook LOOK gate. -PrenderSize=256"
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestBlockFlipbook")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        args = listOf(renderSize)
    }

    register<JavaExec>("itemDayCycle") {
        description = "Bakes a whole in-game day for the time-driven item icons (clock, plus the bearing-driven compass and a plain sword as controls) to cache/visual/item-day-cycle/ as GIFs + quarter-day stills - the animated-clock LOOK gate. -PrenderSize=256 -PdayFrames=<n> overrides the frame count; the default is 0, which derives it per item from the item's own dispatch table and is the more faithful path."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestItemDayCycle")
        classpath = sourceSets["test"].runtimeClasspath
        val renderSize = (project.findProperty("renderSize") as String?) ?: "256"
        val dayFrames = (project.findProperty("dayFrames") as String?) ?: "0"
        args = listOf(renderSize, dayFrames)
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

    register<JavaExec>("bedCompare") {
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

    register<JavaExec>("menuRender") {
        description = "Renders the vanilla-style chest chrome menus (SkyBlock crafting + vanilla crafting) to cache/visual/menu-render/ for visual inspection."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestMenuRender")
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

    register<JavaExec>("playerParityVanilla") {
        description = "Per-scope player parity report (FULL + SKULL) comparing Java PlayerRenderer 3D vs vanilla-reference-harness ground truth (ENTITY_IN_UI lighting). Bbox-aligned diff panels -> cache/visual/player-parity-vanilla/<scope>/. Run :asset-renderer:renderVanillaPlayerReferences first if the cache is missing."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestPlayerParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
    }

    register<JavaExec>("armorParityVanilla") {
        description = "Per-subject worn-armor parity report (adult + baby zombie / piglin, iron + dyed leather) comparing Java EntityRenderer vs the vanilla-reference-harness armor references. Bbox-aligned diff panels -> cache/visual/armor-parity-vanilla/<subject>/. Run :asset-renderer:renderVanillaArmorReferences first if the cache is missing."
        group = "visual"
        mainClass.set("lib.minecraft.renderer.visual.TestArmorParityVanilla")
        classpath = sourceSets["test"].runtimeClasspath
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

    // manifest.visual's producer, and the reason it exists: that artifact had a store file and no
    // producer, so nothing could capture it.
    //
    // The membership is an ALLOWLIST of six producer directories rather than a denylist of
    // everything else. A denylist would have to be extended every time someone runs an A/B and
    // leaves a directory behind under cache/visual, and forgetting to extend it silently bakes
    // scratch into a baseline - there are nine such session-leftover directories on disk today.
    //
    // Three producers writing into cache/visual are deliberately NOT members because each already
    // IS an artifact of its own (playerRender, fluidRenderer, portalRenderer), and the six
    // *-parity-vanilla trees are not members because they are per-subject diff panels keyed by a
    // sweep table rather than a byte-gate population. `atlas` is not a member either: it writes
    // build/atlas/, outside the root entirely, and its parallel tile dispatch makes its output
    // permanently unhashable.
    register<Delete>("visualSweepClean") {
        // No group: it is visualSweepSet's first act. A TASK rather than a doFirst on the aggregator,
        // because a doFirst runs after that task's dependencies - which are the very producers whose
        // output it has to clear.
        description = "Erases the six sub-trees visualSweepSet produces, so manifest.visual's population is what the run wrote."
        delete(visualSweepProducers.values.map { "cache/visual/$it" })
    }

    register("visualSweepSet") {
        description = "Runs the visual renders whose output cache/visual sub-tree no other parity artifact covers - the producer of manifest.visual."
        group = "visual"
        // The clear runs first, and the producers are ordered after it. Without that the six
        // sub-trees accumulate across sessions and the artifact's population becomes a function of
        // session history: measured at 255 files where the run itself wrote 153, with
        // entity-render-3d 90 fresh beside 14 stale and block-render-3d 35 beside 83.
        dependsOn("visualSweepClean")
        dependsOn(visualSweepProducers.keys)
    }
    visualSweepProducers.keys.forEach { producer ->
        named(producer) { mustRunAfter("visualSweepClean") }
    }

    // The harness runs. Seven rows over one helper, where there used to be five 30-line Exec bodies
    // re-declaring workingDir, the wrapper path, the OS test and the argv prologue verbatim and
    // differing only by a mode flag, whether they forward -PrefharnessTargets, and which sub-trees
    // they refresh. Those are three columns, so they are rows. The argument for keeping clones is
    // that one can diverge; the recorded history is that divergence IS the defect - one of the five
    // forwarded a MODE flag as if it were a filter, and two accepted no properties at all with no
    // stated reason.
    //
    // A mode is a task, never a pass-through. -PrefharnessPitchRollSweep used to ride
    // renderVanillaReferences, where it made the task render no reference at all - under a name
    // claiming otherwise, and exiting 0. It has its own task now, and so does the depth-quantum
    // probe, which was previously reachable only by cd-ing into the harness.
    registerHarnessRun("renderVanillaReferences", null, true,
        listOf("blocks", "items", "entities", "players"),
        "Runs the harness in FULL mode: blocks, items, entities and the player. Does NOT refresh glint/ or " +
        "armor/ - use renderVanillaAllReferences after any harness render change. ~125 s warm.")

    registerHarnessRun("renderVanillaGlintReferences", "refharnessGlintOnly", true, listOf("glint"),
        "Runs the harness in GLINT mode: references/glint/ only. ~43 s. Then run glintParityVanilla.")

    // forwardsTargets = false on the next two, and the reason is in the sweep rather than in the task:
    // PlayerSweep.honoursTargetFilter() and ArmorSweep.honoursTargetFilter() both return false because
    // neither sweep's subjects have a registry id, so a filter would match nothing and the run would
    // write no reference at all.
    registerHarnessRun("renderVanillaPlayerReferences", "refharnessPlayersOnly", false, listOf("players"),
        "Runs the harness in PLAYERS mode: references/players/ only. Then run playerParityVanilla.")

    registerHarnessRun("renderVanillaArmorReferences", "refharnessArmorOnly", false, listOf("armor"),
        "Runs the harness in ARMOR mode: references/armor/ only. ~27 s. Then run armorParityVanilla.")

    registerHarnessRun("renderVanillaAllReferences", "refharnessEverySweep", true, referenceSubTrees,
        "Runs every sweep in ONE client boot and writes the whole reference tree. ~152 s, which is 43 s " +
        "cheaper than the three narrower tasks run separately. The only task that can leave no sub-tree stale.")

    registerHarnessRun("renderVanillaPitchRollProbe", "refharnessPitchRollSweep", true, emptyList(),
        "Harness PITCH_ROLL probe: renders the first -PrefharnessTargets subject over a 24x24 pitch/roll " +
        "grid into entities-pitch-roll-sweep/, OUTSIDE the reference tree. Refreshes no reference. " +
        "Requires -PrefharnessTargets.")

    registerHarnessRun("renderVanillaDepthQuantumProbe", "refharnessDepthQuantumProbe", false, emptyList(),
        "Harness DEPTH_QUANTUM probe: writes its frames into depth-quantum-probe/, OUTSIDE the reference " +
        "tree. This is the probe that measured the 2^-23 depth quantum. Refreshes no reference.")

    // All eight tooling flows write through one constant into src/main/resources/lib/minecraft/renderer/
    // and therefore dirty tracked files on every run. That is the signal, not a problem, so nothing
    // auto-restores - but the command to undo it is printed rather than remembered. Driven off the
    // artifact table's producer list, so it is one rule rather than eight copies.
    parityArtifacts.single { it.artifact == "manifest.tooling-tables" }.producers.forEach { flow ->
        named(flow) {
            doLast {
                logger.lifecycle("parity: $name rewrote tracked tables under src/main/resources/lib/minecraft/renderer/.")
                logger.lifecycle("  restore with: git restore ':(glob)src/main/resources/lib/minecraft/renderer/*.json'")
            }
        }
    }

    // `./gradlew fonts` now lives in the minecraft-text build at
    // W:/Workspace/Java/Minecraft-Library/minecraft-text. Run it from there when a
    // Minecraft version bump requires regenerating the OTF files.

    // ---- parity: the entry points, and the capture step behind every producer --------------------
    // Group `parity` is deliberately small and countable. The 23 capture steps below carry NO group,
    // because they are finalizers rather than something to run: a human runs a producer, and the
    // capture happens. That is the whole of "route through the same store without remembering a flag".

    register<Exec>("paritySelfTest") {
        description = "Runs the parity toolkit's own unit suite. Every parity task depends on it, because a gate a broken toolkit computed is worse than no gate at all."
        group = "verification"
        parityToolkit("selftest")
        outputs.upToDateWhen { false }
    }

    register<Exec>(parityCaptureBeginTask) {
        // No group: it is parityCapture's first act rather than an entry point. It exists as a task
        // rather than as something each capture step does, because the erase has to happen ONCE per
        // invocation - a step is one process per artifact, so a step-local erase leaves only the row
        // that ran last - and because it has to happen BEFORE every producer, since the rows whose
        // source is the working root are written into it from inside the test JVM.
        description = "Erases the parity working root and opens a capture. Ordered before every producer."
        parityToolkit("capture-begin", "--root", parityWorkingRoot)
        outputs.upToDateWhen { false }
    }

    register<ParityToolkitTask>("parityCapture") {
        description = "Runs the producers of -Partifacts and writes their captures into the parity working root. " +
            "-Partifacts=sweep.entity,manifest.fluid | all | sweeps | renders | dump | tables | pins | digests. " +
            "Absent, it reads the plan's SEES set. -Pruns=<n> -PparityRoot=<dir>"
        group = "parity"
        dependsOn("paritySelfTest")
        dependsOn(parityCaptureBeginTask)
        requireParityRootUnderCache()
        pythonExe.set(parityPythonExe)
        val runs = parityProperty("runs")
        argv.set(buildList {
            add("capture-index")
            add("--root"); add(parityWorkingRoot)
            runs?.let { add("--runs"); add(it) }
        })
        // Both edges are load-bearing. The producer edge is what runs the measurement; the capture-step
        // edge is what orders THIS task's own action - capture-index writes _run/COMPLETE last, and a
        // finalizer is only guaranteed to run after the task it finalizes, not before a third task
        // that depends on that same producer.
        if (parityTaskRequested("parityCapture"))
            resolveParityArtifacts(parityProperty("artifacts")).forEach { spec ->
                spec.producers.forEach { dependsOn(it) }
                dependsOn(parityCaptureTaskName(spec.artifact))
            }
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityCompare") {
        description = "Compares the parity working root against the production store (or -Pbase=<dir>) and fails " +
            "on any mover not listed in the expected-diff. Runs no producer. -Partifacts= -Pbase= -Pexpected= -Pstale=include -Pbootstrap"
        group = "parity"
        dependsOn("paritySelfTest")
        requireParityRootUnderCache()
        // -Partifacts absent is not an error here, and this is the one task where that is true: it has
        // a root to walk where capture and promote have nothing to run. So a bare `parityCompare`
        // compares everything the root holds, which is what lets a human never learn the artifact ids.
        val artifacts = parityProperty("artifacts")
        val base = parityProperty("base") ?: "production"
        val expected = parityProperty("expected")
        val stale = parityProperty("stale") == "include"
        // An artifact with no baseline is MISSING_BASELINE, which is a failure everywhere except the
        // one promotion that establishes it. Without this the toolkit's own refusal names a flag the
        // build never sends, so the first promotion of any artifact is unreachable through Gradle.
        val bootstrap = parityFlag("bootstrap")
        parityToolkit(*buildList {
            add("compare")
            add("--root"); add(parityWorkingRoot)
            add("--base"); add(if (base == "production") parityProductionStore else base)
            artifacts?.let { add("--artifacts"); add(it) }
            expected?.let { add("--expected"); add(it) }
            if (stale) add("--include-stale")
            if (bootstrap) add("--bootstrap")
        }.toTypedArray())
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityPromote") {
        description = "Promotes the parity working root into the production store as the new baseline and writes the " +
            "diff analysis report. Runs no producer. Requires -Preason=<text>. -Partifacts= -Ppopulation=changed -Pclass= -Pbootstrap"
        group = "parity"
        dependsOn("paritySelfTest")
        requireParityRootUnderCache()
        // -Preason has no default and no prompt: a baseline replaced for a reason nobody wrote down is
        // the failure this whole store is built against.
        val reason = parityProperty("reason")
            ?: if (parityTaskRequested("parityPromote"))
                throw GradleException("parityPromote requires -Preason=<why this value is being replaced>")
            else ""
        val artifacts = parityProperty("artifacts")
        val population = parityProperty("population") == "changed"
        // Defaults to `moving` because forgetting it cannot then understate a change.
        val parityClass = parityProperty("class") ?: "moving"
        val bootstrap = parityFlag("bootstrap")
        parityToolkit(*buildList {
            add("promote-apply")
            add("--root"); add(parityWorkingRoot)
            add("--store"); add(parityProductionStore)
            add("--reason"); add(reason)
            add("--class"); add(parityClass)
            artifacts?.let { add("--artifacts"); add(it) }
            if (population) add("--population-changed")
            if (bootstrap) add("--bootstrap")
        }.toTypedArray())
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityPlan") {
        description = "Resolves which parity artifacts can SEE the working tree's change, prints SEES / BLIND / PLAN / " +
            "BUDGET and writes _run/plan.json. Runs no producer and measures nothing. -Pchanged=<paths> -Pformat=json"
        group = "parity"
        // The only dependency, and deliberately not a producer of any kind: a plan a broken toolkit
        // produced is worse than no plan.
        dependsOn("paritySelfTest")
        requireParityRootUnderCache()
        val changed = parityProperty("changed")
        val json = parityProperty("format") == "json"
        parityToolkit(*buildList {
            add("plan")
            add("--root"); add(parityWorkingRoot)
            add("--store"); add(parityProductionStore)
            if (changed == null) add("--changed-from-git")
            else changed.split(",").map(String::trim).filter { it.isNotEmpty() }
                .forEach { add("--changed"); add(it) }
            if (json) { add("--format"); add("json") }
        }.toTypedArray())
        // A plan is a function of the working tree, which Gradle cannot see.
        outputs.upToDateWhen { false }
    }

    // One capture step per artifact row, attached to every task that can produce it. Registered last,
    // so every producer name the table references is already registered.
    parityArtifacts.forEach { registerParityCapture(it) }

    // A producer's stdout is the only source of its row counts and its wall time, and nothing
    // redirected it: ten of the file-producing artifacts are JavaExec, whose stream Gradle discards.
    // Driven off the artifact table so a new row needs no second edit. `test` and `slowTest` are Test
    // rather than JavaExec and are deliberately not reached - their rows self-capture a value instead
    // of printing a count to be parsed.
    withType<JavaExec>().matching { it.name in parityProducerNames }.configureEach {
        val log = layout.buildDirectory.file("parity/producer-$name.log").get().asFile
        doFirst {
            log.parentFile.mkdirs()
            standardOutput = TeeStream(System.out, FileOutputStream(log))
        }
    }
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
