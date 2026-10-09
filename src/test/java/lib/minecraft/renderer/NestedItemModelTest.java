package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import lib.minecraft.renderer.asset.Item;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the model an item definition's index row draws where a pack ships two
 * {@code models/item} files that share a file name in different folders. Vanilla finds the model a
 * definition names by its whole id, so each definition draws its own file, whichever of the two the
 * item named by the shared file name holds.
 * <p>
 * The pack is written to a temporary directory and stacked over the client, which it reads through
 * the shared client-assets extension rather than acquiring one of its own.
 */
@DisplayName("An item definition draws the nested models/item file its whole model id names")
@ExtendWith(ClientAssetsExtension.class)
class NestedItemModelTest {

    /** The canvas every slot render is drawn at. */
    private static final int SIZE = 32;

    /** The fixture pack's namespace. */
    private static final String NAMESPACE = "gems";

    /** The file name both {@code models/item} files share. */
    private static final String SHARED_NAME = "fine_opal_gem";

    @Test
    @DisplayName("two definitions naming same-named models/item files in two folders each draw their own file's texture")
    void eachDefinitionDrawsItsOwnFile(@TempDir Path work) throws IOException {
        Map<String, Integer> folders = Map.of("collections/gemstone/opal", 0xFFFF0000, "slayer/blaze/gemstones", 0xFF0000FF);
        Path pack = work.resolve("nestedpack");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"nested fixture\"}}");
        for (Map.Entry<String, Integer> folder : folders.entrySet())
            writeGem(pack, folder.getKey(), folder.getValue());

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        RendererContext context = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
        ItemRenderer renderer = new ItemRenderer(context);

        for (Map.Entry<String, Integer> folder : folders.entrySet()) {
            String path = folder.getKey() + "/" + SHARED_NAME;
            String definition = NAMESPACE + ":" + path;
            String model = NAMESPACE + ":item/" + path;
            Item item = context.findItem(definition).orElseThrow();

            assertThat(definition + " in a slot", distinctOpaque(renderer.render(slot(definition))), is(Set.of(folder.getValue())));
            assertThat(definition + " draws the model its definition names", item.model(),
                is(sameInstance(context.findItemModel(model).orElseThrow())));
            assertThat(definition + " binds the texture at its model's own path", item.textures().get("layer0"), is(model));
        }
    }

    /**
     * Writes one gem into the pack: a flat {@code models/item} file under the folder, its one-colour
     * texture at the same path, and an item definition at that path naming the model by its whole id.
     *
     * @param pack the pack root
     * @param folder the folder under {@code models/item}, {@code textures/item} and {@code items}
     * @param argb the texture's one colour
     * @throws IOException if a file cannot be written
     */
    private static void writeGem(@NotNull Path pack, @NotNull String folder, int argb) throws IOException {
        String path = folder + "/" + SHARED_NAME;
        String id = NAMESPACE + ":item/" + path;
        Path assets = pack.resolve("assets").resolve(NAMESPACE);
        write(assets.resolve("models/item/" + path + ".json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + id + "\"}}");
        write(assets.resolve("items/" + path + ".json"),
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + id + "\"}}");

        BufferedImage texture = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                texture.setRGB(x, y, argb);
        Path png = assets.resolve("textures/item/" + path + ".png");
        Files.createDirectories(png.getParent());
        ImageIO.write(texture, "PNG", png.toFile());
    }

    /**
     * Builds the options for a flat slot render of one id, with no stack.
     *
     * @param id the item id to render
     * @return the slot options
     */
    private static @NotNull ItemOptions slot(@NotNull String id) {
        return ItemOptions.builder()
            .itemId(id)
            .type(ItemOptions.Type.GUI_2D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build();
    }

    /**
     * Writes one fixture file, creating its parent directories.
     *
     * @param path the file to write
     * @param content the text it holds
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull Path path, @NotNull String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /**
     * Collects the distinct fully-opaque colours a render's first frame carries.
     *
     * @param rendered the render
     * @return every opaque colour present, without duplicates
     */
    private static @NotNull Set<Integer> distinctOpaque(@NotNull RenderResult rendered) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : RenderDigest.firstFramePixels(rendered.image()))
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
    }

}
