package lib.minecraft.renderer;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.frame.ImageFrame;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.call.request.AnimationOptions;
import lib.minecraft.renderer.call.request.AtlasOptions;
import lib.minecraft.renderer.call.request.BlockOptions;
import lib.minecraft.renderer.call.result.AtlasResult;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage of the read a block entity's mesh texture takes, which its client renderer decides: a skull's
 * and the copper golem statue's renderers bind the texture file itself, a plain texture the client's
 * texture manager loads with its sidecar's texture section alone, where every other block-entity renderer
 * binds a sprite of a sheet atlas, which the sprite loader refuses for a refused or non-dividing animation.
 * <p>
 * Either read keeps playing a valid strip on a block's frames, a skull's as a chest's, because the block's
 * tables fold its block entity's flipbook in. A chest whose animation is refused draws its missing sprite,
 * as the client's chest sheet does; an atlas tile draws a skull's texture as a single render of the skull
 * draws it at rest - the strip's first frame for a valid animation, and the whole file for a refused one -
 * since the atlas's still view freezes both reads.
 * <p>
 * Loads the vanilla stack under one staged pack holding a valid strip and a refused animation for a skull
 * texture and for a chest texture, through {@link ClientAssetsExtension}, which abandons the class where no
 * client jar is cached. No stored stack ships either sidecar, so no parity artifact sees any of this.
 */
@ExtendWith(ClientAssetsExtension.class)
@DisplayName("A block entity's texture takes the read its client renderer binds")
class PlainBlockEntityTextureTest {

    /** The skull texture the staged pack ships as a two-frame strip beside a valid animation. */
    private static final String SKELETON = "minecraft:entity/skeleton/skeleton";

    /** The skull texture the staged pack ships as vanilla's bytes beside a refused animation. */
    private static final String WITHER_SKELETON = "minecraft:entity/skeleton/wither_skeleton";

    /** The chest texture the staged pack ships as a two-frame strip beside a valid animation. */
    private static final String CHEST = "minecraft:entity/chest/normal";

    /** The chest texture the staged pack ships as vanilla's bytes beside a refused animation. */
    private static final String ENDER_CHEST = "minecraft:entity/chest/ender";

    /** The colour of every strip's second frame. */
    private static final int LOWER = 0xFF11AA22;

    /** The animation section the client's codec refuses. */
    private static final String REFUSED = "{\"animation\":{\"frametime\":0}}";

    private static final int TILE = 64;

    @TempDir
    static Path work;

    private static RendererContext vanilla;

    private static RendererContext packed;

    /**
     * Stages the pack - two strips of vanilla's texture over a solid copy, two of vanilla's own files beside a
     * refused animation - and loads it over the vanilla stack.
     *
     * @throws IOException if writing the pack fails
     */
    @BeforeAll
    static void loadPack() throws IOException {
        vanilla = ClientAssetsExtension.context();

        Path pack = work.resolve("plain-block-entity");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":84,\"description\":\"plain block entity fixture\"}}");
        stageStrip(pack, SKELETON);
        stageStrip(pack, CHEST);
        stage(pack, WITHER_SKELETON, vanillaBytes(WITHER_SKELETON), REFUSED);
        stage(pack, ENDER_CHEST, vanillaBytes(ENDER_CHEST), REFUSED);

