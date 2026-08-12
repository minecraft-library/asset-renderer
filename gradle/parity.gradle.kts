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
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong

// Everything the parity store is gated by: the roots, the artifact roster, the harness runs, the
// capture steps and the five `parity` entry points. Applied LAST from the root build script, because
// the capture wiring resolves producers by name across the other scripts and a name has to be
// registered before `named` will answer for it.
//
// An applied script gets no type-safe accessors, and no declaration of the root script's is in scope.
// The one value from there this needs is bound below; everything else it reads, it declares.

/**
 * Every `-Dasset.*` in force, as the root script resolved them.
 *
 * <p>Computed there and read here because a function cannot cross an `apply(from = ...)` boundary and
 * a second walk of the system properties would be a second spelling of the prefix. The value is fixed
 * before configuration - a fork inherits these from the daemon - so reading it once is the same
 * answer the walk would give.
 */
@Suppress("UNCHECKED_CAST")
val assetFlagsInForce: Map<String, String> = extra["assetFlagsInForce"] as Map<String, String>

/** No type-safe accessors in an applied script; the one extension the tasks here read. */
val sourceSets = the<SourceSetContainer>()

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
 * Returns whether a command-line boolean option is on: given, and not given the value `false`.
 *
 * <p>Presence alone is not the test, and the reason is what the shipped documentation writes. Both
 * `SKILL.md` and `references/procedures.md` spell these `-Pname=true`, which is the only spelling
 * that makes `-Pname=false` look meaningful - and under a presence test `=false` turns the option
 * ON. A valueless `-Pname` still means on, so the documented spelling and the terse one agree.
 *
 * @param name the -P name
 * @return whether it was given with anything but the value `false`
 */
fun parityFlag(name: String): Boolean =
    gradle.startParameter.projectProperties.containsKey(name) && parityProperty(name) != "false"

/**
 * Returns whether one of this invocation's typed tokens may have named the task.
 *
 * <p><b>It decides one thing: an up-to-date rule.</b> Its single caller marks a self-capturing
 * producer never-up-to-date while a capture is being run, because such a producer's real output is
 * a file in the root the erase has just emptied and Gradle cannot see that - on an unchanged tree
 * `test` is UP-TO-DATE, does not run, and the step then fails on a file nothing rewrote. Answering
 * true for an invocation that goes on to capture nothing costs that invocation a cache entry and
 * nothing else, which is the property that makes an imprecise predicate safe here.
 *
 * <p><b>It over-answers, and cannot be made not to.</b> A task OPTION and its value ride this same
 * list - `tasks --group parity` arrives as `[tasks, --group, parity]` - and nothing on this side
 * can tell an option's value from a task name, because that answer is a function of the option's
 * own arity: a valued option like `--task` is followed by its value, a boolean one like `--rerun`
 * by whatever comes next, which is often the next task. Only the task Gradle matched the option
 * against knows which, so a positional rule here is wrong for one of the two shapes whichever way
 * it is written.
 *
 * <p><b>So nothing that costs anything hangs off it.</b> A refusal goes through
 * `refuseWhenScheduled` and a dependency edge through a lazy `Callable`, both of which ask the
 * RESOLVED graph rather than the tokens. The difference is what a wrong true buys: a refusal thrown
 * for a token that named no task is a build that will not configure, and an edge resolved for one
 * is whatever resolving it costs - and `tasks --group parity` and `help --task parityCapture`
 * realize every registered task to read its description, so both fired on exactly the invocation
 * that runs no parity task at all.
 *
 * <p>It under-answers for nothing, and that is why it is not a literal comparison. The start
 * parameter holds what was TYPED and Gradle expands a camel-case abbreviation to a task name later,
 * so a literal test answers no for `parityCapt` while the build goes on to run `parityCapture` -
 * which leaves the self-capturing producers cached over a root the erase has just emptied, and the
 * step after them fails on a file nothing rewrote. Gradle's own matcher decides it here, so the two
 * cannot disagree about what a token names. The candidate list is the one name being asked about,
 * which over-answers again for a token ambiguous across several tasks - and that invocation fails
 * in Gradle's own resolution anyway.
 *
 * <p><b>`NameMatcher` is Gradle-internal and carries no compatibility guarantee</b>, so a wrapper
 * bump can move it or change its signature. Accepted knowingly, and in the loud direction: the
 * failure is a build script that will not compile, on the first invocation after the bump, rather
 * than a predicate that quietly answers differently. The alternative - a hand-rolled camel matcher
 * - is the one thing this must not be, because a matcher that disagrees with Gradle's about what a
 * token names is the defect it was written to remove.
 *
 * @param name the task name
 * @return whether the command line may have asked for it
 */
fun parityTaskRequested(name: String): Boolean =
    gradle.startParameter.taskNames.any { typed ->
        val token = typed.substringAfterLast(':')
        typed == name || typed.endsWith(":$name") ||
            (token.isNotEmpty() &&
                org.gradle.util.internal.NameMatcher().find(token, listOf(name)) != null)
    }

/**
 * Refuses an invocation that will actually run the named task, before any task of it executes.
 *
 * <p>The question a refusal has to ask is not which tokens were typed but which tasks Gradle
 * resolved them into, and the graph is the only thing that knows: it is built after every token has
 * been matched against the task it belongs to, so an abbreviation, an option and an option's value
 * are already told apart by the code that owns that distinction. `tasks --group parity` and
 * `help --task parityPromote` schedule neither task and reach no refusal; `parityProm` schedules
 * `parityPromote` and reaches its own.
 *
 * <p>It runs where a configuration-time throw used to and keeps that property, which is the one that
 * matters: the graph is ready before the first task executes, so a missing `-Preason` still fails
 * ahead of the multi-minute producer it was going to waste rather than after it. A `doFirst` would
 * not - `parityPromote` is ordered after `parityCompare`, so the refusal would land on the far side
 * of the compare it exists to run before.
 *
 * @receiver the task the refusal belongs to
 * @param refuse what to throw, evaluated only when this task is scheduled
 */
