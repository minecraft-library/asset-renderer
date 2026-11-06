package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.PalettedPermutationSource;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.call.result.Substitution;
import lib.minecraft.renderer.content.container.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.SpriteAtlas;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.vanilla.id.PackId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Coverage of the read the client's texture manager makes of a plain texture - one it loads by its file
 * rather than stitching it into an atlas, such as the book, a container sheet or an entity's texture -
 * through the production context over a real pack stack. The client decodes the image first, then asks
 * the sidecar for its texture section alone, so it loses such a texture only to an image that does not
 * decode, a sidecar that does not parse, or a texture section its codec refuses. An animation section it
 * never reads: a refused one, or one whose frame size does not divide the image, leaves the texture whole,
 * and the playback table plays nothing over it, so the whole file is drawn. A valid one plays over the
 * plain read as over the sprite read. Nor does it read a {@code gui} section, which only its GUI atlas
 * reads, so a refused one leaves the texture whole too.
 * <p>
 * The read takes the file the id names: no atlas sprite stitched from another file redirects it, and no
 * paletted permutation answers it. Each read memoises and reports its own answers, so a texture the sprite
 * read refuses is still whole to a plain read after it, and the other way round; a plain read reports an
 * image that does not decode before a sidecar that does not parse, as the client's loader fails on the
 * image first. The atlas sprite read keeps every refusal it makes.
 * <p>
 * No shipped stack holds a sidecar the two reads answer differently, so nothing the parity store renders
 * can see any of this; these rows are the only witness. The reporting sets are static and live as long as
 * the process, and each stack reports a texture once, so every id whose report is read is read by one test
 * alone.
 */
@DisplayName("A texture the client loads by its file loses it only to the texture loader's refusals")
class IndexedRendererContextPlainLoadTest {

    @TempDir
    static Path packRoot;

    /** A strip beside an animation section whose frametime the client's codec refuses. */
    private static final String REFUSED_FRAMETIME = "minecraft:entity/plain_load_test_refused_frametime";

    /** A strip beside an animation section whose frame list the client's codec refuses. */
    private static final String REFUSED_FRAME = "minecraft:entity/plain_load_test_refused_frame";

    /** A square whose declared frame width does not divide it. */
    private static final String RAGGED_WIDTH = "minecraft:entity/plain_load_test_ragged_width";

    /** A strip declaring no frame size, taller than wide by less than a whole frame. */
    private static final String RAGGED_UNDECLARED = "minecraft:entity/plain_load_test_ragged_undeclared";

    /** A texture beside a sidecar holding no characters but whitespace. */
    private static final String BLANK_SIDECAR = "minecraft:entity/plain_load_test_blank_sidecar";

    /** A texture beside a sidecar that is not JSON. */
    private static final String MALFORMED_SIDECAR = "minecraft:entity/plain_load_test_malformed_sidecar";

    /** A texture beside a texture section whose blur the client's codec refuses. */
    private static final String REFUSED_BLUR = "minecraft:entity/plain_load_test_refused_blur";

    /** A texture beside a texture section whose mipmap strategy the client's codec refuses. */
    private static final String REFUSED_MIPMAP = "minecraft:entity/plain_load_test_refused_mipmap";

    /** A texture beside a {@code gui} section the client's codec refuses, a section only its GUI atlas reads. */
    private static final String REFUSED_GUI = "minecraft:entity/plain_load_test_refused_gui";

    /** A file of zero bytes with no sidecar beside it. */
    private static final String ZERO_BYTE = "minecraft:entity/plain_load_test_zero_byte";

    /** A PNG cut in half with no sidecar beside it. */
    private static final String TRUNCATED = "minecraft:entity/plain_load_test_truncated";

    /** A PNG cut in half beside a sidecar that is not JSON, read plainly, whose report is read. */
    private static final String REPORTED_IMAGE = "minecraft:entity/plain_load_test_reported_image";

    /** {@link #REPORTED_IMAGE}'s twin, read as an atlas sprite, whose report is read. */
    private static final String REPORTED_IMAGE_TWIN = "minecraft:entity/plain_load_test_reported_image_twin";