        ClientOptions options = ClientOptions.builder()
            .cacheRoot(work.resolve("cache").toFile())
            .texturePacks(Concurrent.adoptList(List.of(pack.toFile())))
            .build();
        packed = RendererContext.load(new ClientAssets(options, ClientAssetsExtension.vanilla()));
    }

    @Test
    @DisplayName("a skull plays a valid strip on a block's frames, the plain read keeping its flipbook")
    void aSkullPlaysItsStrip() {
        List<Long> frames = RenderDigest.frameCrcs(twoFrames(packed, "minecraft:skeleton_skull").image());

        assertThat("frames", frames.size(), is(2));
        assertThat("the first frame is the strip's upper frame, vanilla's texture",
            frames.getFirst(), is(RenderDigest.frameCrcs(twoFrames(vanilla, "minecraft:skeleton_skull").image()).getFirst()));
        assertThat("the second frame is the lower", frames.getLast(), is(not(frames.getFirst())));
    }

    @Test
    @DisplayName("a chest plays a valid strip on a block's frames, the sheet read keeping its flipbook")
    void aChestPlaysItsStrip() {
        List<Long> frames = RenderDigest.frameCrcs(twoFrames(packed, "minecraft:chest").image());

        assertThat("frames", frames.size(), is(2));
        assertThat("the two frames differ", frames.getLast(), is(not(frames.getFirst())));
    }

    @Test
    @DisplayName("a chest whose animation the client's codec refuses draws its missing sprite, as the client's chest sheet does")
    void aChestsRefusedAnimationLosesItsSheetSprite() {
        RenderResult drawn = still(packed, "minecraft:ender_chest");
        RenderResult reference = still(vanilla.withTexture(ENDER_CHEST, MissingSprite.sprite()), "minecraft:ender_chest");

        assertThat("the chest draws its missing sprite", RenderDigest.frameCrcs(drawn.image()),
            is(RenderDigest.frameCrcs(reference.image())));
        assertThat("and names it unreadable", drawn.substitutions(),
            contains(Substitution.texture(ENDER_CHEST, Possible.State.EMPTY)));
    }

    @Test
    @DisplayName("an atlas tile draws a skull's texture as a single render of the skull draws it at rest")
    void anAtlasTileDrawsASkullsTextureAsTheSkullDraws() {
        List<String> skulls = List.of("minecraft:skeleton_skull", "minecraft:wither_skeleton_skull");
        AtlasOptions options = AtlasOptions.builder()
            .filter(Optional.of(skulls::contains))
            .tileSize(TILE)
            .progressLogging(false)
            .build();
        RendererContext reserved = vanilla
            .withTexture(SKELETON, decode(vanillaBytes(SKELETON)))
            .withTexture(WITHER_SKELETON, decode(vanillaBytes(WITHER_SKELETON)));

        AtlasResult drawn = new AtlasRenderer(packed).render(options);
        AtlasResult reference = new AtlasRenderer(reserved).render(options);

        for (String skull : skulls) {
            assertThat(skull + "'s tile", tilePixels(drawn, skull), is(tilePixels(reference, skull)));
            assertThat(skull + "'s row names no stand-in", tile(drawn, skull).substitutions(), is(empty()));
        }
    }

    /**
     * Renders a block's two frames, one tick apart.
     *
     * @param context the context the block is read through
     * @param blockId the block
     * @return the render
     */
    private static @NotNull RenderResult twoFrames(@NotNull RendererContext context, @NotNull String blockId) {
        return new BlockRenderer(context).render(BlockOptions.builder()
            .blockId(blockId)
            .animation(AnimationOptions.builder().frameCount(2).ticksPerFrame(1).build())
            .build());
    }

    /**
     * Renders a block's one still frame.
     *
     * @param context the context the block is read through
     * @param blockId the block
     * @return the render
     */
    private static @NotNull RenderResult still(@NotNull RendererContext context, @NotNull String blockId) {
        return new BlockRenderer(context).render(BlockOptions.builder().blockId(blockId).build());
    }

    /**
     * Stages vanilla's texture over a solid copy of it below, beside a valid animation of two whole frames,
     * the upper vanilla's.
     *
     * @param pack the pack root
     * @param textureId the texture id
     * @throws IOException if reading or writing fails
     */
    private static void stageStrip(@NotNull Path pack, @NotNull String textureId) throws IOException {
        BufferedImage upper = ImageIO.read(new ByteArrayInputStream(vanillaBytes(textureId)));
        int width = upper.getWidth();
        int height = upper.getHeight();
        BufferedImage strip = new BufferedImage(width, height * 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                strip.setRGB(x, y, upper.getRGB(x, y));
                strip.setRGB(x, y + height, LOWER);
            }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(strip, "PNG", out);
        stage(pack, textureId, out.toByteArray(), "{\"animation\":{\"height\":" + height + "}}");
    }

    /**
     * Writes a texture and its sidecar at the paths its id names.
     *
     * @param pack the pack root
     * @param textureId the texture id
     * @param png the image's bytes
     * @param sidecar the sidecar's text
     * @throws IOException if writing fails
     */
    private static void stage(@NotNull Path pack, @NotNull String textureId, byte @NotNull [] png,
                              @NotNull String sidecar) throws IOException {
        Path file = pack.resolve("assets/minecraft/textures/" + textureId.substring("minecraft:".length()) + ".png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        Files.writeString(file.resolveSibling(file.getFileName() + ".mcmeta"), sidecar);
    }

    /**
     * Reads vanilla's file of a texture.
     *
     * @param textureId the texture id
     * @return the file's bytes
     */
    private static byte @NotNull [] vanillaBytes(@NotNull String textureId) {
        return ClientAssetsExtension.vanilla()
            .bytes("assets/minecraft/textures/" + textureId.substring("minecraft:".length()) + ".png")
            .orElseThrow();
    }

    /**
     * Decodes a texture's bytes as the pack stack decodes them.
     *
     * @param png the bytes
     * @return the pixels
     */
    private static @NotNull PixelBuffer decode(byte @NotNull [] png) {
        return ImageFactory.fromByteArray(png).toPixelBuffer();
    }

    /**
     * Finds one subject's row in a composed sheet's sidecar.
     *
     * @param atlas the composed sheet and its sidecar
     * @param id the subject whose row is read
     * @return the subject's row
     */
    private static @NotNull AtlasResult.Tile tile(@NotNull AtlasResult atlas, @NotNull String id) {
        return atlas.sidecar().tiles().stream()
            .filter(row -> row.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError(id + " has no tile"));
    }

    /**
     * Reads one subject's tile out of a composed sheet.
     *
     * @param atlas the composed sheet and its sidecar
     * @param id the subject whose tile is read
     * @return the tile's ARGB pixels, row by row
     */
    private static @NotNull List<Integer> tilePixels(@NotNull AtlasResult atlas, @NotNull String id) {
        AtlasResult.Tile tile = tile(atlas, id);
        ImageData image = atlas.image();
        ImageFrame sheet = image.getFrames().getFirst();
        PixelBuffer pixels = sheet.pixels();
        List<Integer> out = new ArrayList<>(tile.width() * tile.height());
        for (int y = 0; y < tile.height(); y++)
            for (int x = 0; x < tile.width(); x++)
                out.add(pixels.getPixel(tile.x() + x, tile.y() + y));
        return out;
    }

}
