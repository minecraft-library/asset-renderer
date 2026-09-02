package lib.minecraft.renderer.pipeline.index;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import lib.minecraft.renderer.tensor.Matrix4f;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The composition contract for a block overlay's placement: the shipped {@code transforms} ops fold to
 * one matrix at index build, and the render frame gets the product rather than the operands.
 *
 * <p>A fold has exactly two ways to be wrong and they are both silent. It can compose in the wrong
 * ORDER - matrix multiplication does not commute, so a reversed chain is a different placement that
 * still renders something plausible - and it can DROP an operand, which reads as a row that simply sits
 * somewhere else. The first two tests pin the order against a longhand composition and then prove that
 * pin is load-bearing by showing the reverse disagrees; the third counts the operands back out.
 */
@DisplayName("block-overlay transform fold")
class BlockOverlayTransformFoldTest {

    /** The snow golem's pumpkin, the shortest shipped chain that uses four of the five op kinds. */
    private static final String SNOW_GOLEM = "minecraft:snow_golem";

    /**
     * The four ops {@code snow_golem}'s shipped row declares, composed longhand in declared order. Written
     * out rather than looped so the order under test is visible in the source of the test asserting it.
     */
    private static @NotNull Matrix4f snowGolemLonghand() {
        return Matrix4f.IDENTITY
            .translate(0f, -0.34375f, 0f)
            .rotateY((float) Math.toRadians(180f))
            .scale(0.625f, -0.625f, -0.625f)
            .translate(-0.5f, -0.5f, -0.5f);
    }

    @Test
    @DisplayName("folds its ops in declared order")
    void foldsInDeclaredOrder() {
        Entity golem = EntityModelLoader.load().get(SNOW_GOLEM);
        List<Entity.BlockOverlayLayer> overlays = golem.blockOverlays();
        assertThat("the snow golem ships one block overlay", overlays.size(), is(1));
        assertEntries("the folded placement", overlays.getFirst().transform(), snowGolemLonghand());
    }

    @Test
    @DisplayName("the declared order is load-bearing, so the reverse disagrees")
    void reverseOrderDisagrees() {
        Matrix4f reversed = Matrix4f.IDENTITY
            .translate(-0.5f, -0.5f, -0.5f)
            .scale(0.625f, -0.625f, -0.625f)
            .rotateY((float) Math.toRadians(180f))
            .translate(0f, -0.34375f, 0f);
        assertThat("a reversed chain is a different placement, so the order test can fail",
            entries(reversed), not(is(entries(snowGolemLonghand()))));
    }

    @Test
    @DisplayName("every shipped chain reaches the row that carries it")
    void everyShippedChainSurvivesTheFold() {
        ConcurrentMap<String, Entity> definitions = EntityModelLoader.load();
        int rows = 0;
        for (Entity definition : definitions.values())
            for (Entity.BlockOverlayLayer overlay : definition.blockOverlays()) {
                rows++;
                // Every shipped chain ends in a translate, so none of them composes to the identity.
                // A fold that silently dropped its operands would leave exactly that behind.
                assertThat(definition.id() + " composes to a placement rather than the identity",
                    entries(overlay.transform()), not(is(entries(Matrix4f.IDENTITY))));
            }
        assertThat("the four block-overlay subjects still ship their rows",
            rows, is(greaterThanOrEqualTo(6)));
    }

    /**
     * Asserts two matrices agree entry for entry, which is exact equality - the fold's whole claim is
     * that it changed when the arithmetic runs and not what the arithmetic is, so a tolerance here would
     * admit the drift it exists to catch.
     */
    private static void assertEntries(@NotNull String reason, @NotNull Matrix4f actual, @NotNull Matrix4f expected) {
        assertThat(reason, entries(actual), is(entries(expected)));
    }

    /** A matrix as its sixteen entries, in the column-major order the type stores them. */
    private static @NotNull List<Float> entries(@NotNull Matrix4f matrix) {
        List<Float> out = new ArrayList<>(16);
        for (int col = 1; col <= 4; col++)
            for (float value : matrix.column(col)) out.add(value);
        return out;
    }
}