fun Task.refuseWhenScheduled(refuse: () -> Unit) {
    val scheduled = path
    project.gradle.taskGraph.whenReady { if (hasTask(scheduled)) refuse() }
}

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

// The prefix on a line a test JVM wants an operator to read, forwarded to the console below. Gradle
// captures a test's stdout and shows it to nobody, so a self-captured row declining to overwrite a
// finished capture said so into a report file and nowhere else. Declared here rather than typed
// into the listener because `SelfCaptureTest` reads it back out of this file and asserts it is the
// prefix the decline actually prints - two spellings of it would forward nothing and look wired.
val parityOutputPrefix: String = "parity: "

// The parity-gate skill's reference files, two of which are generated from the store's JSON and
// asserted byte-identical by ParityReferencesTest. The suite reads them by filesystem path, so this
// is the Gradle-side spelling of `ParityReferences.HOME` for the same reason the store has one here,
// one in Java and one in Python.
val paritySkillReferences: String = ".claude/skills/parity-gate/references"

// The skill body itself, which states how many entry points it has and then runs them. Nothing
// regenerates it, so it is here for one reason: ParityTaskWiringTest reads that count back against
// the tasks this file registers into the `parity` group, and undeclared, editing the sentence alone
// leaves the suite UP-TO-DATE and the guard never runs on the edit it exists for.
val paritySkillFile: String = ".claude/skills/parity-gate/SKILL.md"

// The directories BlindnessMapTest's own walk skips, at any depth. Kept as one list applied to every
// root below rather than a per-root spelling, because the two lists differing is unnoticeable by
// inspection and means the declaration is either missing files the walk reads or declaring ones it
// never sees.
val parityWalkSkips: List<String> =
    listOf(".git", ".gradle", ".idea", "build", "cache", "texturepacks", ".jmh", "__pycache__")
        .map { "**/$it/**" }

// Every root a blindness rule's trigger glob can match that nothing else already reaches the test
// task. BlindnessMapTest resolves those globs against files on disk to catch a rename orphaning a
// rule, and the main and test trees reach it through the classpath while these do not. The
// exclusions are the walk's skip list and nothing else. Gradle drops the `.gitignore` family from
// every file tree on top of that and the only opt-out is global, so those few stay undeclared -
// measured, and left, because no trigger glob's only match is one of them.
val parityTriggerRoots: FileCollection = files(
    "gradle.properties", "gradlew", "gradlew.bat", "settings.gradle.kts",
    fileTree("gradle") { exclude(parityWalkSkips) },
    fileTree("src/jmh") { exclude(parityWalkSkips) },
    fileTree("tooling") { exclude(parityWalkSkips) },
    fileTree("client") { exclude(parityWalkSkips) },
    fileTree("scripts/parity") { exclude(parityWalkSkips) },
    fileTree("harness") { exclude(parityWalkSkips) }
)

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
// The row that hashes the whole vanilla reference tree, named for the one RULE that asks about that
// one row by id: which rows are a function of the tree. The roster below keeps the id as a literal,
// because the roster is the declaration a rule is resolved against and two suites read those rows as
// data.
val parityReferencesArtifact: String = "manifest.references"

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
) {
    /**
     * Whether this row's producer writes it from inside the test JVM.
     *
     * <p>Read off the source column rather than kept as a second list, because naming the working
     * root IS what the toolkit reads as self-captured - a row that says so here and not there would
     * be refused at capture time with the two spellings disagreeing.
     */
    val selfCaptured: Boolean get() = source == parityWorkingRoot

    /**
     * Whether this row's numbers are a diff against the vanilla reference tree.
     *
     * <p>The six sweeps are exactly those rows, which is why this is a rule over the kind rather
     * than a column: every `*ParityVanilla` producer reads the reference tree and nothing else does.
     * Such a row's provenance carries the digest of that tree's manifest, so a stored number says
     * which ground truth produced it - the one thing that cannot be recovered from the number later.
     * ParityTaskWiringTest holds the rule to that statement, resolving it against the producer
     * column rather than trusting the prefix.
     */
    val referenceDerived: Boolean get() = artifact.startsWith("sweep.")

    /**
     * Whether this row's value is a function of the vanilla reference tree at all.
     *
     * <p>The six that diff against it and the one that hashes it. Wider than `referenceDerived` by
     * exactly that row, and the two part company because a digest of the tree is what a number is
     * measured against and would restate that row's own content - but the render mode that last
     * wrote the tree is a fact about the invocation, which the row that hashes it needs as much.
     */
    val readsReferenceTree: Boolean get() = referenceDerived || artifact == parityReferencesArtifact
}

// Rows 16 to 24 carry the WORKING ROOT ITSELF as their source, with no sub-directory: those producers
// self-capture from inside the test JVM, so the capture step validates and stamps the already-canonical
// file at its own production-relative path rather than reading a producer directory. `--source` naming
// the root IS what marks a row self-captured, so `$parityWorkingRoot/pins` refuses exactly as the
// shipped-tables directory does. Row 16 is one of them and not an exception: `table-canonical` is
// Gson's number formatting, which Python cannot reproduce for the seven float-bearing tables, so no
// reader outside the test JVM can take that digest at all.
// Row 16 is `test` and row 17 is `slowTest` because BundledResourceShaTest runs in the fast suite
// while ClientAcquisitionIntegrationTest is @Tag("slow") - naming `test` for the latter would let a
// fast-suite run credit itself with a value it never computed.
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
    // Shares manifest.visual's parent and its member mechanism, and covers a disjoint file set: that
    // allowlist names the producer directories no other artifact covers, and neither of these two is
    // among them. ONE producer, and it is an aggregator rather than the two sweeps, for
    // manifest.references' reason - a capture taken after one of them would hash one fresh member
    // beside one stale one, and a compare skips what a run did not capture, so a real regression in
    // the un-run half would read clean.
    ParityArtifact("manifest.player-raw", listOf("playerRawSweepSet"), "cache/visual"),
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

