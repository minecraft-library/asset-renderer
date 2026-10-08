package lib.minecraft.renderer.asset.item;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.item.ItemModelNode.Special;
import lib.minecraft.renderer.asset.item.ItemModelNode.SpecialTransform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Kind-classification pins for {@link Special}: every vanilla 26.1 special kind is renderable
 * (maps onto an existing dispatcher), while an unrecognised kind is dropped - the no-fallback contract
 * for special nodes.
 */
@DisplayName("ItemModelNode.Special classification")
class ItemModelNodeSpecialTest {

    @Test
    @DisplayName("every vanilla special kind is renderable, bare or under minecraft:")
    void vanillaKindsRenderable() {
        for (String kind : new String[]{
            "bed", "chest", "shulker_box", "banner", "conduit", "decorated_pot",
            "shield", "head", "player_head", "copper_golem_statue", "trident"}) {
            assertThat(kind + " (bare)", ItemModelNode.Special.isRenderable(kind), is(true));
            assertThat(kind + " (namespaced)", ItemModelNode.Special.isRenderable("minecraft:" + kind), is(true));
        }
    }

    @Test
    @DisplayName("a kind is read namespace-exact, as the deserializer reads it: a mod's kind is not vanilla's even where its path spells one")
    void kindsReadNamespaceExact() {
        assertThat(ItemModelNode.Special.isRenderable(":bed"), is(true));
        assertThat(ItemModelNode.Special.isRenderable("somepack:bed"), is(false));
        assertThat(ItemModelNode.Special.isRenderable("minecraft:minecraft:bed"), is(false));
    }

    @Test
    @DisplayName("a known kind resolves; an unknown kind is dropped")
    void resolveOrDrop() {
        ItemModelNode.Special known = new ItemModelNode.Special(
            "minecraft:bed", "minecraft:item/white_bed", Concurrent.newUnmodifiableMap(), SpecialTransform.IDENTITY);
        assertThat("known kind kept", known.resolveOrDrop().isPresent(), is(true));

        ItemModelNode.Special unknown = new ItemModelNode.Special(
            "minecraft:future_widget", "minecraft:item/widget", Concurrent.newUnmodifiableMap(), SpecialTransform.IDENTITY);
        assertThat("unknown kind dropped", unknown.resolveOrDrop().isPresent(), is(false));
    }

}
