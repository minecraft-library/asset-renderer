package lib.minecraft.renderer.bake.pose;

import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.mesh.EntityMesh;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.engine.frame.StripFit;
import lib.minecraft.renderer.exception.RenderException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The subject, row and strip one entity render takes, resolved once for the render and for every
 * reader of the schedule it draws at.
 *
 * <p>The row is read off the subject's in-force view rather than the definition's shipped catalog,
 * which is what leaves a gated source out of the strip of an appearance that does not admit its gate
 * - the uncharged creeper's idle is still - and puts it in for one that does. The row's entailed
 * toggles fold into the subject before that second read, so the bone a selection draws is part of
 * the subject the strip is fitted over. An id the index holds no row for is refused, and an id whose
 * row draws nothing resolves its style on the bind-only catalog to the still strip with no subject.
 */
@DisplayName("one resolution of an entity's style, shared by a render and its schedule")
class StyleResolutionTest {

    /** The id the two index answers carrying no definition are asked under. */
    private static final @NotNull String UNKNOWN = "minecraft:test";

    /** The shipped entity tables, empty on a checkout that has not generated them. */
    private static ConcurrentMap<String, Entity> entities;

    @BeforeAll
    static void load() {
        entities = EntityModelLoader.load();
    }

    @Test
    @DisplayName("an id the index holds no row for is refused, naming the id")
    void anIdTheIndexHoldsNoRowForIsRefused() {
        RenderException refusal = assertThrows(RenderException.class,
            () -> StyleResolution.of(Possible.absent(), UNKNOWN, PoseStyle.IDLE, AppearanceOptions.defaults()));
        assertTrue(refusal.getMessage().contains(UNKNOWN), refusal.getMessage());
    }

    @Test
    @DisplayName("a row that draws nothing resolves on the bind-only catalog, still and with no subject")
    void aRowThatDrawsNothingResolvesStillWithNoSubject() {
        AppearanceOptions appearance = AppearanceOptions.defaults();
        StyleResolution resolution = StyleResolution.of(Possible.empty(), UNKNOWN, PoseStyle.STRIDE, appearance);

        assertTrue(resolution.subject().isEmpty(), "nothing is drawn, so there is no subject");
        assertEquals(StyleCatalog.BIND_ONLY.resolve(PoseStyle.STRIDE, appearance::applies, UNKNOWN), resolution.row(),
            "the row is the bind-only catalog's answer");
        assertSame(StripFit.STILL, resolution.strip(), "and the strip is one frame");
        assertThrows(RenderException.class,
            () -> StyleResolution.of(Possible.empty(), UNKNOWN, "croak", appearance),
            "a style the bind-only catalog refuses is refused, as a render of it is");
    }

    @Test
    @DisplayName("the row is the one the subject holds in force - a gate its appearance refuses is left out")
    void theRowIsTheSubjectsInForceRow() {
        Entity creeper = definition("minecraft:creeper");

        StyleResolution uncharged = resolve(creeper, PoseStyle.IDLE, AppearanceOptions.defaults());
        assertFalse(gated(uncharged.row()), "an uncharged creeper's idle carries no gated source");
        assertSame(StripFit.STILL, uncharged.strip(), "so it holds still");

        StyleResolution charged = resolve(creeper, PoseStyle.IDLE, AppearanceOptions.builder().charged(true).build());
        assertTrue(gated(charged.row()), "a charged creeper's idle keeps its charged scroll");
        Entity subject = charged.subject().orElseThrow();
        assertSame(PoseStrips.of(subject, charged.row(), subject.styles().periodTicks()), charged.strip(),
            "and its strip is the one fitted for that row over that subject");
    }

    @Test
    @DisplayName("a row's entailed toggles fold into the subject, so the bone it names is drawn")
    void aRowsEntailedTogglesFoldIntoTheSubject() {
        Entity frog = definition("minecraft:frog");
        List<String> croaking = frog.model().getBones().entrySet().stream()
            .filter(bone -> "croak".equals(bone.getValue().getToggle()))
            .map(Map.Entry::getKey)
            .toList();
        assertFalse(croaking.isEmpty(), "the frog's mesh names the bone its croak draws");

        EntityMesh croak = resolve(frog, "croak", AppearanceOptions.defaults()).subject().orElseThrow().model();
        for (String bone : croaking) {
            assertFalse(frog.model().getBones().get(bone).isVisible(), bone + " rests undrawn");
            assertTrue(croak.getBones().get(bone).isVisible(), bone + " is drawn under the croak row");
        }
        assertSame(frog.model(), resolve(frog, PoseStyle.IDLE, AppearanceOptions.defaults()).subject().orElseThrow().model(),
            "and a row entailing no toggle leaves the mesh as authored");
    }

    // ------------------------------------------------------------------------------------

    /** The shipped definition of one id, abandoning the case where the tables are absent. */
    private static @NotNull Entity definition(@NotNull String id) {
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        Entity definition = entities.get(id);
        assumeTrue(definition != null, "the shipped tables answer " + id);
        return definition;
    }

    /** One style of a shipped definition resolved under one appearance. */
    private static @NotNull StyleResolution resolve(
        @NotNull Entity definition, @NotNull String style, @NotNull AppearanceOptions appearance) {

        return StyleResolution.of(Possible.of(definition), definition.id().toString(), style, appearance);
    }

    /** Whether any source of a row is gated. */
    private static boolean gated(@NotNull PoseStyle row) {
        return row.sources().stream().anyMatch(source -> source.gate().isPresent());
    }

}
