package lib.minecraft.renderer;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.model.ItemModelData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link ItemRenderer}'s static helpers. Full rendering is covered end-to-end
 * by the slow {@link lib.minecraft.renderer.pipeline.AssetPipelineIntegrationTest}; these tests focus on
 * the tintindex dispatch logic that drives the standard layered-sprite path for non-overlay
 * items.
 */
class ItemRendererTest {

    private static final Gson GSON = GsonSettings.defaults().create();

    @Test
    @DisplayName("empty elements uses vanilla item/generated convention: layer N has tintindex N")
    void emptyElementsFallsBackToLayerIndex() {
        Item item = simpleItem("layer0", "minecraft:item/grass_block");
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(0));
        assertThat(ItemRenderer.tintIndexForLayer(item, 1), is(1));
        assertThat(ItemRenderer.tintIndexForLayer(item, 7), is(7));
    }

    @Test
    @DisplayName("element face with explicit tintindex overrides the convention")
    void elementFaceTintIndexWins() {
        // Model has one element whose south face references #layer0 with tintindex=0,
        // matching vanilla's item/generated procedural expansion for tintable items.
        ItemModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/leather_helmet\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"#layer0\",\"tintindex\":0}}}]}",
            ItemModelData.class
        );
        Item item = new Item("minecraft:leather_helmet", "minecraft", "leather_helmet",
            model, model.getTextures(), 0, 1, Optional.empty());
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(0));
    }

    @Test
    @DisplayName("element face with tintindex -1 reports untinted")
    void elementFaceUntinted() {
        ItemModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/diamond_sword\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"#layer0\",\"tintindex\":-1}}}]}",
            ItemModelData.class
        );
        Item item = new Item("minecraft:diamond_sword", "minecraft", "diamond_sword",
            model, model.getTextures(), 0, 64, Optional.empty());
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(-1));
    }

    @Test
    @DisplayName("element face matching resolved texture id (not #var) picks up tintindex")
    void elementFaceMatchesByResolvedId() {
        // Face directly references the texture id rather than a #variable.
        ItemModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/carrot\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"minecraft:item/carrot\",\"tintindex\":0}}}]}",
            ItemModelData.class
        );
        Item item = new Item("minecraft:carrot", "minecraft", "carrot",
            model, model.getTextures(), 0, 64, Optional.empty());
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(0));
    }

    @Test
    @DisplayName("element present but no face references the layer reports untinted")
    void elementsPresentButLayerUnreferenced() {
        // Element references #side, not #layer0, so layer0 has no owning face.
        ItemModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/unrelated\",\"side\":\"minecraft:block/stone\"},"
                + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                + "\"south\":{\"texture\":\"#side\",\"tintindex\":0}}}]}",
            ItemModelData.class
        );
        Item item = new Item("minecraft:stick", "minecraft", "stick",
            model, model.getTextures(), 0, 64, Optional.empty());
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(-1));
    }

    private static Item simpleItem(String textureKey, String textureRef) {
        ItemModelData model = new ItemModelData();
        model.getTextures().put(textureKey, textureRef);
        ConcurrentMap<String, String> textures = Concurrent.newMap();
        textures.putAll(model.getTextures());
        return new Item("minecraft:test", "minecraft", "test", model, textures, 0, 64, Optional.empty());
    }

}
