package lib.minecraft.renderer.asset.equipment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of {@link LayerType}: the serialized-id round-trip and the vanilla-parity constant count.
 */
@DisplayName("LayerType serialized ids")
class LayerTypeTest {

    @Test
    @DisplayName("fromId round-trips every constant's serialized id")
    void fromIdRoundTrips() {
        for (LayerType type : LayerType.values())
            assertThat(LayerType.fromId(type.getId()), is(Optional.of(type)));
    }

    @Test
    @DisplayName("the constant count matches the vanilla enum")
    void constantCountMatchesVanilla() {
        assertThat(LayerType.values().length, is(19));
    }

    @Test
    @DisplayName("known ids map to the right constant and back")
    void knownIds() {
        assertThat(LayerType.fromId("humanoid"), is(Optional.of(LayerType.HUMANOID)));
        assertThat(LayerType.fromId("humanoid_leggings"), is(Optional.of(LayerType.HUMANOID_LEGGINGS)));
        assertThat(LayerType.fromId("wings"), is(Optional.of(LayerType.WINGS)));
        assertThat(LayerType.WINGS.getId(), is("wings"));
    }

    @Test
    @DisplayName("an unknown id resolves to empty")
    void unknownEmpty() {
        assertThat(LayerType.fromId("not_a_layer").isPresent(), is(false));
    }

}
