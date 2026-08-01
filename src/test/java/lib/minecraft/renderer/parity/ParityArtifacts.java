package lib.minecraft.renderer.parity;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every parity artifact, where it lives, what produces it and how many runs prove it deterministic.
 *
 * <p>This is a <b>deliberate second copy</b> of the artifact taxonomy, held in Java so that
 * {@code index.json} - which is written by the toolkit - has something independent to be checked
 * against. {@code ParityIndexTest} relates the two, and that relation is what stops an artifact
 * appearing in the store without anyone deciding it should exist. Coining one means editing this
 * roster, which is the point: the registry whose absence let a superseded manifest be cited as
 * current for three phases.
 *
 * <p><b>The determinism floor is how many runs a first promotion performs</b>, not how many it
 * records. What lands in provenance is a measurement - how many runs agreed - and the two are
 * deliberately different numbers, because a floor that doubled as the record would let a declaration
 * pass for evidence.
 */
public final class ParityArtifacts {

    private ParityArtifacts() {}

    /** Where an artifact's value lives, which decides which map of {@code index.json} registers it. */
    public enum Home {

        /** A file in the production store; registered in {@code index.json}'s {@code artifacts} map. */
        STORE,

        /**
         * A JSON pointer into another artifact's file, because a sum that is the sum of the column
         * beside it would otherwise be two places to read one answer. Registered in {@code pointers}.
         */
        POINTER,

        /**
         * Not in the production store at all - a working-root statement about a run, or evidence no
         * gate can reproduce. Registered in {@code external}, which is what stops a citation by path
         * from being the only record that it exists.
         */
        EXTERNAL,

        /**
         * A deliberate second copy of shipped data or vanilla bytecode, held in Java so that a change
         * to the first copy fails loudly. Externalising one would defeat exactly that property, so
         * the store records where it lives and how to re-derive it, and carries no value.
         */
        SOURCE
    }

    /**
     * One artifact's registration.
     *
     * @param id the artifact id
     * @param home where its value lives
     * @param producer the Gradle task whose run produces it, or empty when nothing produces it
     * @param determinismFloor how many runs a first promotion performs to prove it reproducible
     */
    public record Registration(@NotNull String id, @NotNull Home home, @NotNull String producer, int determinismFloor) {

        /** A store artifact with a producer and a floor. */
        private static @NotNull Registration store(@NotNull String id, @NotNull String producer, int floor) {
            return new Registration(id, Home.STORE, producer, floor);
        }

        /** An artifact that is a pointer into another's file. */
        private static @NotNull Registration pointer(@NotNull String id) {
            return new Registration(id, Home.POINTER, "", 0);
        }

        /** An artifact the production store does not hold. */
        private static @NotNull Registration external(@NotNull String id) {
            return new Registration(id, Home.EXTERNAL, "", 0);
        }

        /** A roster or pin whose home is Java source. */
        private static @NotNull Registration source(@NotNull String id) {
            return new Registration(id, Home.SOURCE, "", 0);
        }
    }

