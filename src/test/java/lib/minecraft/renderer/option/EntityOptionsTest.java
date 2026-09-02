package lib.minecraft.renderer.option;

import dev.simplified.classbuilder.validate.BuilderValidationException;
import lib.minecraft.renderer.asset.pose.PoseStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The entity options' required id and style knob.
 *
 * <p>The entity id is the one knob every render needs, so a builder that never names one refuses to
 * build rather than handing a renderer a request about nothing; the style defaults to the authored
 * still pose, so a caller that asks for nothing renders the still subject.
 */
@DisplayName("the entity options' required id and style knob")
class EntityOptionsTest {

    @Test
    @DisplayName("building without an entity id is refused")
    void buildingWithoutAnIdIsRefused() {
        assertThrows(BuilderValidationException.class, () -> EntityOptions.builder().build(),
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

}
