package lib.minecraft.renderer.content.pack;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.PalettedPermutationSource;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.asset.rule.CtmRule;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.content.container.PackLimits;
import lib.minecraft.renderer.content.rule.CtmParser;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The limits a pack's files are read under, each held at the read that would otherwise allocate what the
 * file names: an image's declared pixels, read from its header before it is decoded; the decoded pixels a
 * stack keeps; the frames a strip is cut into; the sprites paletted permutations add; and the tiles a
 * connected-texture range expands to. Every input here is a few bytes, read under limits lowered to
 * match, and each case pairs the refusal with the read at the limit that still goes through.
 */
@DisplayName("Pack files are read within the stack's limits")
class PackReadLimitsTest {

    private static final ResourceId FIRST = new ResourceId("minecraft", "block/first");
    private static final ResourceId SECOND = new ResourceId("minecraft", "block/second");

    @TempDir
    Path root;

    @Test
    @DisplayName("a header is refused where it is no PNG's or declares more pixels than the ceiling")
    void headerRefusals() throws IOException {
        byte[] png = png(4, 4);

        assertThat(PngHeader.refusal(png, 16), is(Optional.empty()));
        assertThat(PngHeader.refusal(png, 15).orElseThrow(), containsString("4 x 4 is more than the limit of 15 pixels"));
        assertThat(PngHeader.refusal(new byte[0], 16).orElseThrow(), is("PNG header missing"));
        assertThat(PngHeader.refusal(encode(4, 4, "bmp"), 16).orElseThrow(), is("bad PNG signature"));
    }

    @Test
    @DisplayName("a declared size is read unsigned, so one past the signed range is over every ceiling")
    void headerSizesAreUnsigned() {
        byte[] header = ByteBuffer.allocate(24)
            .putLong(0x89504E470D0A1A0AL).putInt(13).putInt(0x49484452)
            .putInt(0x80000000).putInt(0x80000000)
            .array();

        assertThat(PngHeader.refusal(header, PackLimits.DEFAULTS.imagePixels()).isPresent(), is(true));
    }

    @Test
    @DisplayName("a texture declaring more pixels than the limit is served unreadable by both reads, and reported")
    void oversizedTextureIsUnreadable() throws IOException {
        Files.createDirectories(textures());
        Files.write(textures().resolve("first.png"), png(8, 8));
        PackStack stack = stack(PackLimits.DEFAULTS.withImagePixels(63));

        String reported = stderrOf(() -> {
            assertThat(stack.pixels(FIRST).getState(), is(Possible.State.EMPTY));
            assertThat(stack.plainTexture(FIRST).getState(), is(Possible.State.EMPTY));
        });

        assertThat(reported, containsString("Unreadable texture 'minecraft:block/first' in pack 'vanilla' - 8 x 8 is more than the limit of 63 pixels"));
        assertThat("the same texture reads at the limit", stack(PackLimits.DEFAULTS.withImagePixels(64)).pixels(FIRST).isPresent(), is(true));
    }

    @Test
    @DisplayName("a texture whose bytes are another image format is served unreadable, as the client reads none")
    void textureOfAnotherFormatIsUnreadable() throws IOException {
        Files.createDirectories(textures());
        Files.write(textures().resolve("first.png"), encode(8, 8, "bmp"));
        PackStack stack = stack(PackLimits.DEFAULTS);

        String reported = stderrOf(() -> assertThat(stack.pixels(FIRST).getState(), is(Possible.State.EMPTY)));

        assertThat(reported, containsString("Unreadable texture 'minecraft:block/first' in pack 'vanilla' - bad PNG signature"));
    }

    @Test
    @DisplayName("a stack keeps decoded textures up to its limit and serves the rest decoded afresh, saying so once")
    void stackKeepsWithinItsLimit() throws IOException {
        Files.createDirectories(textures());
        Files.write(textures().resolve("first.png"), png(8, 8));
        Files.write(textures().resolve("second.png"), png(8, 8));
        PackStack stack = stack(PackLimits.DEFAULTS.withHeldPixels(100));

        PixelBuffer kept = stack.pixels(FIRST).orElseThrow();
        assertThat("a texture within the limit is decoded once", stack.pixels(FIRST).orElseThrow(), is(sameInstance(kept)));
        assertThat("the plain read shares the buffer rather than counting it twice",
            stack.plainTexture(FIRST).orElseThrow().pixels(), is(sameInstance(kept)));

        PixelBuffer[] past = new PixelBuffer[2];
        String reported = stderrOf(() -> {
            past[0] = stack.pixels(SECOND).orElseThrow();
            past[1] = stack.pixels(SECOND).orElseThrow();
            stack.plainTexture(SECOND).orElseThrow();
        });

        assertThat("a texture past the limit is decoded on every read", past[1], is(not(sameInstance(past[0]))));
        assertThat("and reads as the same pixels", past[1].getRow(7), is(past[0].getRow(7)));
        assertThat("the stack says so once",
            reported.split("keeps its limit of 100 decoded pixels", -1).length - 1, is(1));
        assertThat("a kept texture stays kept", stack.pixels(FIRST).orElseThrow(), is(sameInstance(kept)));
    }

