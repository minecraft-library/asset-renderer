package lib.minecraft.renderer;

import com.google.gson.Gson;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.gson.GsonSettings;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.model.ModelData;
import lib.minecraft.renderer.asset.model.ModelTexture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit coverage for {@link ItemRenderer#tintIndexForLayer}, the tintindex-resolution helper that
 * drives the standard layered-sprite path for non-overlay items. The rule under test: prefer the
 * tintindex declared on any element face whose texture reference resolves to {@code layerN}
 * (whether the face names {@code #layerN}, the resolved texture id, or a nested {@code #var} that
 * resolves to it); fall back to the vanilla {@code item/generated} convention (layer N has
 * tintindex N) only when the model declares no elements. An element-bearing model with no face
 * owning the layer reports untinted ({@code -1}).
 * <p>
 * Full item rendering is covered end-to-end by the slow
 * {@code PipelineIntegrationTest} (package {@code lib.minecraft.renderer.pipeline}); these cases
 * isolate the dispatch logic without booting the pipeline.
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
        ModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/leather_helmet\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"#layer0\",\"tintindex\":0}}}]}",
            ModelData.class
        );
        Item item = new Item(ResourceId.parse("minecraft:leather_helmet"),
            model, spriteMap(model), 0, List.of(), false);
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(0));
    }

    @Test
    @DisplayName("element face with tintindex -1 reports untinted")
    void elementFaceUntinted() {
        ModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/diamond_sword\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"#layer0\",\"tintindex\":-1}}}]}",
            ModelData.class
        );
        Item item = new Item(ResourceId.parse("minecraft:diamond_sword"),
            model, spriteMap(model), 0, List.of(), false);
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(-1));
    }

    @Test
    @DisplayName("element face matching resolved texture id (not #var) picks up tintindex")
    void elementFaceMatchesByResolvedId() {
        // Face directly references the texture id rather than a #variable.
        ModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/carrot\"},"
                + "\"elements\":[{\"from\":[0,0,7],\"to\":[16,16,9],\"faces\":{"
                + "\"south\":{\"texture\":\"minecraft:item/carrot\",\"tintindex\":0}}}]}",
            ModelData.class
        );
        Item item = new Item(ResourceId.parse("minecraft:carrot"),
            model, spriteMap(model), 0, List.of(), false);
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(0));
    }

    @Test
    @DisplayName("element present but no face references the layer reports untinted")
    void elementsPresentButLayerUnreferenced() {
        // Element references #side, not #layer0, so layer0 has no owning face.
        ModelData model = GSON.fromJson(
            "{\"textures\":{\"layer0\":\"minecraft:item/unrelated\",\"side\":\"minecraft:block/stone\"},"
                + "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                + "\"south\":{\"texture\":\"#side\",\"tintindex\":0}}}]}",
            ModelData.class
        );
        Item item = new Item(ResourceId.parse("minecraft:stick"),
            model, spriteMap(model), 0, List.of(), false);
        assertThat(ItemRenderer.tintIndexForLayer(item, 0), is(-1));
    }

    /**
     * Builds an element-less {@link Item} carrying a single texture variable, exercising the
     * {@code item/generated} fallback branch of {@link ItemRenderer#tintIndexForLayer} where the
     * layer index doubles as its tintindex.
     *
     * @param textureKey the texture-variable name (e.g. {@code layer0})
     * @param textureRef the resolved texture id the variable points at
     * @return the fixture item with no model elements
     */
    private static Item simpleItem(String textureKey, String textureRef) {
        ModelData model = new ModelData();
        model.getTextures().put(textureKey, new ModelTexture(textureRef, false));
        return new Item(ResourceId.parse("minecraft:test"), model, spriteMap(model), 0, List.of(), false);
    }

    /**
     * Flattens a model's texture bindings to their sprite ids, matching the {@code slot -> sprite}
     * shape an {@link Item}'s texture map carries.
     *
     * @param model the model whose bindings to flatten
     * @return the sprite-string map
     */
    private static ConcurrentMap<String, String> spriteMap(ModelData model) {
        ConcurrentMap<String, String> sprites = Concurrent.newMap();
        model.getTextures().forEach((slot, texture) -> sprites.put(slot, texture.sprite()));
        return sprites;
    }

}
