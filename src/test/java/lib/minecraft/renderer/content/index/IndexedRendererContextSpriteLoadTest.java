package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.pack.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.vanilla.id.PackId;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the two textures vanilla's sprite loader refuses beyond one whose image does not decode,
 * through the production context: one whose {@code .png.mcmeta} sidecar does not parse - blank, not JSON,
 * or carrying a value of the wrong type in a frametime, a frame entry or a gui scaling type - and one
 * whose animation's frame size does not divide its strip - a declared height or width the strip is no
 * multiple of, an undeclared frame the strip is no multiple of, and a strip shorter than its frame.
 * <p>
 * Vanilla draws its missing sprite for each, so each is served and answers empty, and the substituting
 * context draws the checkerboard for it in words of its own. A sidecar that does not parse fails nothing
 * beyond its own texture: the index builds around it, and the lookup reads it without opening the image.
 * Each is read and reported once. A strip its frame size does divide is unchanged, and one wider than tall
 * whose undeclared frame is a square of its height plays across, as vanilla plays it.
 * <p>
 * No shipped stack holds a texture of either kind - one would show the missing sprite in vanilla itself -
 * so nothing the parity store renders can see any of this; these rows are the only witness. The reporting
 * sets are static and live as long as the process, and each stack reports a texture once, so every id
 * whose report is read is read by one test alone.
 */
@DisplayName("A texture vanilla's sprite loader refuses is served and empty")
class IndexedRendererContextSpriteLoadTest {

    @TempDir
    static Path packRoot;

    private static final String ANIMATED = "minecraft:block/sprite_load_test_animated";
    private static final String WIDE = "minecraft:block/sprite_load_test_wide";
    private static final String BLANK_SIDECAR = "minecraft:block/sprite_load_test_blank_sidecar";
    private static final String MALFORMED_SIDECAR = "minecraft:block/sprite_load_test_malformed_sidecar";
    private static final String WRONG_TYPED_FRAMETIME = "minecraft:block/sprite_load_test_wrong_typed_frametime";
    private static final String WRONG_TYPED_FRAME = "minecraft:block/sprite_load_test_wrong_typed_frame";
    private static final String WRONG_TYPED_SCALING = "minecraft:block/sprite_load_test_wrong_typed_scaling";
    private static final String RAGGED_HEIGHT = "minecraft:block/sprite_load_test_ragged_height";
    private static final String RAGGED_WIDTH = "minecraft:block/sprite_load_test_ragged_width";
    private static final String RAGGED_UNDECLARED = "minecraft:block/sprite_load_test_ragged_undeclared";
    private static final String SHORT_STRIP = "minecraft:block/sprite_load_test_short_strip";

    /** A truncated PNG beside a sidecar that does not parse, whose image is never read. */
    private static final String SIDECAR_OVER_BROKEN_IMAGE = "minecraft:block/sprite_load_test_sidecar_over_broken_image";

    /** A sidecar that does not parse, whose report is read. */
    private static final String REPORTED_SIDECAR = "minecraft:block/sprite_load_test_reported_sidecar";

    /** A strip its frame size does not divide, whose report is read. */
    private static final String REPORTED_STRIP = "minecraft:block/sprite_load_test_reported_strip";

    /** A sidecar that does not parse, drawn by the substituting context. */
    private static final String SUBSTITUTED_SIDECAR = "minecraft:block/sprite_load_test_substituted_sidecar";

    /** A strip its frame size does not divide, drawn by the substituting context. */
    private static final String SUBSTITUTED_STRIP = "minecraft:block/sprite_load_test_substituted_strip";

    /** The second pack, whose id names it in a texture id's prefix. */
    private static final PackId EXTRA = new PackId("sprite-load-extra");

    /** A sidecar that does not parse, reached through the second pack's id rather than its namespace. */
    private static final String PACK_PREFIXED_SIDECAR = "sprite-load-extra:block/sprite_load_test_extra_sidecar";

    /** The colours of the two frames {@link #ANIMATED} and {@link #WIDE} each hold, in strip order. */
    private static final int FIRST = 0xFF102030, SECOND = 0xFF405060;