    /** A texture beside a refused texture section, whose report is read. */
    private static final String REPORTED_ONCE = "minecraft:entity/plain_load_test_reported_once";

    /** A texture with no sidecar, read twice. */
    private static final String REMEMBERED = "minecraft:entity/plain_load_test_remembered";

    /** A strip beside a refused animation, read as a sprite first. */
    private static final String SPRITE_FIRST = "minecraft:entity/plain_load_test_sprite_first";

    /** A strip beside a refused animation, read plainly first. */
    private static final String PLAIN_FIRST = "minecraft:entity/plain_load_test_plain_first";

    /** A texture with no sidecar, read as a sprite first and then plainly. */
    private static final String SHARED_SPRITE_FIRST = "minecraft:entity/plain_load_test_shared_sprite_first";

    /** A texture with no sidecar, read plainly first and then as a sprite. */
    private static final String SHARED_PLAIN_FIRST = "minecraft:entity/plain_load_test_shared_plain_first";

    /** The file the items atlas stitches under the two redirected sprite ids. */
    private static final String REDIRECT_SOURCE = "minecraft:entity/plain_load_test_redirect_source";

    /** A sprite id the items atlas stitches from {@link #REDIRECT_SOURCE}, beside a file of its own. */
    private static final String REDIRECTED = "minecraft:entity/plain_load_test_redirected";

    /** A sprite id the items atlas stitches from {@link #REDIRECT_SOURCE}, with no file of its own. */
    private static final String REDIRECTED_UNSHIPPED = "minecraft:entity/plain_load_test_redirected_unshipped";

    /** The palette the fixture's permutation maps from. */
    private static final String PALETTE_KEY = "minecraft:entity/plain_load_test_palette_key";

    /** The palette the fixture's permutation maps to. */
    private static final String MATERIAL = "minecraft:entity/plain_load_test_material";

    /** The base the fixture's permutation permutes. */
    private static final String PERMUTATION_BASE = "minecraft:entity/plain_load_test_permutation_base";

    /** The one permutation suffix the fixture's source declares. */
    private static final String SUFFIX = "tint";

    /** A permutation no file backs, which only the synthesizer serves. */
    private static final String PERMUTED = PERMUTATION_BASE + "_" + SUFFIX;

    /** The second pack, whose id names it in a texture id's prefix. */
    private static final PackId EXTRA = new PackId("plain-load-extra");

    /** A strip beside a refused animation, reached through the second pack's id. */
    private static final String PACK_PREFIXED = "plain-load-extra:entity/plain_load_test_extra_refused";

    /** A strip of two frames that differ, beside a valid animation. */
    private static final String VALID = "minecraft:entity/plain_load_test_valid";

    /** The colours of the two halves every two-colour fixture holds, in strip order. */
    private static final int FIRST = 0xFF102030, SECOND = 0xFF405060;

    /** The colour of the redirect's source file and the colour of the redirected id's own file. */
    private static final int SOURCE_COLOUR = 0xFF7788AA, OWN_COLOUR = 0xFFAA8877;

    /** Every fixture's bytes, by id, so a read is compared texel for texel to the file it decodes. */
    private static final Map<String, byte[]> FILES = new HashMap<>();

    private static IndexedRendererContext context;

