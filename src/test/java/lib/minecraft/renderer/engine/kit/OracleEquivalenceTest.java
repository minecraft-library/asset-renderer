package lib.minecraft.renderer.engine.kit;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.appearance.Age;
import lib.minecraft.renderer.asset.pose.Drawn;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.AppearanceOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the two frame oracles equal while both exist: {@link PoseKit#frameAt} answers every
 * render-state figure from the global Java rosters, and the shipped style catalog answers the same
 * figures from the resolved subject's own {@code idle} and {@code stride} rows through
 * {@link PoseStyle#frameAt}.
 *
 * <p>Every subject the index resolves - each entity at its default appearance, plus the baby form
 * of every entity carrying a distinct baby mesh - is compared at every tick of one whole period, at
 * both moving gaits, over every field its shipped poses actually read (recorded off the evaluation,
 * so a figure named only down a branch nothing takes is not counted) plus every field the resolved
 * row drives. Equality is bit equality on the float-narrowed answers: the catalog is the redundant
 * copy of the oracle it stands beside, so any difference at all is a wrong table rather than a
 * tolerance to price, and the whole mismatch list is reported before the assertion fails.
 *
 * <p>The catalog side resolves the way a render does: the catalog narrowed
 * {@link StyleCatalog#inForce in force} for the subject's age, its {@code idle} row against the
 * resting gait and its {@code stride} row against the walking one - which is what resolves an
 * age-split row set to the rows that apply, the baby axolotl answering the universal rows because
 * its carried pair applies to the adult alone.
 */
@DisplayName("the catalog oracle answers the bits the frame oracle answers")
class OracleEquivalenceTest {

    /** The excursions the reference set is drawn at, which is what an unnamed animation means. */
    private static final @NotNull AnimationOptions ANIMATION = AnimationOptions.defaults();

    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("every subject, tick, gait and read field answers the same float bits")
    void theTwoOraclesAnswerTheSameBits() {
        List<String> mismatches = new ArrayList<>();
        List<String> census = new ArrayList<>();
        long comparisons = 0;
        int subjects = 0;
        for (Map.Entry<String, Entity> entry : new TreeMap<>(entities).entrySet()) {
            Entity definition = entry.getValue();
            comparisons += compare(entry.getKey() + "[adult]",
                definition.resolve(AppearanceOptions.defaults()), false, mismatches, census);
            subjects++;
            if (definition.axes().babyModel().isPresent()) {
                comparisons += compare(entry.getKey() + "[baby]",
                    definition.resolve(AppearanceOptions.builder().age(Age.BABY).build()), true,
                    mismatches, census);
                subjects++;
            }
        }
        census.forEach(System.out::println);
        System.out.println("oracle equivalence: " + subjects + " subjects, " + comparisons
            + " comparisons, " + mismatches.size() + " mismatch(es)");
        assertTrue(subjects >= 90, "the corpus is expected to resolve at least its 90 entities");
        assertTrue(mismatches.isEmpty(),
            () -> "the frame oracle and the catalog oracle disagree:\n"
                + String.join("\n", mismatches));
    }

    /** One resolved subject compared at both gaits, answering how many comparisons it took. */
    private static long compare(
        @NotNull String label, @NotNull Entity subject, boolean baby,
        @NotNull List<String> mismatches, @NotNull List<String> census) {

        StyleCatalog catalog = subject.styles().inForce(baby, gate -> true);
        EntityOptions options = EntityOptions.defaults();
        long comparisons = 0;
        StringBuilder line = new StringBuilder(label + ":");
        for (EntityOptions.PoseMode gait : new EntityOptions.PoseMode[]{
            EntityOptions.PoseMode.IDLE, EntityOptions.PoseMode.WALK}) {

            PoseStyle row = catalog.resolve(gait == EntityOptions.PoseMode.WALK
                ? PoseStyle.STRIDE : PoseStyle.IDLE, options);
            TreeSet<String> fields = fieldsReadAt(subject, gait, catalog.periodTicks());
            fields.addAll(row.drivers().keySet());
            line.append(' ').append(gait).append('=').append(fields.size()).append(" fields");

            for (int tick = 0; tick < catalog.periodTicks(); tick++) {
                ToDoubleFunction<String> frame = PoseKit.frameAt(gait, tick, ANIMATION);
                ToDoubleFunction<String> styled = row.frameAt(tick, catalog.periodTicks());
                for (String field : fields) {
                    float old = (float) frame.applyAsDouble(field);
                    float catalogued = (float) styled.applyAsDouble(field);
                    comparisons++;
                    if (Float.floatToIntBits(old) != Float.floatToIntBits(catalogued))
                        mismatches.add(label + " " + gait + " tick " + tick + " '" + field
                            + "': frameAt " + old + " vs style '" + row.id() + "' " + catalogued);
                }
            }
        }
        census.add(line.toString());
        return comparisons;
    }

    /**
     * Every render-state field the subject's poses read at one gait, recorded off the evaluation
     * itself across one whole period - the frame oracle drives the walk, so every branch a real
     * pose takes at that gait is the branch recorded here.
     */
    private static @NotNull TreeSet<String> fieldsReadAt(
        @NotNull Entity subject, EntityOptions.@NotNull PoseMode gait, int periodTicks) {

        TreeSet<String> read = new TreeSet<>();
        for (int tick = 0; tick < periodTicks; tick++) {
            ToDoubleFunction<String> driven = PoseKit.frameAt(gait, tick, ANIMATION);
            ToDoubleFunction<String> recording = field -> {
                read.add(field);
                return driven.applyAsDouble(field);
            };
            for (Drawn one : subject.drawn()) {
                if (!one.pose().isReadable()) continue;
                PoseEvaluator.evaluate(one.pose(), one.model(), recording);
                ClipKit.deltas(one.pose(), one.model(), recording);
            }
        }
        return read;
    }

}
