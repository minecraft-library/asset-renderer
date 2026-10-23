package lib.minecraft.renderer;

import dev.simplified.collection.ConcurrentMap;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.call.request.AppearanceOptions;
import lib.minecraft.renderer.call.request.EntityOptions;
import lib.minecraft.renderer.content.index.EntityModelLoader;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The renderer's style discovery over the shipped catalog.
 *
 * <p>Discovery answers the same question a render resolves: a known id the shipped catalog, a
 * styles-less entity the bind-only one, and an unknown id the refusal a render of it throws -
 * discovery never invents an entity. The shipped axolotl splits its idle and its play-dead by age,
 * so its catalog is where the per-request resolution is pinned against real data.
 */
@DisplayName("the renderer's style discovery")
class EntityRendererStylesTest {

    private static EntityRenderer renderer;

    @BeforeAll
    static void load() {
        ConcurrentMap<String, Entity> entities = EntityModelLoader.load();
        assumeTrue(!entities.isEmpty(), "entity_models.json not present - run entityModels first");
        renderer = new EntityRenderer(RendererContext.builder().entities(entities).build());
    }

    @Test
    @DisplayName("a known id answers the shipped catalog")
    void aKnownIdAnswersTheShippedCatalog() {
        assertTrue(renderer.styles("minecraft:frog").ids().contains("croak"),
            "the frog's own croak selection is listed");
    }

    @Test
    @DisplayName("a styles-less entity answers the bind-only catalog itself")
    void aStylesLessEntityAnswersBindOnly() {
        assertSame(StyleCatalog.BIND_ONLY, renderer.styles("minecraft:armor_stand"),
            "an armour stand ships no styles and answers the one shared catalog");
    }

    @Test
    @DisplayName("an unknown entity id is refused as a render of it would be")
    void anUnknownIdIsRefused() {
        RendererException refused = assertThrows(RendererException.class,
            () -> renderer.styles("minecraft:nothing"));
        assertTrue(refused.getMessage().contains("minecraft:nothing"),
            "the refusal names the id: " + refused.getMessage());
    }

    @Test
    @DisplayName("the shipped age-split pair resolves per request, one row per age")
    void theShippedAgeSplitPairResolvesPerRequest() {
        StyleCatalog axolotl = renderer.styles("minecraft:axolotl");
        EntityOptions adult = adult();
        EntityOptions baby = baby();
        assertEquals(Optional.of(Age.ADULT),
            axolotl.resolve("play_dead", adult.getAppearance()::applies, adult.getEntityId()).age(),
            "an adult request resolves the adult row");
        assertEquals(Optional.of(Age.BABY),
            axolotl.resolve("play_dead", baby.getAppearance()::applies, baby.getEntityId()).age(),
            "and a baby request the baby one");
    }

    @Test
    @DisplayName("a baby whose family ships an idle at each age answers its own rather than the universal row")
    void aBabyAnswersItsOwnShippedIdle() {
        // The axolotl ships an idle at each age, so a baby request resolves the baby's own row - its
        // float clip selected beside elapsed age - and never falls through to the universal standing row.
        EntityOptions baby = baby();
        PoseStyle idle = renderer.styles("minecraft:axolotl")
            .resolve(PoseStyle.IDLE, baby.getAppearance()::applies, baby.getEntityId());
        assertEquals(Optional.of(Age.BABY), idle.age(), "a baby request resolves the baby's row");
        assertEquals(Set.of("ageInTicks", "idleUnderWaterAnimationState"), idle.drivers().keySet(),
            "which selects the float clip beside elapsed age");
    }

    // ------------------------------------------------------------------------------------

    private static EntityOptions adult() {
        return EntityOptions.of("minecraft:axolotl");
    }

    private static EntityOptions baby() {
        return EntityOptions.builder()
            .entityId("minecraft:axolotl")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();
    }

}