    /**
     * Stages a pack holding each kind of sidecar the two reads answer, an items atlas stitching two sprites
     * from a third file, and a second pack reached through its id; indexes both with the real
     * {@link TextureIndexer}; and wraps them in the production context with one permutation registered.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void buildFixture() throws IOException {
        Path vanillaRoot = packRoot.resolve("vanilla");
        Path entityDir = vanillaRoot.resolve("assets/minecraft/textures/entity");
        Files.createDirectories(entityDir);
        byte[] still = png(16, 16);
        byte[] truncated = Arrays.copyOf(still, still.length / 2);

        write(entityDir, REFUSED_FRAMETIME, png(16, 32), "{\"animation\":{\"frametime\":0}}");
        write(entityDir, REFUSED_FRAME, png(16, 32), "{\"animation\":{\"frames\":[0,\"x\"]}}");
        write(entityDir, RAGGED_WIDTH, png(32, 32), "{\"animation\":{\"width\":20}}");
        write(entityDir, RAGGED_UNDECLARED, png(16, 40), "{\"animation\":{}}");
        write(entityDir, BLANK_SIDECAR, still, "   ");
        write(entityDir, MALFORMED_SIDECAR, still, "{ \"animation\": ");
        write(entityDir, REFUSED_BLUR, still, "{\"texture\":{\"blur\":1}}");
        write(entityDir, REFUSED_MIPMAP, still, "{\"texture\":{\"mipmap_strategy\":\"weird\"}}");
        write(entityDir, REFUSED_GUI, still, "{\"gui\":{\"scaling\":{\"type\":\"weird\"}}}");
        write(entityDir, ZERO_BYTE, new byte[0], null);
        write(entityDir, TRUNCATED, truncated, null);
        write(entityDir, REPORTED_IMAGE, truncated, "{ \"animation\": ");
        write(entityDir, REPORTED_IMAGE_TWIN, truncated, "{ \"animation\": ");
        write(entityDir, REPORTED_ONCE, still, "{\"texture\":{\"blur\":1}}");
        write(entityDir, REMEMBERED, still, null);
        write(entityDir, SPRITE_FIRST, png(16, 32), "{\"animation\":{\"frametime\":0}}");
        write(entityDir, PLAIN_FIRST, png(16, 32), "{\"animation\":{\"frametime\":0}}");
        write(entityDir, SHARED_SPRITE_FIRST, still, null);
        write(entityDir, SHARED_PLAIN_FIRST, still, null);
        write(entityDir, REDIRECT_SOURCE, solid(16, 16, SOURCE_COLOUR), null);
        write(entityDir, REDIRECTED, solid(16, 16, OWN_COLOUR), null);
        write(entityDir, PALETTE_KEY, png(3, 1), null);
        write(entityDir, MATERIAL, png(3, 1), null);
        write(entityDir, PERMUTATION_BASE, still, null);
        write(entityDir, VALID, png(16, 32), "{\"animation\":{}}");

        Path atlases = vanillaRoot.resolve("assets/minecraft/atlases");
        Files.createDirectories(atlases);
        Files.writeString(atlases.resolve("items.json"), "{\"sources\":["
            + "{\"type\":\"single\",\"resource\":\"" + REDIRECT_SOURCE + "\",\"sprite\":\"" + REDIRECTED + "\"},"
            + "{\"type\":\"single\",\"resource\":\"" + REDIRECT_SOURCE + "\",\"sprite\":\"" + REDIRECTED_UNSHIPPED + "\"}]}");

        Path extraRoot = packRoot.resolve("extra");
        Path extraEntities = extraRoot.resolve("assets/minecraft/textures/entity");
        Files.createDirectories(extraEntities);
        write(extraEntities, PACK_PREFIXED, png(16, 32), "{\"animation\":{\"frametime\":0}}");

        PackStack bare = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot), pack(EXTRA, extraRoot)));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
        TextureSynthesizer synthesizer = new TextureSynthesizer(List.of(new PalettedPermutationSource(
            PALETTE_KEY, Map.of(SUFFIX, MATERIAL), List.of(PERMUTATION_BASE))));

        context = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), synthesizer, Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList(),
            SpriteAtlas.load(stack, SpriteAtlas.ITEMS), SpriteAtlas.load(stack, SpriteAtlas.BLOCKS));
    }

    @Test
    @DisplayName("an animation section the client's codec refuses leaves a plain texture whole, playing nothing over it")
    void aRefusedAnimationLeavesAPlainTextureWhole() {
        for (String id : List.of(REFUSED_FRAMETIME, REFUSED_FRAME))
            assertDrawnWhole(id);
    }

    @Test
    @DisplayName("a frame size that does not divide the image leaves a plain texture whole, playing nothing over it")
    void aFrameSizeThatDoesNotDivideLeavesAPlainTextureWhole() {
        for (String id : List.of(RAGGED_WIDTH, RAGGED_UNDECLARED))
            assertDrawnWhole(id);
    }

    @Test
    @DisplayName("a sidecar that does not parse loses a plain texture as it loses a sprite")
    void aSidecarThatDoesNotParseLosesAPlainTexture() {
        for (String id : List.of(BLANK_SIDECAR, MALFORMED_SIDECAR)) {
            assertThat(id + "'s plain read", context.resolvePlainTexture(id).getState(), is(Possible.State.EMPTY));
            assertThat(id + "'s sprite read", context.resolveTexture(id).getState(), is(Possible.State.EMPTY));
        }
    }

    @Test
    @DisplayName("a texture section the client's codec refuses loses a plain texture")
    void aRefusedTextureSectionLosesAPlainTexture() {
        for (String id : List.of(REFUSED_BLUR, REFUSED_MIPMAP))
            assertThat(id + "'s plain read", context.resolvePlainTexture(id).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a gui section the client's codec refuses leaves a plain texture whole, the texture loader never reading it")
    void aRefusedGuiSectionLeavesAPlainTextureWhole() {
        assertThat("the sidecar's gui section is refused",
            context.findMeta(REFUSED_GUI).orElseThrow().gui().getState(), is(Possible.State.EMPTY));
        assertWholeFile("the plain read", context.resolvePlainTexture(REFUSED_GUI), REFUSED_GUI);
    }

    @Test
    @DisplayName("an image that does not decode loses a plain texture")
    void anImageThatDoesNotDecodeLosesAPlainTexture() {
        for (String id : List.of(ZERO_BYTE, TRUNCATED))
            assertThat(id + "'s plain read", context.resolvePlainTexture(id).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a plain read reports an image that does not decode before a sidecar that does not parse, a sprite read the sidecar")
    void aPlainReadReportsTheImageBeforeTheSidecar() {
        String plain = errDuring(() -> assertThat(
            context.resolvePlainTexture(REPORTED_IMAGE).getState(), is(Possible.State.EMPTY)));
        String sprite = errDuring(() -> assertThat(
            context.resolveTexture(REPORTED_IMAGE_TWIN).getState(), is(Possible.State.EMPTY)));

        assertThat(plain, containsString("Unreadable texture '" + REPORTED_IMAGE + "' in pack 'vanilla'"));
        assertThat(plain, not(containsString("Unable to parse metadata from '" + REPORTED_IMAGE + "'")));
        assertThat(sprite, containsString("Unable to parse metadata from '" + REPORTED_IMAGE_TWIN + "' in pack 'vanilla'"));
        assertThat(sprite, not(containsString("Unreadable texture '" + REPORTED_IMAGE_TWIN + "'")));
    }

    @Test
    @DisplayName("a plain read reports a texture it cannot read once, and answers every read of it from memory")
    void eachReadReportsOnceAndRemembers() {
        String first = errDuring(() -> assertThat(
            context.resolvePlainTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY)));
        String again = errDuring(() -> assertThat(
            context.resolvePlainTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY)));

        assertThat(first, containsString("Unable to parse metadata from '" + REPORTED_ONCE + "' in pack 'vanilla'"));
        assertThat("a read after the first neither reads nor reports again", again, is(emptyString()));
        assertThat("two plain reads of a whole texture answer one buffer",
            context.resolvePlainTexture(REMEMBERED).orElseThrow(), is(sameInstance(context.resolvePlainTexture(REMEMBERED).orElseThrow())));
    }

    @Test
    @DisplayName("the two reads keep their own answers, whichever reads a texture first")
    void theTwoReadsKeepTheirOwnAnswers() {
        assertThat("the sprite read, first", context.resolveTexture(SPRITE_FIRST).getState(), is(Possible.State.EMPTY));
        assertWholeFile("the plain read after it", context.resolvePlainTexture(SPRITE_FIRST), SPRITE_FIRST);
        assertThat("the sprite read again", context.resolveTexture(SPRITE_FIRST).getState(), is(Possible.State.EMPTY));

        assertWholeFile("the plain read, first", context.resolvePlainTexture(PLAIN_FIRST), PLAIN_FIRST);
        assertThat("the sprite read after it", context.resolveTexture(PLAIN_FIRST).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a file both reads serve is decoded once, whichever reads it first")
    void aFileBothReadsServeIsDecodedOnce() {
        PixelBuffer sprite = context.resolveTexture(SHARED_SPRITE_FIRST).get();
        assertThat("the plain read after the sprite read", context.resolvePlainTexture(SHARED_SPRITE_FIRST).get(), sameInstance(sprite));

        PixelBuffer plain = context.resolvePlainTexture(SHARED_PLAIN_FIRST).get();
        assertThat("the sprite read after the plain read", context.resolveTexture(SHARED_PLAIN_FIRST).get(), sameInstance(plain));
    }

    @Test
    @DisplayName("a plain read takes the file the id names, never the one an atlas stitches under it")
    void aPlainReadIsTheFileTheIdNames() {
        assertWholeFile("the sprite read of the redirected id", context.resolveTexture(REDIRECTED), REDIRECT_SOURCE);
        assertWholeFile("the plain read of the redirected id", context.resolvePlainTexture(REDIRECTED), REDIRECTED);
        assertWholeFile("the sprite read of a redirected id with no file", context.resolveTexture(REDIRECTED_UNSHIPPED), REDIRECT_SOURCE);
        assertThat("the plain read of a redirected id with no file",
            context.resolvePlainTexture(REDIRECTED_UNSHIPPED).getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("no paletted permutation answers a plain read")
    void noPermutationAnswersAPlainRead() {
        assertThat("the sprite read", context.resolveTexture(PERMUTED).getState(), is(Possible.State.PRESENT));
        assertThat("the plain read", context.resolvePlainTexture(PERMUTED).getState(), is(Possible.State.ABSENT));
    }

    @Test
    @DisplayName("an id naming a pack rather than a namespace is read plainly through that pack")
    void aPackPrefixedIdIsReadPlainly() {
        assertThat("the plain read holds pixels", context.resolvePlainTexture(PACK_PREFIXED).isPresent(), is(true));
        assertThat("the plain read is the whole file",
            same(context.resolvePlainTexture(PACK_PREFIXED).get(), decoded(PACK_PREFIXED)), is(true));
        assertThat("the sprite read", context.resolveTexture(PACK_PREFIXED).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("an atlas sprite keeps every refusal the sprite loader makes")
    void anAtlasSpriteKeepsItsRefusals() {
        for (String id : List.of(REFUSED_FRAMETIME, REFUSED_FRAME, RAGGED_WIDTH, RAGGED_UNDECLARED,
            BLANK_SIDECAR, MALFORMED_SIDECAR, REFUSED_BLUR, REFUSED_MIPMAP))
            assertThat(id + "'s sprite read", context.resolveTexture(id).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("substituting, a plain texture that cannot be read is the checkerboard and a stand-in, and one it can read is its file")
    void theCheckerboardStandsInForAPlainTextureThatCannotBeRead() {
        SubstitutionCollector collector = new SubstitutionCollector();
        RendererContext substituting = context.collecting(collector).withMissingTexture();
        List<String> unreadable = List.of(BLANK_SIDECAR, MALFORMED_SIDECAR, REFUSED_BLUR, REFUSED_MIPMAP, ZERO_BYTE, TRUNCATED);

        for (String id : unreadable)
            assertThat(id + " is the checkerboard", substituting.resolvePlainTexture(id).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
        assertWholeFile("a refused animation's file", substituting.resolvePlainTexture(REFUSED_FRAMETIME), REFUSED_FRAMETIME);

        assertThat("each is recorded as a stand-in, and the readable file is not", collector.snapshot(),
            containsInAnyOrder(unreadable.stream().map(id -> Substitution.texture(id, Possible.State.EMPTY)).toArray()));
    }

    @Test
    @DisplayName("a valid animation plays over a plain texture as it plays over a sprite")
    void aValidAnimationPlaysOverAPlainTextureAsOverASprite() {
        Possible<PixelBuffer> plain = context.resolvePlainTexture(VALID);
        Possible<Flipbook> table = context.findFlipbook(VALID);
        assertWholeFile("the plain read", plain, VALID);
        assertThat("the playback table", table.isPresent(), is(true));

        for (int tick : List.of(0, 1)) {
            PixelBuffer frame = Flipbook.atTick(plain, table, tick).orElseThrow();
            assertThat("tick " + tick + "'s frame is a half of the strip", List.of(frame.width(), frame.height()), is(List.of(16, 16)));
            assertThat("tick " + tick + "'s frame colour", frame.getPixel(0, 0), is(tick == 0 ? FIRST : SECOND));
            assertThat("tick " + tick + "'s frame is the sprite read's",
                same(frame, Flipbook.atTick(context.resolveTexture(VALID), table, tick).orElseThrow()), is(true));
        }
    }

    /**
     * Asserts a plain read answers a texture's whole file while the sprite read refuses it, and that its
     * playback table plays nothing, so a frame at any tick is the whole file.
     *
     * @param id the texture id
     */
    private static void assertDrawnWhole(@NotNull String id) {
        assertWholeFile(id + "'s plain read", context.resolvePlainTexture(id), id);
        assertThat(id + "'s sprite read", context.resolveTexture(id).getState(), is(Possible.State.EMPTY));
        assertThat(id + "'s playback table", context.findFlipbook(id).getState(), is(Possible.State.EMPTY));
        for (int tick : List.of(0, 1)) {
            assertWholeFile(id + " at tick " + tick,
                Flipbook.atTick(context.resolvePlainTexture(id), context.findFlipbook(id), tick), id);
        }
    }

