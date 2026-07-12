package lib.minecraft.renderer.pipeline.pack.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins the {@link SpecialKinds} classifier: every vanilla 26.1 special kind is renderable (maps onto
 * an existing dispatcher), while an unrecognised kind is dropped - the no-fallback contract for
 * special nodes (05-models.md §3.4).
 */
@DisplayName("SpecialKinds classification")
class SpecialKindsTest {

    @Test
    @DisplayName("every vanilla special kind is renderable, namespace-agnostic")
    void vanillaKindsRenderable() {
        for (String kind : new String[]{
            "bed", "chest", "shulker_box", "banner", "conduit", "decorated_pot",
            "shield", "head", "player_head", "copper_golem_statue", "trident"}) {
            assertThat(kind + " (bare)", SpecialKinds.isRenderable(kind), is(true));
            assertThat(kind + " (namespaced)", SpecialKinds.isRenderable("minecraft:" + kind), is(true));
        }
    }

    @Test
    @DisplayName("a known kind resolves; an unknown kind is dropped")
    void resolveOrDrop() {
        ItemModelNode.Special known = new ItemModelNode.Special(
            "minecraft:bed", "minecraft:item/white_bed", Map.of(), SpecialTransform.IDENTITY);
        assertThat("known kind kept", SpecialKinds.resolveOrDrop(known).isPresent(), is(true));

        ItemModelNode.Special unknown = new ItemModelNode.Special(
            "minecraft:future_widget", "minecraft:item/widget", Map.of(), SpecialTransform.IDENTITY);
        assertThat("unknown kind dropped", SpecialKinds.resolveOrDrop(unknown).isPresent(), is(false));
    }

}