    @Test
    @DisplayName("a strip cut into more frames than the limit is no sprite and plays nothing, and still reads plain")
    void stripOverTheFrameLimit() throws IOException {
        Files.createDirectories(textures());
        Files.write(textures().resolve("first.png"), png(1, 8));
        Files.writeString(textures().resolve("first.png.mcmeta"), "{\"animation\":{\"width\":1,\"height\":1}}");
        PackStack limited = stack(PackLimits.DEFAULTS.withFrames(7));

        String reported = stderrOf(() -> {
            assertThat(limited.pixels(FIRST).getState(), is(Possible.State.EMPTY));
            assertThat(limited.flipbook(FIRST).getState(), is(Possible.State.EMPTY));
        });

        assertThat(reported, containsString("holds more than the limit of 7 frames of size 1,1"));
        assertThat("the plain read cuts no frames", limited.plainTexture(FIRST).isPresent(), is(true));
        assertThat("the same strip plays at the limit",
            stack(PackLimits.DEFAULTS.withFrames(8)).flipbook(FIRST).orElseThrow().entries().size(), is(8));
    }

    @Test
    @DisplayName("a table is not built for a strip holding more frames than the ceiling it is resolved under")
    void flipbookCeiling() {
        PixelBuffer strip = PixelBuffer.ofStraight(new int[8], 1, 8);
        MCMeta.Animation animation = new MCMeta.Animation(1, false, Optional.of(1), Optional.of(1), Optional.empty());

        assertThat(Flipbook.of(strip, animation, 8).orElseThrow().entries().size(), is(8));
        assertThat(Flipbook.of(strip, animation, 7), is(Optional.empty()));
        assertThat("the two-argument form resolves under the default ceiling",
            Flipbook.of(strip, animation).orElseThrow().entries().size(), is(8));
    }

    @Test
    @DisplayName("a colormap is refused where it declares more pixels than the ceiling or is no PNG")
    void colormapCeiling() throws IOException {
        byte[] png = png(4, 4);

        assertThat(ColorMapLoader.decode(png, 16).length, is(4 * 4 * Integer.BYTES));
        ContentException oversized = assertThrows(ContentException.class, () -> ColorMapLoader.decode(png, 15));
        assertThat(oversized.getMessage(), containsString("more than the limit of 15 pixels"));
        assertThrows(ContentException.class, () -> ColorMapLoader.decode(encode(4, 4, "bmp"), 16));
    }

    @Test
    @DisplayName("a permutation source that would take the stack past its limit is skipped, the ones around it kept")
    void permutationSourcesWithinTheLimit() throws IOException {
        writeAtlas();
        PackStack stack = stack(PackLimits.DEFAULTS.withPermutations(5));

        AtomicReference<List<PalettedPermutationSource>> loaded = new AtomicReference<>();
        String reported = stderrOf(() -> loaded.set(PalettedPermutationLoader.load(stack)));

        assertThat(loaded.get().stream().map(PalettedPermutationSource::paletteKey).toList(), is(List.of("fits", "fits_after")));
        assertThat(reported, containsString("its 4 sprites take the stack past the limit of 5"));
        assertThat("every source loads at the limit",
            PalettedPermutationLoader.load(stack(PackLimits.DEFAULTS.withPermutations(9))).size(), is(3));
    }

    @Test
    @DisplayName("an atlas skips a permutation source that would take it past the stack's limit, the ones around it stitched")
    void atlasPermutationsWithinTheLimit() throws IOException {
        writeAtlas();
        PackStack stack = stack(PackLimits.DEFAULTS.withPermutations(5));

        AtomicReference<SpriteAtlas> atlas = new AtomicReference<>();
        String reported = stderrOf(() -> atlas.set(SpriteAtlas.load(stack, SpriteAtlas.ITEMS)));

        assertThat(atlas.get().contains("minecraft:a1_x"), is(true));
        assertThat(atlas.get().contains("minecraft:b1_x"), is(false));
        assertThat(atlas.get().contains("minecraft:c1_x"), is(true));
        assertThat(reported, containsString("its 4 sprites take the atlas past its limit"));
        assertThat("every source is stitched at the limit",
            SpriteAtlas.load(stack(PackLimits.DEFAULTS.withPermutations(9)), SpriteAtlas.ITEMS).contains("minecraft:b1_x"), is(true));
    }