/**
 * How long each producer that ran took, in milliseconds, filled as each one finishes.
 *
 * This is what a capture stamps as its row's wall time, and it is the producers rather than the
 * capture step because the step runs after them and takes milliseconds: timing it would answer with
 * the cost of a JSON rewrite where the question is whether to background a twenty-minute render. A
 * row with several producers sums them, because running the row is running all of them.
 *
 * Nothing was ever measured before, so `provenance.gather`'s `wall_time_ms` had no caller, the plan's
 * budget summed absent keys on every artifact, and the rule that says to background a capture above
 * 110 seconds could not fire on any bundle.
 */
val parityProducerElapsedMs: MutableMap<String, Long> = linkedMapOf()

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
 * Expands every alias in a `-Partifacts` list, leaving each other token exactly as it was typed.
 *
 * The three parity commands that take `-Partifacts` disagree about what an unknown token is, so this
 * does the one thing all three need and refuses nothing. `parityCapture` has to reject a token no
 * capture row answers, because it turns each into a producer task; a compare and a promotion work off
 * the store, which holds rows the capture table has none for - the hand-authored rule roster among
 * them - so a token this build does not know is theirs to forward and the toolkit's to refuse.
 *
 * Expanding on capture alone was the whole defect: `-Partifacts=sweeps` captured six rows and then
 * asked the toolkit to compare an artifact called `sweeps`, which resolves to no store path.
 *
 * @param spec the -Partifacts value
 * @return the same list with aliases replaced by their members, comma-joined and without duplicates
 */
fun expandParityAliases(spec: String): String =
    spec.split(",").map(String::trim).filter(String::isNotEmpty)
        .flatMap { token -> parityArtifactAliases[token] ?: listOf(token) }
        .distinct()
        .joinToString(",")

/**
 * The artifact ids a plan selected for capture, or null when no plan has been written.
 *
 * Read through `providers.fileContents` so an absent plan is an absent value rather than an
 * exception, and so the read is a declared input.
 *
 * The `plan` key rather than `sees`, because they answer different questions. `sees` states what the
 * change moves and names artifacts the store keeps no file of its own for - a pointer into another
 * artifact's file, a pin whose home is Java source - and every row here is a file, so feeding it
 * `sees` refuses the whole invocation for every change under the tooling and pipeline packages or
 * `TrimKit`. That refusal lands where this is read: inside the capture's `dependsOn(Callable { })`,
 * which Gradle resolves as it builds the task graph and only for an invocation the graph holds -
 * ahead of every producer, and not on a `tasks --group parity` that merely realizes the task. What
 * the plan leaves out the planner still prints, split into what a captured file already carries and
 * what a human has to read, because a silent drop would read as full coverage.
 *
 * @return the plan's capture set, or null when the working root holds no plan
 */
fun parityPlannedArtifacts(): List<String>? {
    val text = providers
        .fileContents(layout.projectDirectory.file("$parityWorkingRoot/_run/plan.json"))
        .asText.orNull ?: return null
    val plan = groovy.json.JsonSlurper().parseText(text) as Map<*, *>
    return (plan["plan"] as List<*>?)?.map { it.toString() }
}

/**
 * Expands `-Partifacts` into the rows to capture.
 *
 * Three branches in order: an explicit comma list of ids and aliases; absent with a plan, which
 * resolves to the plan's capture set so a human never has to know the ids; absent with no plan,
 * which throws carrying the full id list.
 *
 * @param spec the -Partifacts value, or null when it was not given
 * @return the named rows, in table order and without duplicates
 */
