package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of a sprite an atlas source stitches under an id of its own: a model names the sprite, and
 * the context reads the texture the sprite was stitched from, as the client draws it.
 * <p>
 * A fixture pack stitches a texture into the items atlas and an animated one into the blocks atlas, each
 * under a sprite id no texture file carries - the shape ItemsAdder packs ship every texture in - and
 * one sprite id into both atlases from two different textures. A flat item model names the items-atlas
 * sprite. No vanilla atlas source names a sprite apart from its texture, so nothing the parity store
 * renders can see any of this; these rows are the only witness.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class where
 * nothing has extracted the client yet.
 */
@DisplayName("A sprite an atlas stitches under its own id reads the texture it was stitched from")
@ExtendWith(ClientAssetsExtension.class)
class AtlasSpriteResolutionTest {

    /** The fixture texture the items atlas stitches as {@link #ITEMS_SPRITE}. */
    private static final int RED = 0xFFFF0000;

    /** The first frame of the animated fixture texture the blocks atlas stitches as {@link #BLOCKS_SPRITE}. */
    private static final int GREEN = 0xFF00FF00;

    /** The second frame of the animated fixture texture. */
    private static final int BLUE = 0xFF0000FF;

    /** The texture the items atlas stitches as {@link #SHARED_SPRITE}. */
    private static final int YELLOW = 0xFFFFFF00;

    /** The texture the blocks atlas stitches as {@link #SHARED_SPRITE}. */
    private static final int CYAN = 0xFF00FFFF;

    /** The items-atlas sprite id, which no texture file carries. */
    private static final String ITEMS_SPRITE = "_fixture:items/red";

    /** The blocks-atlas sprite id, which no texture file carries. */
    private static final String BLOCKS_SPRITE = "_b_fixture:blocks/green";

    /** The sprite id both atlases stitch, each from a texture of its own. */
    private static final String SHARED_SPRITE = "_fixture:shared";

    /** The item whose flat model names {@link #ITEMS_SPRITE}. */
    private static final String ITEM = "fixture:red";

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
        Path pack = work.resolve("spritepack");
        Path assets = pack.resolve("assets/fixture");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"atlas sprite fixture\"}}");

        png(assets.resolve("textures/items/red.png"), RED);
        png(assets.resolve("textures/blocks/green.png"), GREEN, BLUE);
        write(assets.resolve("textures/blocks/green.png.mcmeta"), "{\"animation\":{\"frametime\":2}}");
        png(assets.resolve("textures/shared/items_side.png"), YELLOW);
        png(assets.resolve("textures/shared/blocks_side.png"), CYAN);

        write(pack.resolve("assets/minecraft/atlases/items.json"), "{\"sources\":["
            + single("fixture:items/red", ITEMS_SPRITE) + ","
            + single("fixture:shared/items_side", SHARED_SPRITE) + "]}");
        write(pack.resolve("assets/minecraft/atlases/blocks.json"), "{\"sources\":["
            + single("fixture:blocks/green", BLOCKS_SPRITE) + ","
            + single("fixture:shared/blocks_side", SHARED_SPRITE) + "]}");

        write(assets.resolve("models/item/red.json"),
            "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + ITEMS_SPRITE + "\"}}");
        write(assets.resolve("items/red.json"), "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"fixture:item/red\"}}");

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        stacked = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("an items-atlas sprite reads the texture its single source names")
    void itemsSpriteReadsItsTexture() {
        assertThat(stacked.resolveTexture(ITEMS_SPRITE).isPresent(), is(true));
        assertThat(stacked.resolveTexture(ITEMS_SPRITE).get().data()[0], is(RED));
    }

    @Test
    @DisplayName("a blocks-atlas sprite reads the texture its single source names, its sidecar included")
    void blocksSpriteReadsItsTextureAndSidecar() {
        assertThat(stacked.resolveTexture(BLOCKS_SPRITE).get().data()[0], is(GREEN));
        assertThat("the sidecar is the texture's", stacked.findMeta(BLOCKS_SPRITE).isPresent(), is(true));
        assertThat("so the sprite animates", stacked.findFlipbook(BLOCKS_SPRITE).isPresent(), is(true));
    }

    @Test
    @DisplayName("a sprite id both atlases stitch reads the items atlas's texture, which a model looks up first")
    void itemsAtlasWinsASharedSprite() {
        assertThat(stacked.resolveTexture(SHARED_SPRITE).get().data()[0], is(YELLOW));
    }

    @Test
    @DisplayName("an item whose model names a renamed sprite draws its texture and no stand-in")
    void itemDrawsTheRenamedSprite() {
        RenderResult rendered = new ItemRenderer(stacked).render(ItemOptions.builder()
            .itemId(ITEM)
            .type(ItemOptions.Type.GUI_2D)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(32).build())
            .build());

        assertThat(rendered.substitutions(), is(empty()));
        assertThat(distinctOpaque(rendered), is(Set.of(RED)));
    }

    /** A {@code single} atlas source stitching one texture under a sprite id of its own. */
    private static @NotNull String single(@NotNull String resource, @NotNull String sprite) {
        return "{\"type\":\"minecraft:single\",\"resource\":\"" + resource + "\",\"sprite\":\"" + sprite + "\"}";
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

    /**
     * Writes a 16-wide texture of one-colour 16x16 frames stacked top to bottom.
     *
     * @param png the file to write
     * @param frames each frame's one colour, top first
     * @throws IOException if the file cannot be written
     */
    private static void png(@NotNull Path png, int @NotNull ... frames) throws IOException {
        BufferedImage image = new BufferedImage(16, 16 * frames.length, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, frames[y / 16]);

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
