package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.item.ItemModelTree;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of the client's bake refusal: an item definition holding a {@code minecraft:model} leaf
 * whose quads draw sprites from more than one atlas draws the missing item model for every stack, as
 * {@link ItemModelTreeLoader#refuseUnbakeable} holds it, and the {@link ItemsAtlas} membership that
 * decides which atlas a sprite is in.
 * <p>
 * A fixture pack stitches its own {@code fixture/} textures into the blocks atlas and filters
 * {@code item/emerald} out of the items atlas, then shadows five vanilla items: a generated model
 * layering a vanilla {@code item/} sprite under a fixture one, reached only through a display context a
 * slot never selects; a cube one face of which draws a vanilla {@code item/} sprite and the rest a
 * {@code block/} one; a generated model whose second layer resolves to no texture; one layering two
 * {@code item/} sprites, the second of which the filter took out; and two that draw from one atlas
 * alone. Vanilla's own definitions all bake, which the client's loading them without a refusal is.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where
 * nothing has extracted the client yet.
 */
@DisplayName("Item definitions the client cannot bake draw the missing item model")
@ExtendWith(ClientAssetsExtension.class)
class ItemBakeRefusalTest {

    @TempDir
    static Path work;

    /** The vanilla stack with the fixture pack laid over it. */
    private static PackStack stack;

    /** The trees the fixture stack decodes, before the bake refusal runs. */
    private static Map<String, ItemModelTree> decoded;

    /** The trees after it. */
    private static Map<String, ItemModelTree> baked;

    /**
     * Writes the fixture pack and runs the decode and the bake refusal over it.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void bootstrapPipeline() throws IOException {
        Path pack = work.resolve("bake_refusal");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"bake refusal fixture\"}}");
        write(pack.resolve("assets/minecraft/atlases/blocks.json"),
            "{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"fixture\",\"prefix\":\"fixture/\"}]}");
        write(pack.resolve("assets/minecraft/atlases/items.json"),
            "{\"sources\":[{\"type\":\"minecraft:filter\",\"pattern\":{\"path\":\"^item/emerald$\"}}]}");
        Path stripe = pack.resolve("assets/minecraft/textures/fixture/stripe.png");
        Files.createDirectories(stripe.getParent());
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", stripe.toFile());

        write(model(pack, "bake_mixed_layers"), generated("minecraft:item/diamond", "minecraft:fixture/stripe"));
        write(model(pack, "bake_unresolved_layer"), generated("minecraft:item/diamond", "#nowhere"));
        write(model(pack, "bake_filtered_layer"), generated("minecraft:item/diamond", "minecraft:item/emerald"));
        write(model(pack, "bake_blocks_only"), generated("minecraft:fixture/stripe"));
        write(model(pack, "bake_items_only"), generated("minecraft:item/diamond", "minecraft:item/gold_ingot"));
        write(model(pack, "bake_mixed_faces"), """
            {"textures": {"side": "minecraft:block/stone", "top": "minecraft:item/diamond"},
             "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
               "up": {"texture": "#top"}, "down": {"texture": "#side"}, "north": {"texture": "#side"},
               "south": {"texture": "#side"}, "east": {"texture": "#side"}, "west": {"texture": "#side"}}}]}""");

        write(definition(pack, "diamond"), """
            {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
              "cases": [{"when": "fixed", "model": {"type": "minecraft:model", "model": "minecraft:item/bake_mixed_layers"}}],
              "fallback": {"type": "minecraft:model", "model": "minecraft:item/diamond"}}}""");
        write(definition(pack, "iron_ingot"), leaf("bake_mixed_faces"));
        write(definition(pack, "copper_ingot"), leaf("bake_unresolved_layer"));
        write(definition(pack, "gold_ingot"), leaf("bake_filtered_layer"));
        write(definition(pack, "emerald"), leaf("bake_blocks_only"));
        write(definition(pack, "netherite_ingot"), leaf("bake_items_only"));

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stack = PackAcquisition.acquire(new ClientAssets(options, ClientAssetsExtension.vanilla()));
        decoded = ItemModelTreeLoader.load(stack);
        baked = ItemModelTreeLoader.refuseUnbakeable(decoded, ResolvedModels.load(stack), ItemsAtlas.load(stack));
    }

    @Test
    @DisplayName("vanilla's own definitions all bake")
    void vanillaBakes() {
        PackStack vanilla = PackAcquisition.acquire(new ClientAssets(
            ClientOptions.builder().cacheRoot(work.resolve("cache").toFile()).build(), ClientAssetsExtension.vanilla()));
        Map<String, ItemModelTree> trees = ItemModelTreeLoader.load(vanilla);
        Map<String, ItemModelTree> refused = ItemModelTreeLoader.refuseUnbakeable(trees, ResolvedModels.load(vanilla), ItemsAtlas.load(vanilla));

        assertThat(refused.entrySet().stream().filter(entry -> entry.getValue().isRejected()).map(Map.Entry::getKey).toList(), is(empty()));
    }

    @Test
    @DisplayName("the items atlas holds every item/ sprite, the trim permutations and the missing sprite, and no block/ one")
    void itemsAtlasMembership() {
        PackStack vanilla = PackAcquisition.acquire(new ClientAssets(
            ClientOptions.builder().cacheRoot(work.resolve("cache").toFile()).build(), ClientAssetsExtension.vanilla()));
        ItemsAtlas atlas = ItemsAtlas.load(vanilla);

        assertThat(atlas.contains("minecraft:item/diamond"), is(true));
        assertThat("a bare id reads as a minecraft: one", atlas.contains("item/diamond"), is(true));
        assertThat("a paletted permutation", atlas.contains("minecraft:trims/items/helmet_trim_amethyst"), is(true));
        assertThat(atlas.contains("minecraft:missingno"), is(true));
        assertThat(atlas.contains("minecraft:block/stone"), is(false));
        assertThat("the fixture's filter takes a sprite back out", ItemsAtlas.load(stack).contains("minecraft:item/emerald"), is(false));
        assertThat("and leaves the rest", ItemsAtlas.load(stack).contains("minecraft:item/diamond"), is(true));
    }

    @Test
    @DisplayName("a leaf drawing from two atlases refuses its whole definition, whichever branch it sits on")
    void mixedLeavesRefuse() {
        for (String id : List.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot")) {
            assertThat(id + " decodes", decoded.get(id).isRejected(), is(false));
            assertThat(id + " does not bake", baked.get(id).isRejected(), is(true));
        }
    }

    @Test
    @DisplayName("a definition every leaf of which draws from one atlas bakes, the blocks atlas or the items atlas")
    void singleAtlasLeavesBake() {
        for (String id : List.of("minecraft:emerald", "minecraft:netherite_ingot"))
            assertThat(id + " bakes", baked.get(id).isRejected(), is(false));
    }

    /** A generated item model layering the given sprites from {@code layer0} up. */
    private static @NotNull String generated(@NotNull String @NotNull ... layers) {
        StringBuilder textures = new StringBuilder();
        for (int layer = 0; layer < layers.length; layer++)
            textures.append(layer == 0 ? "" : ", ").append("\"layer").append(layer).append("\": \"").append(layers[layer]).append('"');
        return "{\"parent\": \"minecraft:item/generated\", \"textures\": {" + textures + "}}";
    }

    /** A definition rooted at one model leaf naming a fixture model. */
    private static @NotNull String leaf(@NotNull String model) {
        return "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/" + model + "\"}}";
    }

    private static @NotNull Path model(@NotNull Path pack, @NotNull String name) {
        return pack.resolve("assets/minecraft/models/item/" + name + ".json");
    }

    private static @NotNull Path definition(@NotNull Path pack, @NotNull String name) {
        return pack.resolve("assets/minecraft/items/" + name + ".json");
    }

    private static void write(@NotNull Path path, @NotNull String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

}
