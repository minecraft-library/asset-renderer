package lib.minecraft.renderer.parity;

import dev.simplified.annotations.UtilityClass;
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
@UtilityClass
public final class ParityArtifacts {

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
     * @param producers the Gradle tasks a capture of it runs, in the build file's order - or what
     *   writes the file where no capture step covers one - and empty when nothing writes it
     * @param determinismFloor how many runs a first promotion performs to prove it reproducible
     */
    public record Registration(@NotNull String id, @NotNull Home home, @NotNull List<String> producers,
                               int determinismFloor) {

        /**
         * A store artifact with its producers and a floor.
         *
         * <p>A list rather than one name, because a manifest can be defined over what several flows
         * emit and running the first of them leaves the rest of the file set stale. The whole set is
         * rendered into the skill's artifact reference, which is the column a reader with no budget
         * is sent to, and {@code ParityIndexTest} holds it to the build file's own row - or, for the
         * two root files that table has no row for, to what writes each of those files.
         */
        private static @NotNull Registration store(@NotNull String id, int floor, @NotNull String... producers) {
            return new Registration(id, Home.STORE, List.of(producers), floor);
        }

        /** An artifact that is a pointer into another's file. */
        private static @NotNull Registration pointer(@NotNull String id) {
            return new Registration(id, Home.POINTER, List.of(), 0);
        }

        /** An artifact the production store does not hold. */
        private static @NotNull Registration external(@NotNull String id) {
            return new Registration(id, Home.EXTERNAL, List.of(), 0);
        }

        /** A roster or pin whose home is Java source. */
        private static @NotNull Registration source(@NotNull String id) {
            return new Registration(id, Home.SOURCE, List.of(), 0);
        }
    }

    /**
     * The roster, in the spine's own order: sweep-table, render-manifest, file-digest-set, value-pin,
     * roster, report, probe.
     */
    public static final @NotNull List<Registration> ALL = List.of(
        // --- sweep-table. A sweep is exactly reproducible - four fresh JVM forks have agreed row for
        // row - so two runs is the cheapest proof rather than a token one.
        Registration.store("sweep.entity", 2, "entityParityVanilla"),
        Registration.store("sweep.block", 2, "blockParityVanilla"),
        Registration.store("sweep.item", 2, "itemParityVanilla"),
        Registration.store("sweep.player", 2, "playerParityVanilla"),
        Registration.store("sweep.armor", 2, "armorParityVanilla"),
        Registration.store("sweep.glint", 2, "glintParityVanilla"),
        Registration.store("sweep.menu", 2, "menuParityVanilla"),
        // The eighth sweep is the only one measured against a moving client: its ground truth is the
        // animation/ sub-tree, which one boot writes with the setupAnim freezes off while the seven
        // above are what those freezes are ground truth FOR. Same floor, for the same reason - a
        // sweep is a pure function of two file sets.
        Registration.store("sweep.entity-animation", 2, "entityAnimationParityVanilla"),

        // --- render-manifest. Two-run reproducibility is the precondition that makes a digest
        // comparison admissible at all.
        Registration.store("manifest.references", 2, "renderVanillaAllReferences"),
        Registration.store("manifest.visual", 2, "visualSweepSet"),
        // The raw halves of the two sweeps that rescale before diffing. Its producer is an aggregator
        // over both of them rather than either one, because a manifest captured after a single sweep
        // would hash one fresh member beside one stale one and compare clean.
        Registration.store("manifest.player-raw", 2, "playerRawSweepSet"),
        // The dump pair carried 5 while the hazard it guarded was live: `capabilities` is a
        // `Set.copyOf` whose iteration order is salted per JVM launch, and several launches are what
        // catches a salt that only sometimes flips. PipelineParityDump now re-sorts that set and
        // `namespaces` AT EMIT, so no field's runtime iteration order reaches the bytes, and five
        // launches produce one digest for each of the two - measured twice over, once on the
        // pipeline-phase captures and once fresh. A floor is how many runs prove reproducibility, and
        // two is what proves it once the emit is order-free.
        Registration.store("manifest.dump.vanilla", 2, "parityDump"),
        Registration.store("manifest.dump.packs", 2, "parityDump"),
        Registration.store("manifest.player-sheets", 2, "playerRender"),
        Registration.store("manifest.fluid", 2, "fluidRenderer"),
        Registration.store("manifest.portal", 2, "portalRenderer"),
        // Every flow that writes one of the shipped tables, because the manifest is one digest set
        // over all of them and no aggregator task drives them: running entityModels alone rewrites
        // three and captures the rest as they were, and the manifest hashes cleanly over the mixture
        // because every declared member exists.
        Registration.store("manifest.tooling-tables", 2, "entityModels", "blockModels", "blockDefaults",
            "blockItems", "blockTints", "potionColors", "glintItems", "colorMaps"),

        // --- file-digest-set. A digest of a shipped file is a pure function of that file, so one run
        // is the whole of the proof. digest.dump-sections is the same 28 values the two dump
        // manifests already carry, one per section file, so storing it would be a third copy.
        Registration.store("digest.shipped-tables", 1, "test"),
        Registration.store("digest.colormap-lut", 1, "slowTest"),
        Registration.external("digest.dump-sections"),

        // --- value-pin. Computed inside a test JVM from bytes the same JVM produced.
        Registration.store("pin.vanilla-iso-pose", 1, "test"),
        Registration.store("pin.kit-corners", 1, "test"),
        Registration.store("pin.corpus-count", 1, "test"),
        Registration.store("pin.player-crc", 1, "slowTest"),
        Registration.store("pin.block-crc", 1, "slowTest"),
        Registration.store("pin.portal-crc", 1, "slowTest"),
        Registration.store("pin.fluid-crc", 1, "slowTest"),
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
        Registration.store("roster.blindness-rules", 1),

        // --- report. A pointer is derived by the writer of the file it points into and rides that
        // file as one of its fields, so it has no producer of its own. Three more were registered
        // over row columns no sweep writer has ever emitted - a canvas mismatch, the panel numbers
        // and a glint frame delta - and each is a diagnosis a human reads off a panel rather than a
        // value a gate reproduces, so they are retired instead of grown a producer.
        Registration.pointer("report.sum"),
        Registration.pointer("report.buckets"),
        Registration.pointer("report.coverage-gaps"),
        Registration.pointer("report.wall-time"),
        Registration.pointer("report.worst-list"),
        Registration.pointer("report.failure-rows"),
        Registration.pointer("report.run-provenance"),
        Registration.pointer("report.diagnostics-log"),
        Registration.pointer("report.harness-sweep-counts"),
        // Statements ABOUT a run rather than values a run produces, so none of them is ever
        // promoted. Where each one lives is its own answer and index.json carries it: the plan, the
        // movers and the expected-diff under the working root's _run/, the capture note in a commit
        // message, the harness fit log in the tee under build/.
        Registration.external("report.movers"),
        Registration.external("report.expected-diff"),
        Registration.external("report.plan"),
        Registration.external("report.capture-note"),
        Registration.external("report.harness-fit-log"),
        Registration.store("report.oracle-index", 1, "parityPromote"),

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
