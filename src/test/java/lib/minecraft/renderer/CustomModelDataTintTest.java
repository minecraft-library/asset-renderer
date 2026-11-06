package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import lib.minecraft.nbt.tag.CompoundTag;
import lib.minecraft.nbt.tag.IntArrayTag;
import lib.minecraft.nbt.tag.IntTag;
import lib.minecraft.nbt.tag.StringTag;
import lib.minecraft.renderer.call.request.ItemContext;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.store.diff.RenderDigest;
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
import java.util.Set;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of a {@code minecraft:custom_model_data} tint drawn as the client draws it: each tinted face
 * takes the stack's {@code minecraft:custom_model_data} {@code colors} entry at the tint's index, made
 * opaque, else the tint's {@code default} made opaque.
 * <p>
 * A fixture pack stacked over the client writes the source in the shape ItemsAdder's player-model
 * composites write it - {@code "default":0} over faces whose texture is solid white - so with no stack
 * the client draws those faces opaque black, and with a stack it draws them in the stack's colour. No
 * vanilla item definition and no pack the parity store reads writes the source, so these rows are the
 * only witness.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where nothing
 * has extracted the client yet.
 */
@DisplayName("A custom_model_data tint draws the stack's colour, else its opaque default")
@ExtendWith(ClientAssetsExtension.class)
class CustomModelDataTintTest {

    /** The item whose one model carries a {@code custom_model_data} tint at index 1. */
    private static final String ITEM = "fixture:tinted";

    /** The tint's {@code default}, {@code 0}, made opaque. */
    private static final int BLACK = 0xFF000000;

    /** The stack's colour at the tint's index, made opaque. */
    private static final int GREEN = 0xFF00FF00;

    /** The canvas every render here draws at. */
    private static final int SIZE = 128;

    /** The client with the fixture pack stacked over it. */
    private static RendererContext stacked;

    /**
     * Writes the fixture pack and stacks it over the client.
     *
     * @param work the directory the pack and the cache root are written under
     * @throws IOException if a fixture file cannot be written
     */
    @BeforeAll
    static void stackThePack(@TempDir Path work) throws IOException {
        Path pack = work.resolve("tintpack");
        Path assets = pack.resolve("assets/fixture");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"custom model data tint fixture\"}}");

        png(assets.resolve("textures/item/white.png"));
        write(assets.resolve("models/item/pixel.json"), "{\"parent\":\"minecraft:item/generated\","
            + "\"textures\":{\"0\":\"fixture:item/white\",\"particle\":\"fixture:item/white\"},"
            + "\"elements\":[{\"shade\":false,\"from\":[0,0,7.5],\"to\":[16,16,8.5],\"faces\":{"
            + face("north") + "," + face("south") + "}}]}");
        write(assets.resolve("items/tinted.json"), "{\"model\":{\"type\":\"minecraft:model\","
            + "\"model\":\"fixture:item/pixel\","
            + "\"tints\":[{\"type\":\"custom_model_data\",\"index\":1,\"default\":0}]}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("with no stack, every tinted face draws the default made opaque - black for a default of 0")
    void noStackDrawsTheOpaqueDefault() {
        RenderResult rendered = render(ItemOptions.builder().itemId(ITEM));

        assertThat(rendered.substitutions(), is(empty()));
        assertThat(opaque(rendered).keySet(), is(Set.of(BLACK)));
    }

    @Test
    @DisplayName("with a stack, every tinted face draws the stack's colour at the tint's index, made opaque")
    void aStackDrawsItsColourAtTheIndex() {
        CompoundTag data = new CompoundTag();
        data.put("colors", new IntArrayTag(0xFF0000, 0x00FF00));
        CompoundTag components = new CompoundTag();
        components.put("minecraft:custom_model_data", data);
        CompoundTag stack = new CompoundTag();
        stack.put("id", new StringTag(ITEM));
        stack.put("count", new IntTag(1));
        stack.put("components", components);

        RenderResult coloured = render(ItemOptions.builder().itemId(ITEM).context(ItemContext.ofStack(stack)));
        Map<Integer, Long> plain = opaque(render(ItemOptions.builder().itemId(ITEM)));

        assertThat(coloured.substitutions(), is(empty()));
        assertThat(opaque(coloured), is(Map.of(GREEN, plain.values().stream().mapToLong(Long::longValue).sum())));
    }

    /**
     * Draws the fixture item as an inventory icon.
     *
     * @param options the options, the item id set
     * @return the render
     */
    private static @NotNull RenderResult render(@NotNull ItemOptions.Builder options) {
        return new ItemRenderer(stacked).render(options
            .type(ItemOptions.Type.GUI_ICON)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build());
    }

    /**
     * Counts each fully-opaque colour a render's first frame carries.
     *
     * @param rendered the render
     * @return the number of pixels of each opaque colour present
     */
    private static @NotNull Map<Integer, Long> opaque(@NotNull RenderResult rendered) {
        Map<Integer, Long> colours = new TreeMap<>();
        for (int pixel : RenderDigest.firstFramePixels(rendered.image()))
            if ((pixel >>> 24) == 0xFF) colours.merge(pixel, 1L, Long::sum);

        return colours;
    }

    /** One face of the fixture element, sampling the whole white texture at tintindex 0. */
    private static @NotNull String face(@NotNull String direction) {
        return "\"" + direction + "\":{\"uv\":[0,0,16,16],\"texture\":\"#0\",\"tintindex\":0}";
    }

    /**
     * Writes a 16x16 texture whose every texel is opaque white.
     *
     * @param png the file to write
     * @throws IOException if the file cannot be written
     */
    private static void png(@NotNull Path png) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, 0xFFFFFFFF);

        Files.createDirectories(png.getParent());
        ImageIO.write(image, "PNG", png.toFile());
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

}
