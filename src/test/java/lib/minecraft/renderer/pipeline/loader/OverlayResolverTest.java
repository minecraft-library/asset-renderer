package lib.minecraft.renderer.pipeline.loader;

import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ItemModelData;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Verifies {@link OverlayResolver}'s synthesis rules against fixture item models. The resolver
 * is a pure function with no I/O, so every case is covered with programmatic inputs.
 */
class OverlayResolverTest {

    private static @NotNull ItemModelData modelWithTextures(String... layerPairs) {
        ItemModelData model = new ItemModelData();
        for (int i = 0; i < layerPairs.length; i += 2)
            model.getTextures().put(layerPairs[i], layerPairs[i + 1]);
        return model;
    }

    @Test
    @DisplayName("leather helmet produces Leather overlay with default dye color")
    void resolvesLeatherHelmet() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/leather_helmet",
            "layer1", "minecraft:item/leather_helmet_overlay"
        );
        Optional<Item.Overlay> overlay = OverlayResolver.resolve("minecraft:leather_helmet", model);
        assertThat(overlay.isPresent(), is(true));
        assertThat(overlay.get(), is(instanceOf(Item.Overlay.Leather.class)));
        Item.Overlay.Leather leather = (Item.Overlay.Leather) overlay.get();
        assertThat(leather.baseTexture(), is("minecraft:item/leather_helmet"));
        assertThat(leather.overlayTexture(), is("minecraft:item/leather_helmet_overlay"));
        assertThat(leather.defaultColor(), is(equalTo(Item.Overlay.LEATHER_DEFAULT_ARGB)));
    }

    @Test
    @DisplayName("leather chestplate, leggings, and boots each resolve to Leather overlay")
    void resolvesAllLeatherPieces() {
        for (String id : new String[]{
            "minecraft:leather_chestplate",
            "minecraft:leather_leggings",
            "minecraft:leather_boots"
        }) {
            ItemModelData model = modelWithTextures(
                "layer0", "minecraft:item/" + id.substring("minecraft:".length()),
                "layer1", "minecraft:item/" + id.substring("minecraft:".length()) + "_overlay"
            );
            assertThat(id, OverlayResolver.resolve(id, model).isPresent(), is(true));
        }
    }

    @Test
    @DisplayName("non-leather helmet returns empty (e.g. diamond_helmet)")
    void rejectsNonLeatherHelmet() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/diamond_helmet"
        );
        assertThat(OverlayResolver.resolve("minecraft:diamond_helmet", model).isPresent(), is(false));
    }

    @Test
    @DisplayName("leather helmet missing layer1 returns empty")
    void requiresOverlayLayer() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/leather_helmet"
        );
        assertThat(OverlayResolver.resolve("minecraft:leather_helmet", model).isPresent(), is(false));
    }

    @Test
    @DisplayName("items unrelated to any overlay kind return empty")
    void rejectsUnrelatedItems() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/diamond_sword"
        );
        assertThat(OverlayResolver.resolve("minecraft:diamond_sword", model).isPresent(), is(false));
        assertThat(OverlayResolver.resolve("minecraft:compass", model).isPresent(), is(false));
        assertThat(OverlayResolver.resolve("minecraft:apple", model).isPresent(), is(false));
    }

    @Test
    @DisplayName("potion, splash_potion, and lingering_potion resolve to Potion overlay")
    void resolvesPotionVariants() {
        for (String id : new String[]{ "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion" }) {
            ItemModelData model = modelWithTextures(
                "layer0", "minecraft:item/potion_bottle_drinkable",
                "layer1", "minecraft:item/potion_overlay"
            );
            Optional<Item.Overlay> overlay = OverlayResolver.resolve(id, model);
            assertThat(id, overlay.isPresent(), is(true));
            assertThat(id, overlay.get(), is(instanceOf(Item.Overlay.Potion.class)));
            Item.Overlay.Potion potion = (Item.Overlay.Potion) overlay.get();
            assertThat(potion.baseTexture(), is("minecraft:item/potion_bottle_drinkable"));
            assertThat(potion.overlayTexture(), is("minecraft:item/potion_overlay"));
        }
    }

    @Test
    @DisplayName("tipped_arrow resolves to TippedArrow overlay")
    void resolvesTippedArrow() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/tipped_arrow_base",
            "layer1", "minecraft:item/tipped_arrow_head"
        );
        Optional<Item.Overlay> overlay = OverlayResolver.resolve("minecraft:tipped_arrow", model);
        assertThat(overlay.isPresent(), is(true));
        assertThat(overlay.get(), is(instanceOf(Item.Overlay.TippedArrow.class)));
    }

    @Test
    @DisplayName("firework_star resolves to Firework overlay with the gray placeholder default")
    void resolvesFireworkStar() {
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/firework_star",
            "layer1", "minecraft:item/firework_star_overlay"
        );
        Optional<Item.Overlay> overlay = OverlayResolver.resolve("minecraft:firework_star", model);
        assertThat(overlay.isPresent(), is(true));
        assertThat(overlay.get(), is(instanceOf(Item.Overlay.Firework.class)));
        Item.Overlay.Firework firework = (Item.Overlay.Firework) overlay.get();
        assertThat(firework.defaultColor(), is(equalTo(Item.Overlay.FIREWORK_DEFAULT_ARGB)));
    }

    @Test
    @DisplayName("spawn eggs render through the standard layered-sprite path (no overlay in MC 26.1)")
    void spawnEggsNoLongerUseOverlays() {
        // MC 26.1 ships pre-composited per-entity spawn egg PNGs; the old shared-texture
        // two-color tinting is gone. Any item ending in _spawn_egg should return empty.
        ItemModelData model = modelWithTextures(
            "layer0", "minecraft:item/pig_spawn_egg"
        );
        assertThat(OverlayResolver.resolve("minecraft:pig_spawn_egg", model).isPresent(), is(false));
    }

    @Test
    @DisplayName("potion missing layer1 returns empty")
    void potionRequiresBothLayers() {
        ItemModelData model = modelWithTextures("layer0", "minecraft:item/potion_bottle_drinkable");
        assertThat(OverlayResolver.resolve("minecraft:potion", model).isPresent(), is(false));
    }

}