fun resolveParityArtifacts(spec: String?): List<ParityArtifact> {
    val known = parityArtifacts.associateBy { it.artifact }
    val roster = "known artifacts: ${known.keys.joinToString(", ")}\n" +
        "known aliases: ${parityArtifactAliases.keys.joinToString(", ")}"
    val requested = spec?.let { expandParityAliases(it).split(",").filter(String::isNotEmpty) }
        ?: parityPlannedArtifacts()
        ?: throw GradleException(
            "-Partifacts is required when no plan has been written: run parityPlan first, or name " +
                "the artifacts.\n$roster")
    val ids = requested.distinct()
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

// Which HarnessMode each run that WRITES the reference tree selects, filled as the run is REGISTERED
// so the ordering edges below can name every one of them without a second list of their names.
// ParityTaskWiringTest holds every value here to a constant the harness declares, because free text
// in a promoted baseline is free text forever. The two probes are not here and that is the same
// distinction the stamp below draws: each renders into its own directory beside the tree and
// rewrites nothing in it, so nothing reading the tree needs ordering against one.
val harnessRunModes: MutableMap<String, String> = linkedMapOf()

// Which of those runs actually RAN, filled as each one executes. This is what a capture stamps, and
// the distinction is the whole value of the field: the mode a row's declared producer would select
// is a constant restatement of that producer's name, where the mode that refreshed the tree this
// invocation is the difference between a baseline whose ground truth was refreshed in part and one
// whose was refreshed whole. Empty is the honest answer for a capture that refreshed nothing: a
// record claiming ground truth nobody re-measured is worse than one that omits the claim, and the
// digest of the tree beside it already says WHICH references a number was measured against. Sorted,
// and comma-joined where it is stamped, so two runs in one invocation name themselves in an order
// that is a function of the modes rather than of execution timing.
val parityHarnessModesRun: MutableSet<String> = sortedSetOf()

/**
 * Registers one harness run. A mode is a task, never a pass-through property: a mode flag changes
 * what the run produces, and a task named `renderVanillaReferences` that renders no reference is the
 * recorded trap.
 *
 * @receiver the task container the run joins
 * @param name the task name
 * @param modeFlag the harness -P selecting the mode, or null for the harness's full mode
 * @param mode the HarnessMode constant that flag resolves to, held against the harness's own
 *   declaration of it and stamped on this invocation's captures when the run refreshes something
 * @param forwardsTargets whether -PrefharnessTargets is honoured by the sweeps this mode runs
 * @param refreshes the reference sub-trees this run rewrites; empty means it writes outside the
 *   tree, which is also what makes the run no re-measurement of anybody's ground truth
 * @param describe the task description
 * @return the registered task
 */
fun TaskContainer.registerHarnessRun(
    name: String,
    modeFlag: String?,
    mode: String,
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
    // The run happened, so any capture still to come in this invocation reads a tree this mode
    // wrote. doLast rather than doFirst: a render that failed left a half-written tree, and a
    // record naming the mode that produced one is a claim about a run that did not finish. Guarded
    // on what the run REFRESHES, because a probe rewrites no reference: it renders into its own
    // directory beside the tree, so an invocation running one has re-measured no ground truth and a
    // capture stamping its mode says the opposite about numbers taken off references nobody touched.
    if (refreshes.isNotEmpty()) doLast { parityHarnessModesRun += mode }
    // The name-to-mode map is recorded through `also`, which runs where the run is REGISTERED. The
    // configuration block above runs only when the task is realized, and the ordering edges that
    // read this map are wired whether or not a harness run is in the graph.
}.also { if (refreshes.isNotEmpty()) harnessRunModes[name] = mode }

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
    // The -Dasset.* in force on the producing fork. A fork inherits them from a long-lived daemon
    // rather than from the command line, so two captures typed identically can disagree and this is
    // the only place that difference is ever written down.
    val assetFlags = assetFlagsInForce
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
            assetFlags.forEach { (name, value) -> add("--flag"); add("$name=$value") }
            // The ground truth this row's numbers are a diff against, named as the TREE. Naming the
            // captured manifest instead put the field on a harness-triggered capture and nowhere
            // else: an ordinary renderer change's plan selects the sweeps and not the row that
            // hashes the tree, so the file is not there and the one field tying a number to its
            // reference set was absent on every sweep the sanctioned flow produces. The toolkit
            // digests the manifest OF this tree, provenance out, so the value is the stored
            // manifest.references row's own identity whether or not this invocation captured it.
            if (spec.referenceDerived) { add("--reference-tree"); add(parityReferenceRoot) }
        }.toTypedArray())
        // And the render mode behind that tree, appended where the answer exists: which harness run
        // executed is not known while the graph is being configured, and the mode a row's declared
        // producer would name instead is that producer's own name spelled a second way. A row that
        // does not read the tree never carries one.
        if (spec.readsReferenceTree)
            doFirst {
                val ran = parityHarnessModesRun.joinToString(",")
                if (ran.isNotEmpty()) args("--mode", ran)
            }
        // And the wall time of this row's producers, appended for the same reason: how long they
        // took is not known while the graph is configured. Only what RAN is summed, so a step whose
        // producers were up to date or absent from the invocation stamps no duration at all - a zero
        // there would be summed by the plan's budget as an artifact that costs nothing.
        doFirst {
            val elapsed = spec.producers.mapNotNull(parityProducerElapsedMs::get).sum()
            if (elapsed > 0) args("--wall-time", elapsed.toString())
        }
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
        // And after any render of the tree: a render that landed between this row's producer and
        // this step would have the capture name a mode and a manifest the measured value never saw.
        // The producers take the same edge below, which is what makes the pair of stamped fields a
        // statement about the number rather than about task order. No edge orders the two rows that
        // read the tree against EACH OTHER: neither writes into it, so they answer the same question
        // about the same bytes whichever runs first, and this edge is what keeps a render out from
        // between them.
        if (spec.readsReferenceTree) mustRunAfter(harnessRunModes.keys)
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
            // A self-capturing producer's real output is a file in the root the erase just emptied,
            // and Gradle cannot see that: on an unchanged tree `test` is UP-TO-DATE, does not run,
            // and the capture step then fails on a file the erase deleted and nothing rewrote. So a
            // suite that feeds a capture is never up to date - only while a capture is being run,
            // because an ordinary `./gradlew test` has no erase in front of it and keeps its cache.
            if (spec.selfCaptured && parityTaskRequested("parityCapture")) outputs.upToDateWhen { false }
        }
    }
}