    /**
     * Asserts a read answers a fixture's whole file, texel for texel.
     *
     * @param label what is read, for the failure message
     * @param read what the read answered
     * @param id the fixture whose file it should be
     */
    private static void assertWholeFile(@NotNull String label, @NotNull Possible<PixelBuffer> read, @NotNull String id) {
        assertThat(label + " holds pixels", read.isPresent(), is(true));
        assertThat(label + " is the whole file", same(read.get(), decoded(id)), is(true));
    }

    /**
     * Decodes a fixture's file as written.
     *
     * @param id the fixture id
     * @return the file's pixels
     */
    private static @NotNull PixelBuffer decoded(@NotNull String id) {
        return ImageFactory.fromByteArray(FILES.get(id)).toPixelBuffer();
    }

    /**
     * Answers whether two buffers hold the same pixels at the same size.
     *
     * @param a one buffer
     * @param b the other
     * @return whether they are texel for texel equal
     */
    private static boolean same(@NotNull PixelBuffer a, @NotNull PixelBuffer b) {
        if (a.width() != b.width() || a.height() != b.height()) return false;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
        return true;
    }

    /**
     * Builds a directory pack with one base root, serving the {@code minecraft} namespace.
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
     * Encodes an image whose top half is {@link #FIRST} and whose bottom half is {@link #SECOND}.
     *
     * @param width the image width
     * @param height the image height
     * @return the PNG bytes
     * @throws IOException if encoding fails
     */
    private static byte @NotNull [] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, y < height / 2 ? FIRST : SECOND);
        return encode(image);
    }

    /**
     * Encodes an image of one colour.
     *
     * @param width the image width
     * @param height the image height
     * @param argb the colour
     * @return the PNG bytes
     * @throws IOException if encoding fails
     */
    private static byte @NotNull [] solid(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, argb);
        return encode(image);
    }

    /**
     * Encodes an image as a PNG.
     *
     * @param image the image
     * @return the PNG bytes
     * @throws IOException if encoding fails
     */
    private static byte @NotNull [] encode(@NotNull BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    /**
     * Writes a texture's bytes, and the sidecar beside it where one is given, at the paths its id names,
     * and keeps the bytes for the comparisons.
     *
     * @param entityDir the pack's {@code textures/entity} directory
     * @param textureId the texture id, its last path segment naming the file
     * @param bytes the image's bytes
     * @param sidecar the sidecar's text, which need not parse, or {@code null} for none
     * @throws IOException if writing fails
     */
    private static void write(@NotNull Path entityDir, @NotNull String textureId, byte @NotNull [] bytes,
                              @Nullable String sidecar) throws IOException {
        String name = textureId.substring(textureId.lastIndexOf('/') + 1);
        Files.write(entityDir.resolve(name + ".png"), bytes);
        if (sidecar != null) Files.writeString(entityDir.resolve(name + ".png.mcmeta"), sidecar);
        FILES.put(textureId, bytes);
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