    @Test
    @DisplayName("a synthesised sprite the stack has no room for is synthesised again on the next read")
    void synthesiserKeepsOnlyWhatItIsGivenRoomFor() {
        PixelBuffer palette = PixelBuffer.ofStraight(new int[]{0xFF00000A, 0xFF000014}, 2, 1);
        PixelBuffer material = PixelBuffer.ofStraight(new int[]{0x00FF0000, 0x0000FF00}, 2, 1);
        PixelBuffer base = PixelBuffer.ofStraight(new int[]{0xFF00000A, 0xFF000014}, 2, 1);
        Map<String, PixelBuffer> textures = Map.of("minecraft:key", palette, "minecraft:material", material, "minecraft:base", base);
        Function<String, Possible<PixelBuffer>> resolver = id -> Possible.of(textures.get(id));
        List<PalettedPermutationSource> sources = List.of(
            new PalettedPermutationSource("minecraft:key", Map.of("x", "minecraft:material"), List.of("minecraft:base")));
        ResourceId sprite = ResourceId.parse("minecraft:base_x");

        TextureSynthesizer refusing = new TextureSynthesizer(sources, buffer -> false);
        PixelBuffer first = refusing.synthesize(sprite, resolver).orElseThrow();
        PixelBuffer second = refusing.synthesize(sprite, resolver).orElseThrow();
        assertThat(second, is(not(sameInstance(first))));
        assertThat(second.getRow(0), is(first.getRow(0)));

        TextureSynthesizer keeping = new TextureSynthesizer(sources, buffer -> true);
        assertThat(keeping.synthesize(sprite, resolver).orElseThrow(), is(sameInstance(keeping.synthesize(sprite, resolver).orElseThrow())));
    }

    @Test
    @DisplayName("a tile range that would take a rule past the tile limit rejects the rule before it is unrolled")
    void tileRangeCeiling() {
        int limit = PackLimits.DEFAULTS.tiles();

        assertThat(ctm("0-" + (limit - 1)).orElseThrow().tiles().size(), is(limit));
        assertThat(ctm("0-" + limit), is(Optional.empty()));
        assertThat(ctm("0-" + (limit - 1) + " 7-7"), is(Optional.empty()));
        assertThat(ctm("0-" + Integer.MAX_VALUE), is(Optional.empty()));
    }

    /** Parses a random-method connected-texture rule over a tile list, which any tile count satisfies. */
    private static Optional<CtmRule> ctm(String tiles) {
        Properties props = new Properties();
        props.setProperty("method", "random");
        props.setProperty("matchTiles", "stone");
        props.setProperty("tiles", tiles);
        return CtmParser.parse(props, new ResourceId("minecraft", "optifine/ctm/a/stone.properties"), PackId.VANILLA, "optifine/ctm/a", "stone");
    }

    /** Three permutation sources in the items atlas: four sprites, four more, then one. */
    private void writeAtlas() throws IOException {
        Path atlases = this.root.resolve("assets/minecraft/atlases");
        Files.createDirectories(atlases);
        Files.writeString(atlases.resolve("items.json"), """
            {"sources":[
              {"type":"minecraft:paletted_permutations","palette_key":"fits",
               "permutations":{"x":"m","y":"m"},"textures":["a1","a2"]},
              {"type":"minecraft:paletted_permutations","palette_key":"over",
               "permutations":{"x":"m","y":"m"},"textures":["b1","b2"]},
              {"type":"minecraft:paletted_permutations","palette_key":"fits_after",
               "permutations":{"x":"m"},"textures":["c1"]}
            ]}""");
    }

    private Path textures() {
        return this.root.resolve("assets/minecraft/textures/block");
    }

    /** A vanilla-only, texture-indexed stack over the fixture root, read under the given limits. */
    private PackStack stack(PackLimits limits) {
        ResourcePack vanilla = new ResourcePack(PackId.VANILLA, new PackContainer.Directory(this.root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableSet("minecraft"),
            Concurrent.newUnmodifiableSet(PackCapability.VANILLA_CORE));
        PackStack bare = PackStack.of(Concurrent.newList(vanilla), limits);
        return bare.withTextureIndex(TextureIndexer.index(bare));
    }

    private static byte[] png(int width, int height) throws IOException {
        return encode(width, height, "png");
    }

    /** Encodes an opaque single-colour image in a format {@link ImageIO} writes. */
    private static byte[] encode(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, 0x336699);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IOException("No writer for " + format);
        return out.toByteArray();
    }

    /** Runs an action with {@code System.err} captured, since these reads report there. */
    private static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

}