// The parity half of the fast suite's configuration. A second `configureEach` beside the root
// script's, which carries the toolchain flag and the property forwarding; Gradle unions them.
tasks.withType<Test>().configureEach {
    systemProperty("asset.parity.root", parityWorkingRoot)
    systemProperty("asset.parity.references", parityReferenceRoot)
    // A self-captured row declines to write into a FINISHED capture and says so. Gradle shows a
    // test's own output to nobody, so the run that skipped the write - an ordinary `test` after a
    // capture, whose author is expecting the file to move - was the one run that heard nothing.
    // Only the toolkit's prefix is forwarded: `showStandardStreams` would carry every line the
    // suite prints with it, and a line nobody can pick out of that is the same as no line.
    addTestOutputListener { _, event ->
        if (event.message.startsWith(parityOutputPrefix))
            logger.lifecycle(event.message.trimEnd())
    }
    // The store is read by filesystem path and excluded from processTestResources, so it reaches the
    // suite by a route Gradle cannot see. Declared here it is an input like any other: without this a
    // promotion leaves `test` UP-TO-DATE and the verification run reports the PRE-promotion answer -
    // measured, on this phase's own promotion. It is the same decision as the classpath exclude
    // rather than a second one: a value the suite reads and the build does not know about is exactly
    // what that exclude makes unrepresentable on the classpath side.
    inputs.dir(parityProductionStore).withPropertyName("parityStore").optional()
    // Three more the suite reads off the filesystem rather than the classpath, and the same decision
    // for the same reason. BlindnessMapTest parses THIS FILE's artifact table and resolves the map's
    // trigger globs against the tree; ParityReferencesTest asserts the skill's generated references
    // are still what their JSON renders to. Undeclared, editing any of them leaves `test` UP-TO-DATE
    // and the guard never fires - measured on the artifact table, where the check and the row it
    // checks live in the same file and a green run said nothing about either.
    //
    // The build file is a WHOLE-SUITE input: Gradle tracks a task rather than a test class, so any
    // build-file edit re-runs all of it. There is no narrower honest form of it, and the alternative
    // is a guard that cannot fail, which is exactly what it exists to catch.
    inputs.file("build.gradle.kts").withPropertyName("parityBuildFile")
    inputs.files(parityTriggerRoots).withPropertyName("parityTriggerRoots")
    // The suite's own sources, which reach the task compiled everywhere but here: PinsTest reads
    // them to find a message prescribing a promotion with no compare in it, and javac emits the
    // same bytes for a javadoc edit that does not move a line. A shipped regeneration runbook lives
    // in one, so the one edit this guard exists to catch is the one Gradle cannot see.
    inputs.dir("src/test/java").withPropertyName("parityTestSources")
    inputs.dir(paritySkillReferences).withPropertyName("paritySkillReferences").optional()
    inputs.file(paritySkillFile).withPropertyName("paritySkillFile")
    // The git index, because the map's coverage and orphan checks resolve against `git ls-files`
    // rather than against a walk: a glob whose only witness is an untracked scratch file is an orphan
    // on every other checkout. Nothing else here declares the index, and the roots above cannot stand
    // in for it - the whole point of the coverage check is that a file tracked under a root NOBODY
    // declared is the one it has to fail on, and that is exactly the edit that would leave this task
    // UP-TO-DATE. The cost is that any git operation rewriting the index re-runs the fast suite,
    // which is the same trade the whole-file build declaration above already makes.
    inputs.file(".git/index").withPropertyName("parityGitIndex").optional()
    // CLAUDE.md, because the blindness map's `source` fields cite its headings by name and
    // BlindnessMapTest reads them back: a heading renamed there turns a citation into a dead one
    // with nothing in this file having moved.
    inputs.file("CLAUDE.md").withPropertyName("parityClaudeMd")
    // The task registry itself, as this build answers it. ParityReferencesTest holds the generated
    // artifact reference's "tasks that carry no artifact id" table to real task names, and two of
    // those rows are tasks a plugin registers and this file never spells - so a check parsing the
    // registrations below would answer with a subset and pass by not looking at them. A provider,
    // so the one read lands after configuration, when nothing further can register a task.
    //
    // Forwarded, and not also declared as an input. Measured by deleting each candidate in turn and
    // moving the registry behind it, `test` re-runs on three routes for three different reasons: a
    // registration typed into this file as "Input property 'parityBuildFile' ... has changed"; an
    // edit under gradle/ as "Input property 'parityTriggerRoots' ... has changed"; and a
    // registration arriving from an init script - the one route that edits no declared file - as
    // "One or more additional actions for task ':test' have changed", which is Gradle
    // re-fingerprinting the action below rather than reading any declaration. Only with the
    // forwarding gone as well does that third move leave the task UP-TO-DATE at an unchanged cache
    // key, so the forwarding is what carries it. BlindnessMapTest keeps the two file declarations
    // in place; the third route rests on the action fingerprint and on nothing written down here.
    val parityTaskNames = provider { tasks.names.joinToString(",") }
    doFirst { systemProperty("asset.parity.task.names", parityTaskNames.get()) }
}

// The parity roots on every JavaExec, beside the root script's own block, which carries the
// toolchain flag and the property forwarding. They go after that forwarding so a resolved root wins
// over a forwarded one.
tasks.withType<JavaExec>().configureEach {
    systemProperty("asset.parity.root", parityWorkingRoot)
    systemProperty("asset.parity.references", parityReferenceRoot)
}