    /**
     * The roster, in the spine's own order: sweep-table, render-manifest, file-digest-set, value-pin,
     * roster, report, probe.
     */
    public static final @NotNull List<Registration> ALL = List.of(
        // --- sweep-table. A sweep is exactly reproducible - four fresh JVM forks have agreed row for
        // row - so two runs is the cheapest proof rather than a token one.
        Registration.store("sweep.entity", "entityParityVanilla", 2),
        Registration.store("sweep.block", "blockParityVanilla", 2),
        Registration.store("sweep.item", "itemParityVanilla", 2),
        Registration.store("sweep.player", "playerParityVanilla", 2),
        Registration.store("sweep.armor", "armorParityVanilla", 2),
        Registration.store("sweep.glint", "glintParityVanilla", 2),

        // --- render-manifest. Two-run reproducibility is the precondition that makes a digest
        // comparison admissible at all; the dump pair carries 5 because that is what was measured.
        Registration.store("manifest.references", "renderVanillaAllReferences", 2),
        Registration.store("manifest.visual", "visualSweepSet", 2),
        Registration.store("manifest.dump.vanilla", "parityDump", 5),
        Registration.store("manifest.dump.packs", "parityDump", 5),
        Registration.store("manifest.player-sheets", "playerRender", 2),
        Registration.store("manifest.fluid", "fluidRenderer", 2),
        Registration.store("manifest.portal", "portalRenderer", 2),
        Registration.store("manifest.tooling-tables", "entityModels", 2),

        // --- file-digest-set. A digest of a shipped file is a pure function of that file, so one run
        // is the whole of the proof. digest.dump-sections is the same 28 values the two dump
        // manifests already carry, one per section file, so storing it would be a third copy.
        Registration.store("digest.shipped-tables", "test", 1),
        Registration.store("digest.colormap-lut", "slowTest", 1),
        Registration.external("digest.dump-sections"),

        // --- value-pin. Computed inside a test JVM from bytes the same JVM produced.
        Registration.store("pin.vanilla-iso-pose", "test", 1),
        Registration.store("pin.kit-corners", "test", 1),
        Registration.store("pin.corpus-count", "test", 1),
        Registration.store("pin.player-crc", "slowTest", 1),
        Registration.store("pin.block-crc", "slowTest", 1),
        Registration.store("pin.portal-crc", "slowTest", 1),
        Registration.store("pin.fluid-crc", "slowTest", 1),
        Registration.source("pin.armor-span"),
        // An identity rather than a captured value: re-baselining is not a concept for it.
        Registration.external("pin.tick-lattice"),

        // --- roster. Twelve stay in Java because the second copy IS the mechanism; the thirteenth
        // becomes a store file because it has no home today and its reader is mechanical.
        Registration.source("roster.humanoid-armor"),
        Registration.source("roster.overlay-pipeline"),
        Registration.source("roster.humanoid-part-crop"),
        Registration.source("roster.face-phase"),
        Registration.source("roster.frame-turn"),
        Registration.source("roster.armor-subjects"),
        Registration.source("roster.glint-subjects"),
        Registration.source("roster.player-scopes"),
        Registration.source("roster.sheet-groups"),
        Registration.source("roster.dump-sections"),
        Registration.source("roster.pack-fixtures"),
        Registration.source("roster.appearance-axes"),
        Registration.store("roster.blindness-rules", "", 1),

        // --- report. Seven are derived from one sweep table by its own writer and ride it as fields.
        Registration.pointer("report.sum"),
        Registration.pointer("report.buckets"),
        Registration.pointer("report.coverage-gaps"),
        Registration.pointer("report.canvas-mismatch"),
        Registration.pointer("report.wall-time"),
        Registration.pointer("report.worst-list"),
        Registration.pointer("report.failure-rows"),
        Registration.pointer("report.panel-stats"),
        Registration.pointer("report.glint-frames"),
        Registration.pointer("report.run-provenance"),
        Registration.pointer("report.diagnostics-log"),
        Registration.pointer("report.harness-sweep-counts"),
        // Three statements ABOUT a run rather than values a run produces, so each lives under the
        // working root's _run/ and none is ever promoted.
        Registration.external("report.movers"),
        Registration.external("report.expected-diff"),
        Registration.external("report.plan"),
        Registration.external("report.capture-note"),
        Registration.external("report.harness-fit-log"),
        Registration.store("report.oracle-index", "parityPromote", 1),

        // --- probe. Produced by instrumented builds and deliberate off-tree renders, so neither can
        // be captured, compared or promoted - the three verbs this store exists for.
        Registration.external("probe.pixel"),
        Registration.external("probe.depth-quantum")
    );

    /** Every registration by id. */
    public static final @NotNull Map<String, Registration> BY_ID =
        ALL.stream().collect(Collectors.toUnmodifiableMap(Registration::id, Function.identity()));

    /**
     * Returns every artifact registered with the given home.
     *
     * @param home the home to filter on
     * @return the matching ids, in roster order
     */
    public static @NotNull List<String> withHome(@NotNull Home home) {
        return ALL.stream().filter(entry -> entry.home() == home).map(Registration::id).toList();
    }

    /**
     * Returns one artifact's registration.
     *
     * @param artifactId the artifact id
     * @return its registration
     * @throws ParityStoreException if the id is not registered
     */
    public static @NotNull Registration of(@NotNull String artifactId) {
        Registration registration = BY_ID.get(artifactId);
        if (registration == null)
            throw new ParityStoreException(
                "'%s' is not a registered parity artifact. Coining one is an edit to ParityArtifacts "
                    + "and to the store's index, and ParityIndexTest relates the two",
                artifactId);
        return registration;
    }

}