    /** The ids whose sidecar is there and does not parse - not JSON at all, or a value of the wrong type. */
    private static final List<String> BAD_SIDECARS = List.of(
        BLANK_SIDECAR, MALFORMED_SIDECAR, WRONG_TYPED_FRAMETIME, WRONG_TYPED_FRAME, WRONG_TYPED_SCALING);

    /** The ids whose animation's frame size does not divide the strip. */
    private static final List<String> BAD_STRIPS = List.of(RAGGED_HEIGHT, RAGGED_WIDTH, RAGGED_UNDECLARED, SHORT_STRIP);

    /** The indexed stack the context is built over, whose rows are read directly. */
    private static PackStack stack;

    private static IndexedRendererContext context;

    /**
     * Stages a pack holding each kind of texture the sprite loader refuses beside two it plays, and a
     * second pack reached through its id, indexes both with the real {@link TextureIndexer} - which is
     * the scan a sidecar that does not parse used to abort - and wraps them in the production context.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void buildFixture() throws IOException {
        Path vanillaRoot = packRoot.resolve("vanilla");
        Path blockDir = vanillaRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(blockDir);

        byte[] still = png(16, 16, false);
        write(blockDir, ANIMATED, png(16, 32, false), "{\"animation\":{\"frametime\":2}}");
        write(blockDir, WIDE, png(32, 16, true), "{\"animation\":{}}");
        write(blockDir, BLANK_SIDECAR, still, "   ");
        write(blockDir, MALFORMED_SIDECAR, still, "{ \"animation\": ");
        write(blockDir, WRONG_TYPED_FRAMETIME, png(16, 32, false), "{\"animation\":{\"frametime\":\"x\"}}");
        write(blockDir, WRONG_TYPED_FRAME, png(16, 32, false), "{\"animation\":{\"frames\":[0,\"x\"]}}");
        write(blockDir, WRONG_TYPED_SCALING, still, "{\"gui\":{\"scaling\":{\"type\":{}}}}");
        write(blockDir, RAGGED_HEIGHT, png(16, 40, false), "{\"animation\":{\"height\":16}}");
        write(blockDir, RAGGED_WIDTH, png(24, 16, true), "{\"animation\":{\"width\":16}}");
        write(blockDir, RAGGED_UNDECLARED, png(16, 40, false), "{\"animation\":{}}");
        write(blockDir, SHORT_STRIP, png(16, 8, false), "{\"animation\":{\"height\":16}}");
        write(blockDir, SIDECAR_OVER_BROKEN_IMAGE, Arrays.copyOf(still, still.length / 2), "{ \"animation\": ");
        write(blockDir, REPORTED_SIDECAR, still, "{ \"animation\": ");
        write(blockDir, REPORTED_STRIP, png(16, 40, false), "{\"animation\":{}}");
        write(blockDir, SUBSTITUTED_SIDECAR, still, "");
        write(blockDir, SUBSTITUTED_STRIP, png(16, 40, false), "{\"animation\":{}}");

        Path extraRoot = packRoot.resolve("extra");
        Path extraBlockDir = extraRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(extraBlockDir);
        write(extraBlockDir, PACK_PREFIXED_SIDECAR, still, "{ \"animation\": ");

        PackStack bare = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot), pack(EXTRA, extraRoot)));
        stack = bare.withTextureIndex(TextureIndexer.index(bare));

        context = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), TextureSynthesizer.EMPTY, Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
    }

    @Test
    @DisplayName("the index builds over sidecars that do not parse, capturing each as there and yielding nothing")
    void theIndexBuildsOverBrokenSidecars() {
        // The fixture stack was indexed in buildFixture, which a sidecar that does not parse once aborted.
        for (String id : BAD_SIDECARS) {
            assertThat(id + "'s row captures its sidecar as unparseable",
                stack.indexed(ResourceId.parse(id)).orElseThrow().meta().getState(), is(Possible.State.EMPTY));
        }

        assertThat("a sidecar that parses beside them is captured whole",
            stack.indexed(ResourceId.parse(RAGGED_HEIGHT)).orElseThrow().meta().isPresent(), is(true));
    }

    @Test
    @DisplayName("a texture whose sidecar does not parse is served and empty, and so are its sidecar, animation and table")
    void aSidecarThatDoesNotParseIsEmpty() {
        for (String id : BAD_SIDECARS) {
            assertThat(id + "'s texture", context.resolveTexture(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s sidecar", context.findMeta(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s animation", context.findAnimation(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s playback table", context.findFlipbook(id).getState(), is(Possible.State.EMPTY));
        }
    }

    @Test
    @DisplayName("a strip its frame size does not divide is served and empty, keeping its sidecar and playing nothing")
    void aStripItsFrameSizeDoesNotDivideIsEmpty() {
        for (String id : BAD_STRIPS) {
            assertThat(id + "'s texture", context.resolveTexture(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s sidecar is still read", context.findMeta(id).isPresent(), is(true));
            assertThat(id + "'s animation with it", context.findAnimation(id).isPresent(), is(true));
            assertThat(id + "'s playback table", context.findFlipbook(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s every tick is empty",
                Flipbook.atTick(context.resolveTexture(id), context.findFlipbook(id), 3).getState(),
                is(Possible.State.EMPTY));
        }
    }

    @Test
    @DisplayName("a pack-prefixed id whose sidecar does not parse answers empty rather than raising")
    void aPackPrefixedSidecarThatDoesNotParseIsEmpty() {
        assertThat(context.resolveTexture(PACK_PREFIXED_SIDECAR).getState(), is(Possible.State.EMPTY));
        assertThat(context.findMeta(PACK_PREFIXED_SIDECAR).getState(), is(Possible.State.EMPTY));
        assertThat(context.findFlipbook(PACK_PREFIXED_SIDECAR).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a strip its frame size divides plays as it always has")
    void aDividingStripIsUnchanged() {
        Flipbook table = context.findFlipbook(ANIMATED).orElseThrow();
        PixelBuffer strip = context.resolveTexture(ANIMATED).orElseThrow();

        assertThat(table.frameWidth(), equalTo(16));
        assertThat(table.frameHeight(), equalTo(16));
        assertThat(table.entries().size(), equalTo(2));
        assertThat("the first frame is the top of the strip",
            Flipbook.atTick(Possible.of(strip), Possible.of(table), 0).orElseThrow().getPixel(0, 0), equalTo(FIRST));
        assertThat("the second frame the bottom",
            Flipbook.atTick(Possible.of(strip), Possible.of(table), 2).orElseThrow().getPixel(0, 0), equalTo(SECOND));
    }

    @Test
    @DisplayName("a strip wider than tall with no declared frame plays its square frames left to right")
    void aWideStripPlaysAcross() {
        Flipbook table = context.findFlipbook(WIDE).orElseThrow();
        PixelBuffer strip = context.resolveTexture(WIDE).orElseThrow();

        assertThat(table.frameWidth(), equalTo(16));
        assertThat(table.frameHeight(), equalTo(16));
        assertThat(table.entries().size(), equalTo(2));
        assertThat("the first frame is the left of the strip",
            Flipbook.atTick(Possible.of(strip), Possible.of(table), 0).orElseThrow().getPixel(15, 15), equalTo(FIRST));
        assertThat("the second frame the right",
            Flipbook.atTick(Possible.of(strip), Possible.of(table), 1).orElseThrow().getPixel(0, 0), equalTo(SECOND));
    }

    @Test
    @DisplayName("a sidecar that does not parse loses its texture without the image being read")
    void theImageBehindABrokenSidecarIsNeverRead() {
        // The PNG is truncated, so reading it would report it as an image that does not decode; the
        // sidecar is what is reported instead.
        String output = errDuring(() -> assertThat(
            context.resolveTexture(SIDECAR_OVER_BROKEN_IMAGE).getState(), is(Possible.State.EMPTY)));

        assertThat(output, containsString("Unable to parse metadata from '" + SIDECAR_OVER_BROKEN_IMAGE + "'"));
        assertThat(output, not(containsString("Unreadable texture '" + SIDECAR_OVER_BROKEN_IMAGE + "'")));
    }

    @Test
    @DisplayName("each is read and reported once, in vanilla's words, then answered from memory")
    void eachIsReportedOnce() {
        String sidecar = errDuring(() -> context.resolveTexture(REPORTED_SIDECAR));
        String strip = errDuring(() -> context.resolveTexture(REPORTED_STRIP));
        String again = errDuring(() -> {
            assertThat(context.resolveTexture(REPORTED_SIDECAR).getState(), is(Possible.State.EMPTY));
            assertThat(context.resolveTexture(REPORTED_STRIP).getState(), is(Possible.State.EMPTY));
            assertThat(context.findFlipbook(REPORTED_STRIP).getState(), is(Possible.State.EMPTY));
        });

        assertThat(sidecar, containsString("Unable to parse metadata from '" + REPORTED_SIDECAR + "' in pack 'vanilla'"));
        assertThat(strip, containsString("Image '" + REPORTED_STRIP + "' in pack 'vanilla' size 16,40 is not multiple of frame size 16,16"));
        assertThat("a lookup after the first neither reads nor reports again", again, is(emptyString()));
    }

    @Test
    @DisplayName("substituting, each draws the checkerboard and is reported as unreadable")
    void theSubstituteSaysUnreadable() {
        RendererContext textures = context.withMissingTexture();

        for (String id : List.of(SUBSTITUTED_SIDECAR, SUBSTITUTED_STRIP)) {
            String output = errDuring(() -> {
                assertThat(textures.resolveTexture(id).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
                assertThat("and every tick of it is the checkerboard",
                    Flipbook.atTick(textures.resolveTexture(id), textures.findFlipbook(id), 3).orElseThrow(),
                    is(sameInstance(MissingSprite.sprite())));
            });

            assertThat(output, containsString("Unreadable texture '" + id + "' - drawing the checkerboard"));
            assertThat(output, not(containsString("Missing texture '" + id + "'")));
        }
    }

    /**
     * A directory pack with one base root, serving the {@code minecraft} namespace.
     *
     * @param id the pack id
     * @param root the pack's root directory
     * @return the pack
     */
    private static @NotNull ResourcePack pack(@NotNull PackId id, @NotNull Path root) {
        return new ResourcePack(
            id, new PackContainer.Directory(root), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
    }

    /**
     * Encodes an image of two frames: its first half in {@link #FIRST} and its second in {@link #SECOND},
     * split across its width or its height. Frame boundaries other than the half fall where they fall.
     *
     * @param width the image width
     * @param height the image height
     * @param acrossWidth whether the halves are the left and right rather than the top and bottom
     * @return the PNG bytes
     * @throws IOException if encoding fails
     */
    private static byte @NotNull [] png(int width, int height, boolean acrossWidth) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, (acrossWidth ? x < width / 2 : y < height / 2) ? FIRST : SECOND);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    /**
     * Writes a texture's bytes and the sidecar beside it at the paths its id names.
     *
     * @param blockDir the pack's {@code textures/block} directory
     * @param textureId the texture id, its last path segment naming the file
     * @param bytes the image's bytes
     * @param sidecar the sidecar's text, which need not parse
     * @throws IOException if writing fails
     */
    private static void write(@NotNull Path blockDir, @NotNull String textureId, byte @NotNull [] bytes, @NotNull String sidecar) throws IOException {
        String name = textureId.substring(textureId.lastIndexOf('/') + 1);
        Files.write(blockDir.resolve(name + ".png"), bytes);
        Files.writeString(blockDir.resolve(name + ".png.mcmeta"), sidecar);
    }

    /**
     * Runs a body with {@code System.err} captured, restoring the real stream afterwards.
     *
     * @param body the call whose diagnostic output is being read
     * @return everything the body wrote to {@code System.err}
     */
    private static @NotNull String errDuring(@NotNull Runnable body) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try {
            body.run();
        } finally {
            System.setErr(original);
        }

        return captured.toString(StandardCharsets.UTF_8);
    }

}
