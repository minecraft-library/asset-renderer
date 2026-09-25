package lib.minecraft.renderer.request;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import lib.minecraft.renderer.asset.pose.StyleCatalog;
import lib.minecraft.renderer.asset.pose.StyleClock;
import lib.minecraft.renderer.engine.pose.StyleDriver;
import lib.minecraft.renderer.exception.RendererException;
import lib.minecraft.renderer.vanilla.appearance.Age;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity options' required id and style knob.
 *
 * <p>The entity id is the one knob every render needs, so a builder that never names one refuses to
 * build rather than handing a renderer a request about nothing; the style defaults to the authored
 * still pose, so a caller that asks for nothing renders the still subject. A style row is selected
 * against the request's appearance, which is what says whether an age-scoped row applies.
 */
@DisplayName("the entity options' required id and style knob")
class EntityOptionsTest {

    @Test
    @DisplayName("building without an entity id is refused")
    void buildingWithoutAnIdIsRefused() {
        assertThrows(IllegalStateException.class, () -> EntityOptions.builder().build(),
            "the id is required, not defaulted");
    }

    @Test
    @DisplayName("of answers the given id at the bind style")
    void ofAnswersTheIdAtTheBindStyle() {
        EntityOptions options = EntityOptions.of("minecraft:zombie");
        assertEquals("minecraft:zombie", options.getEntityId());
        assertEquals(PoseStyle.BIND, options.getStyle(), "asking for nothing is the still subject");
    }

    @Test
    @DisplayName("a style the caller names is held by the builder")
    void aNamedStyleIsHeld() {
        EntityOptions options = EntityOptions.builder()
            .entityId("minecraft:frog")
            .style("croak")
            .build();
        assertEquals("croak", options.getStyle());
    }

    @Test
    @DisplayName("a baby-only row applies to a baby and refuses an adult")
    void aBabyOnlyRowFiltersOnAge() {
        PoseStyle rollUp = new PoseStyle("roll_up",
            Concurrent.newUnmodifiableList(
                new PoseStyle.StyleSource(StyleClock.SELECT, Optional.empty())),
            Concurrent.newUnmodifiableMap(Map.of("rollUpAnimationState",
                new StyleDriver("rollUpAnimationState", StyleDriver.Wave.HOLD, 0f, 1f,
                    Optional.of("action")))),
            Concurrent.newUnmodifiableList(), Optional.of(Age.BABY), Optional.empty());
        StyleCatalog catalog = new StyleCatalog(24, Concurrent.newUnmodifiableList(rollUp));
        EntityOptions adult = EntityOptions.of("minecraft:test");
        EntityOptions baby = EntityOptions.builder()
            .entityId("minecraft:test")
            .appearance(AppearanceOptions.builder().age(Age.BABY).build())
            .build();

        assertFalse(adult.getAppearance().applies(rollUp), "the row refuses an adult appearance");
        assertTrue(baby.getAppearance().applies(rollUp), "and applies to a baby one");
        assertEquals("roll_up",
            catalog.resolve("roll_up", baby.getAppearance()::applies, baby.getEntityId()).id());
        assertThrows(RendererException.class,
            () -> catalog.resolve("roll_up", adult.getAppearance()::applies, adult.getEntityId()),
            "a row that does not apply resolves as an unknown id does");
    }

}