tasks {
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
    registerHarnessRun("renderVanillaReferences", null, "FULL", true,
        listOf("blocks", "items", "entities", "players"),
        "Runs the harness in FULL mode: blocks, items, entities and the player. Does NOT refresh glint/ or " +
        "armor/ - use renderVanillaAllReferences after any harness render change. ~125 s warm.")

    registerHarnessRun("renderVanillaGlintReferences", "refharnessGlintOnly", "GLINT", true, listOf("glint"),
        "Runs the harness in GLINT mode: references/glint/ only. ~43 s. Then run glintParityVanilla.")

    // forwardsTargets = false on the next two, and the reason is in the sweep rather than in the task:
    // PlayerSweep.honoursTargetFilter() and ArmorSweep.honoursTargetFilter() both return false because
    // neither sweep's subjects have a registry id, so a filter would match nothing and the run would
    // write no reference at all.
    registerHarnessRun("renderVanillaPlayerReferences", "refharnessPlayersOnly", "PLAYERS", false, listOf("players"),
        "Runs the harness in PLAYERS mode: references/players/ only. Then run playerParityVanilla.")

    registerHarnessRun("renderVanillaArmorReferences", "refharnessArmorOnly", "ARMOR", false, listOf("armor"),
        "Runs the harness in ARMOR mode: references/armor/ only. ~27 s. Then run armorParityVanilla.")

    registerHarnessRun("renderVanillaAllReferences", "refharnessEverySweep", "EVERY", true, referenceSubTrees,
        "Runs every sweep in ONE client boot and writes the whole reference tree. ~152 s, which is 43 s " +
        "cheaper than the three narrower tasks run separately. The only task that can leave no sub-tree stale.")

    registerHarnessRun("renderVanillaPitchRollProbe", "refharnessPitchRollSweep", "PITCH_ROLL", true, emptyList(),
        "Harness PITCH_ROLL probe: renders the first -PrefharnessTargets subject over a 24x24 pitch/roll " +
        "grid into entities-pitch-roll-sweep/, OUTSIDE the reference tree. Refreshes no reference. " +
        "Requires -PrefharnessTargets.")

    registerHarnessRun("renderVanillaDepthQuantumProbe", "refharnessDepthQuantumProbe", "DEPTH_QUANTUM", false, emptyList(),
        "Harness DEPTH_QUANTUM probe: writes its frames into depth-quantum-probe/, OUTSIDE the reference " +
        "tree. This is the probe that measured the 2^-23 depth quantum. Refreshes no reference.")

    // All eight tooling flows write through one constant into src/main/resources/lib/minecraft/renderer/
    // and therefore dirty tracked files on every run. That is the signal, not a problem, so nothing
    // auto-restores - but the command to undo it is printed rather than remembered. Driven off the
    // artifact table's producer list, so it is one rule rather than eight copies.
    parityArtifacts.single { it.artifact == "manifest.tooling-tables" }.producers.forEach { flow ->
        named(flow) {
            // Read at configuration time so the action captures a String rather than the project.
            val redirected = parityProperty("toolingOut")
            doLast {
                if (redirected == null) {
                    logger.lifecycle("parity: $name rewrote tracked tables under src/main/resources/lib/minecraft/renderer/.")
                    logger.lifecycle("  restore with: git restore ':(glob)src/main/resources/lib/minecraft/renderer/*.json'")
                } else {
                    // A redirected run leaves the tracked tables alone, so there is nothing to restore
                    // and saying otherwise would send a reader to undo a change nobody made. It also
                    // regenerates nothing the store reads, which is what the second line is for.
                    logger.lifecycle("parity: $name wrote to $redirected, leaving the tracked tables untouched.")
                    logger.lifecycle("  manifest.tooling-tables reads src/main/resources - a capture over this run would gate unregenerated bytes.")
                }
            }
        }
    }

    // `./gradlew fonts` now lives in the minecraft-text build at
    // W:/Workspace/Java/Minecraft-Library/minecraft-text. Run it from there when a
    // Minecraft version bump requires regenerating the OTF files.

    // ---- parity: the entry points, and the capture step behind every producer --------------------
    // Group `parity` is deliberately small and countable. The 24 capture steps below carry NO group,
    // because they are finalizers rather than something to run: a human runs a producer, and the
    // capture happens. That is the whole of "route through the same store without remembering a flag".

    register<Exec>("paritySelfTest") {
        description = "Runs the parity toolkit's own unit suite. Every parity task depends on it, because a gate a broken toolkit computed is worse than no gate at all."
        group = "verification"
        parityToolkit("selftest")
        outputs.upToDateWhen { false }
    }

    register<Exec>("harnessClasses") {
        description = "Compiles the vanilla-reference-harness through its own wrapper. The cheap gate on a harness edit: nothing else in this build compiles those sources, so a mistake in them otherwise surfaces at the next client boot."
        group = "verification"
        workingDir = harnessDir.asFile
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val gradlew = harnessDir.file(if (isWindows) "gradlew.bat" else "gradlew").asFile.absolutePath
        // Both source sets, and `clientClasses` is the load-bearing one: Loom's
        // splitEnvironmentSourceSets() puts every harness java file in the CLIENT set, so `classes`
        // alone reports :compileJava NO-SOURCE and succeeds over sources it never read. `classes`
        // stays beside it so a first file under src/main/java is compiled the day it lands.
        commandLine = buildList {
            if (isWindows) { add("cmd"); add("/c") }
            add(gradlew)
            add("classes")
            add("clientClasses")
            // As the reference renders are launched, for the same reason: the harness is a separate
            // build with its own toolchain, and this must not leave a second daemon behind it.
            add("--no-daemon")
        }
    }

    // The one edge that puts both of this build's cheap gates on a verification run; `test` reaches
    // neither. `paritySelfTest` is the toolkit's own suite, and every parity task depends on it - so
    // a break was caught, but not before the next gate, which is the very run the toolkit is being
    // trusted to compute, and the reach map answers a toolkit change with an empty sees and names
    // this task as the gate instead. `harnessClasses` is the only task here that compiles the
    // harness at all, and off this edge it runs only when it is asked for by name, so a harness edit
    // that does not compile waits minutes for a client boot rather than the seconds this costs.
    named("check") { dependsOn("paritySelfTest", "harnessClasses", "toolingTest") }

    register<Exec>(parityCaptureBeginTask) {
        // No group: it is parityCapture's first act rather than an entry point. It exists as a task
        // rather than as something each capture step does, because the erase has to happen ONCE per
        // invocation - a step is one process per artifact, so a step-local erase leaves only the row
        // that ran last - and because it has to happen BEFORE every producer, since the rows whose
        // source is the working root are written into it from inside the test JVM.
        description = "Erases the parity working root and opens a capture. Ordered before every producer."
        // And after the registration, which is the one edge that orders the WHOLE capture chain -
        // every producer and every step is already ordered after this task. The erase spares the
        // expected-diff, so either order leaves the manifest intact and this buys no correctness;
        // what it buys is the documented flow being the flow Gradle picks. Without it, `parityCapture
        // parityExpect` in one invocation schedules the registration last, and the sentence saying
        // the registration runs before the capture is false of the case a reader is likeliest to try.
        mustRunAfter("parityExpect")
        parityToolkit("capture-begin", "--root", parityWorkingRoot)
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityExpect") {
        description = "Registers the movers this change intends into the working root's expected-diff, which is what " +
            "makes the gate diff == expected rather than diff == empty. The manifest survives the capture wipe, so a " +
            "previous change's registration stands until this clears it. -PexpectEmpty | -Partifact= -Pkey= -Pto= -Preason="
        group = "parity"
        dependsOn("paritySelfTest")
        requireParityRootUnderCache()
        val empty = parityFlag("expectEmpty")
        val artifact = parityProperty("artifact")
        val key = parityProperty("key")
        val to = parityProperty("to")
        val reason = parityProperty("reason")
        val members = listOf("artifact" to artifact, "key" to key, "to" to to, "reason" to reason)
        val given = members.filter { (_, value) -> value != null }.map { (name, _) -> name }
        val registers = given.size == members.size
        // Two refusals, and the second is the one the task exists for. -Pto has no default: a
        // registration that names no value licenses any value the row takes, which is the tolerance
        // this gate is built without. And a clear given alongside a registration is two orders in one
        // invocation - taking either silently discards the other, so neither is taken.
        //
        // Raised off the resolved graph, because configuration runs for `gradle tasks` too and the
        // typed tokens cannot say which of them named this task. The argv below carries whatever was
        // given rather than falling back to a clear: the toolkit raises both of these refusals too,
        // so an argv that reached it under-specified would be refused there rather than erasing the
        // registration a previous invocation left - which is what a fallback to --empty does.
        refuseWhenScheduled {
            if (empty && given.isNotEmpty())
                throw GradleException(
                    "parityExpect was given -PexpectEmpty and ${given.joinToString { "-P$it" }} " +
                        "together: a clear and a registration are two different orders, and one of " +
                        "them would be silently dropped. Clear first, then register.")
            if (!empty && !registers)
                throw GradleException(
                    "parityExpect needs -PexpectEmpty to clear the manifest, or all four of " +
                        "-Partifact=<id> -Pkey=<row key> -Pto=<the value it must land on> -Preason=<why>")
        }
        parityToolkit(*buildList {
            add("expect")
            add("--root"); add(parityWorkingRoot)
            if (empty) add("--empty")
            artifact?.let { add("--artifact"); add(it) }
            key?.let { add("--key"); add(it) }
            to?.let { add("--to"); add(it) }
            reason?.let { add("--reason"); add(it) }
        }.toTypedArray())
        outputs.upToDateWhen { false }
    }

    register<ParityToolkitTask>("parityCapture") {
        description = "Runs the producers of -Partifacts and writes their captures into the parity working root. " +
            "-Partifacts=sweep.entity,manifest.fluid | all | sweeps | renders | dump | tables | pins | digests. " +
            "Absent, it reads the plan's capture set. -Pruns=<n> -PparityRoot=<dir>"
        group = "parity"
        dependsOn("paritySelfTest")
        dependsOn(parityCaptureBeginTask)
        requireParityRootUnderCache()
        // A capture is refused under the configuration cache, and this is the one task that refuses
        // it. Every -Dasset.* is read at CONFIGURATION time twice over - once by the forwarder that
        // puts it on each producer's fork, once by the capture step that stamps it as the conditions
        // its numbers were taken under - and both reads walk the same daemon properties, so a reused
        // configuration replays one stored set into both halves at once. The flags typed on the
        // reusing run reach neither, and because the stamp then agrees with the fork there is
        // nothing in the capture to notice: what is left behind is well-formed, promotable, and a
        // record of a run nobody asked for. Raised off the resolved graph, so an ordinary
        // --configuration-cache build of anything else is untouched.
        val configurationCache = project.serviceOf<BuildFeatures>().configurationCache
        refuseWhenScheduled {
            if (configurationCache.requested.getOrElse(false))
                throw GradleException(
                    "parityCapture refuses --configuration-cache: every -Dasset.* is read at " +
                        "configuration time, once onto each producer's fork and once onto the " +
                        "capture's own record of the conditions it measured under, so a reused " +
                        "configuration replays the stored set into both and the flags you type " +
                        "reach neither. The record then agrees with the fork and nothing says " +
                        "otherwise. Re-run without it.")
        }
        pythonExe.set(parityPythonExe)
        // The index records the tree and nothing else. -Pruns reaches each ARTIFACT's provenance
        // through its own capture step, which is where the promotion reads it; the copy that used to
        // ride the index beside it was written by nobody's reader.
        argv.set(buildList {
            add("capture-index")
            add("--root"); add(parityWorkingRoot)
        })
        // Both edges are load-bearing. The producer edge is what runs the measurement; the capture-step
        // edge is what orders THIS task's own action - capture-index writes _run/COMPLETE last, and a
        // finalizer is only guaranteed to run after the task it finalizes, not before a third task
        // that depends on that same producer.
        //
        // Behind a Callable, which Gradle resolves while it builds the graph and only for a task the
        // graph holds. That is what keeps `resolveParityArtifacts`'s refusal - no -Partifacts, no
        // plan - on the invocations that will actually capture. Resolved where the task is merely
        // CONFIGURED it fires on `tasks --group parity` and `help --task parityCapture`, both of
        // which realize every task to read its description, so counting the group's tasks failed on
        // any checkout carrying no plan: the same shape of refusal-at-configuration the promotion
        // reason was moved off. It still lands before the first task executes, so a missing plan
        // costs no producer.
        dependsOn(Callable {
            resolveParityArtifacts(parityProperty("artifacts"))
                .flatMap { spec -> spec.producers + parityCaptureTaskName(spec.artifact) }
        })
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityCompare") {
        description = "Compares the parity working root against the production store (or -Pbase=<dir>) and fails " +
            "on any mover not listed in the expected-diff. Runs no producer. -Partifacts= -Pbase= -Pexpected= -Pbootstrap"
        group = "parity"
        dependsOn("paritySelfTest")
        // A compare reads what a capture wrote, so it never runs before one in the same invocation.
        // Command-line order decided it, and the sanctioned three-invocation runbook happens to get
        // it right; nothing made it so.
        mustRunAfter("parityCapture")
        // And it reads the expected-diff, which is the other input to its verdict. Registering a
        // mover after the compare that was supposed to accept it leaves that compare RED for a
        // reason the operator has already answered.
        mustRunAfter("parityExpect")
        requireParityRootUnderCache()
        // -Partifacts absent is not an error here, and this is the one task where that is true: it has
        // a root to walk where capture and promote have nothing to run. So a bare `parityCompare`
        // compares everything the root holds, which is what lets a human never learn the artifact ids.
        // Given, it is alias-expanded exactly as the capture's is: `sweeps` names six rows to capture
        // and no store path at all, so forwarding the token raw made the documented
        // capture-then-compare pair fail on the second half of itself.
        val artifacts = parityProperty("artifacts")?.let(::expandParityAliases)
        val base = parityProperty("base") ?: "production"
        val expected = parityProperty("expected")
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
            if (bootstrap) add("--bootstrap")
        }.toTypedArray())
        outputs.upToDateWhen { false }
    }

    register<Exec>("parityPromote") {
        description = "Promotes the parity working root into the production store as the new baseline and writes the " +
            "diff analysis report. Runs no producer. Requires -Preason=<text>. -Partifacts= -Ppopulation=changed -Pclass= -Pbootstrap -PallowDirty"
        group = "parity"
        dependsOn("paritySelfTest")
        // `parityPromote parityCompare` promoted the root into the store and then diffed the root
        // against the store it had just been written into - necessarily zero movers - leaving a
        // verdict that says this exact tree was gated. Nothing wipes after a promote, so the hook
        // read that verdict and stayed silent on a commit nothing had compared.
        mustRunAfter("parityCompare")
        requireParityRootUnderCache()
        // -Preason has no default and no prompt: a baseline replaced for a reason nobody wrote down is
        // the failure this whole store is built against. Raised off the resolved graph so `gradle
        // tasks` still reads the description, and there rather than at execution because this task
        // is ordered after parityCompare - a doFirst would refuse on the far side of the compare.
        val reason = parityProperty("reason") ?: ""
        refuseWhenScheduled {
            if (reason.isEmpty())
                throw GradleException("parityPromote requires -Preason=<why this value is being replaced>")
        }
        // Alias-expanded for parityCompare's reason: a promotion scoped with the same token the
        // capture was scoped with has to name the same rows, and `sweeps` is not one of them.
        val artifacts = parityProperty("artifacts")?.let(::expandParityAliases)
        val population = parityProperty("population") == "changed"
        // Defaults to `moving` because forgetting it cannot then understate a change.
        val parityClass = parityProperty("class") ?: "moving"
        val bootstrap = parityFlag("bootstrap")
        // The dirty-tree refusal's only override, and it has to be reachable from here or the
        // refusal is one nobody can answer. It is recorded in the promoted provenance, because an
        // exception nothing writes down cannot be told from the refusal never having fired.
        val allowDirty = parityFlag("allowDirty")
        parityToolkit(*buildList {
            add("promote-apply")
            add("--root"); add(parityWorkingRoot)
            add("--store"); add(parityProductionStore)
            add("--reason"); add(reason)
            add("--class"); add(parityClass)
            artifacts?.let { add("--artifacts"); add(it) }
            if (population) add("--population-changed")
            if (bootstrap) add("--bootstrap")
            if (allowDirty) add("--allow-dirty")
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

    // A sweep reads the vanilla reference tree, so it runs after any render of that tree whenever
    // both are in the graph. Command-line order got this right by convention and nothing made it so,
    // and the cost of the gap is not a stale number: it is a capture stamping the mode and the
    // manifest digest of a tree its own producer never saw, which reads as evidence about the number
    // and is a statement about which task Gradle picked first. A producer that IS a harness run takes
    // no edge - it is the render.
    parityArtifacts.filter { it.readsReferenceTree }
        .flatMap { it.producers }
        .distinct()
        .filter { it !in harnessRunModes }
        .forEach { producer -> named(producer) { mustRunAfter(harnessRunModes.keys) } }

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

    // And its wall time, which is the other thing only the producer can answer. Every producer,
    // whatever its type: the two whole-suite rows are Test tasks and the reference render is an Exec,
    // and a budget missing those is a budget missing the expensive half. `doFirst` prepends, so the
    // start is taken before any action the task already carries.
    parityProducerNames.forEach { producer ->
        named(producer) {
            val startedAt = AtomicLong()
            doFirst { startedAt.set(System.nanoTime()) }
            doLast {
                parityProducerElapsedMs[name] = (System.nanoTime() - startedAt.get()) / 1_000_000L
            }
        }
    }}
