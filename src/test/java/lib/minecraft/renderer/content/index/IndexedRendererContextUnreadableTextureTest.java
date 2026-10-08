package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.exception.ImageException;
import dev.simplified.util.Possible;
import lib.minecraft.renderer.asset.pack.Flipbook;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.asset.pack.PackCapability;
import lib.minecraft.renderer.asset.pack.PackRoot;
import lib.minecraft.renderer.asset.pack.PalettedPermutationSource;
import lib.minecraft.renderer.asset.pack.ResourcePack;
import lib.minecraft.renderer.content.pack.PackContainer;
import lib.minecraft.renderer.content.pack.PackStack;
import lib.minecraft.renderer.content.pack.ResolvedModels;
import lib.minecraft.renderer.content.pack.TextureIndexer;
import lib.minecraft.renderer.content.pack.TextureSynthesizer;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.vanilla.id.PackId;
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
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage of a texture the pack stack serves and cannot read, through the production context: a
 * zero-byte PNG, a PNG signature over a truncated body, and a registered paletted permutation whose
 * input is one of them.
 * <p>
 * The index admits a file by its existence alone, so each is served, and each answers empty rather than
 * absent. It is decoded and reported once, it shadows a permutation registered under its own id, it
 * keeps its sidecar while playing no animation, and the substituting context draws the checkerboard for
 * it in words of its own. No shipped stack holds such a file, so nothing the parity store renders can see
 * any of this; these rows are the only witness.
 * <p>
 * The reporting sets are static and live as long as the process, so every id whose report is read is
 * read by one test alone.
 */
@DisplayName("A served texture that cannot be decoded answers empty")
class IndexedRendererContextUnreadableTextureTest {

    @TempDir
    static Path packRoot;

    private static final String GOOD = "minecraft:block/unreadable_test_good";
    private static final String ZERO_BYTE = "minecraft:block/unreadable_test_zero_byte";
    private static final String TRUNCATED = "minecraft:block/unreadable_test_truncated";
    private static final String ANIMATED = "minecraft:block/unreadable_test_animated";
    private static final String REPORTED_ONCE = "minecraft:block/unreadable_test_reported_once";
    private static final String SUBSTITUTED = "minecraft:block/unreadable_test_substituted";
    private static final String PALETTE_KEY = "minecraft:block/unreadable_test_palette_key";
    private static final String MATERIAL = "minecraft:block/unreadable_test_material";

    /** A permutation base whose file decodes, and whose permutation a broken file ships under. */
    private static final String SHADOWED_BASE = "minecraft:block/unreadable_test_shadowed";

    /** The one permutation suffix the fixture's source declares. */
    private static final String SUFFIX = "tint";

    /** The bytes of {@link #TRUNCATED} and every other truncated fixture: a real PNG cut in half. */
    private static byte[] truncated;

    private static IndexedRendererContext context;

    /**
     * Stages a pack holding a readable texture, the two kinds of broken file and the permutation inputs,
     * indexes it with the real {@link TextureIndexer}, and wraps it in a context whose synthesizer
     * permutes every listed base by {@link #SUFFIX}.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void buildFixture() throws IOException {
        Path blockDir = packRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(blockDir);

        byte[] good = png(16, 16);
        truncated = Arrays.copyOf(good, good.length / 2);
        write(blockDir, GOOD, good);
        write(blockDir, SHADOWED_BASE, good);
        write(blockDir, PALETTE_KEY, png(3, 1));
        write(blockDir, MATERIAL, png(3, 1));
        write(blockDir, ZERO_BYTE, new byte[0]);
        write(blockDir, TRUNCATED, truncated);
        write(blockDir, ANIMATED, truncated);
        write(blockDir, REPORTED_ONCE, truncated);
        write(blockDir, SUBSTITUTED, truncated);
        write(blockDir, SHADOWED_BASE + "_" + SUFFIX, truncated);
        Files.writeString(blockDir.resolve(name(ANIMATED) + ".png.mcmeta"), "{\"animation\":{\"frametime\":2}}");

        ResourcePack vanillaPack = new ResourcePack(
            PackId.VANILLA, new PackContainer.Directory(packRoot), MCMeta.EMPTY,
            Concurrent.newList(PackRoot.BASE), Concurrent.newUnmodifiableTreeSet("minecraft"),
            Concurrent.newUnmodifiableLinkedSet(PackCapability.VANILLA_CORE));
        PackStack stack = PackStack.of(Concurrent.newList(vanillaPack))
            .withTextureIndex(TextureIndexer.index(PackStack.of(Concurrent.newList(vanillaPack))));
        TextureSynthesizer synthesizer = new TextureSynthesizer(List.of(new PalettedPermutationSource(
            PALETTE_KEY, Map.of(SUFFIX, MATERIAL), List.of(GOOD, ZERO_BYTE, SHADOWED_BASE))));

        context = new IndexedRendererContext(
            stack, Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), synthesizer, Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());
    }

    @Test
    @DisplayName("the two broken fixtures fail the decode on its two arms")
    void theFixturesReachBothArms() {
        // Without this the rows below could pass over two files that fail the same way, leaving the
        // checked IIOException a corrupt body raises unexercised.
        ImageFactory factory = new ImageFactory();
        assertThrows(ImageException.class, () -> factory.fromByteArray(new byte[0]));
        assertThrows(IOException.class, () -> factory.fromByteArray(truncated));
    }

    @Test
    @DisplayName("a readable texture is present")
    void aReadableTextureIsPresent() {
        assertThat(context.resolveTexture(GOOD).isPresent(), is(true));
    }

    @Test
    @DisplayName("a zero-byte file is served and empty")
    void aZeroByteFileIsEmpty() {
        assertThat(context.resolveTexture(ZERO_BYTE).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a PNG whose body is cut short is served and empty")
    void aTruncatedPngIsEmpty() {
        assertThat(context.resolveTexture(TRUNCATED).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("an id nothing serves is absent")
    void anUnservedIdIsAbsent() {
        assertThat(context.resolveTexture("minecraft:block/unreadable_test_nothing").isAbsent(), is(true));
    }

    @Test
    @DisplayName("a broken file is decoded and reported once, then answered from memory")
    void aBrokenFileIsReportedOnce() {
        String first = errDuring(() -> assertThat(
            context.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY)));
        String again = errDuring(() -> {
            assertThat(context.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY));
            assertThat(context.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY));
        });

        assertThat(first, containsString("Unreadable texture '" + REPORTED_ONCE + "'"));
        assertThat("a lookup after the first neither decodes nor reports again", again, is(emptyString()));
    }

    @Test
    @DisplayName("a broken file keeps its sidecar and plays nothing")
    void aBrokenFileKeepsItsSidecar() {
        assertThat("the texture is served", context.resolveTexture(ANIMATED).getState(), is(Possible.State.EMPTY));
        assertThat("its sidecar is still read", context.findMeta(ANIMATED).isPresent(), is(true));
        assertThat("its animation section with it", context.findAnimation(ANIMATED).isPresent(), is(true));
        assertThat("but a strip with no pixels has no frames to play - a table that is there and plays nothing",
            context.findFlipbook(ANIMATED).getState(), is(Possible.State.EMPTY));
        assertThat("and every tick of it is empty",
            Flipbook.atTick(context.resolveTexture(ANIMATED), context.findFlipbook(ANIMATED), 5).getState(),
            is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a permutation over readable inputs is synthesized")
    void aPermutationOverReadableInputsIsPresent() {
        // The control for the two rows below: the synthesizer does produce this source's permutations.
        assertThat(context.resolveTexture(GOOD + "_" + SUFFIX).isPresent(), is(true));
    }

    @Test
    @DisplayName("a permutation over an unreadable input is registered and empty")
    void aPermutationOverABrokenInputIsEmpty() {
        assertThat(context.resolveTexture(ZERO_BYTE + "_" + SUFFIX).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a broken file shadows the permutation registered under its own id")
    void aBrokenFileShadowsAPermutation() {
        // The permutation's inputs all decode, so consulting the synthesizer would answer present; the
        // file the pack ships under the same id answers first, unreadable as it is.
        assertThat(context.resolveTexture(SHADOWED_BASE + "_" + SUFFIX).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("substituting, a broken file draws the checkerboard and is reported as unreadable")
    void theSubstituteSaysUnreadable() {
        RendererContext textures = context.withMissingTexture();

        String output = errDuring(() -> assertThat(
            textures.resolveTexture(SUBSTITUTED).orElseThrow(), is(sameInstance(MissingSprite.sprite()))));

        assertThat(output, containsString("Unreadable texture '" + SUBSTITUTED + "' - drawing the checkerboard"));
        assertThat(output, not(containsString("Missing texture '" + SUBSTITUTED + "'")));
    }

    /**
     * Encodes an opaque image whose texels vary, so its compressed body is long enough to cut short.
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
                image.setRGB(x, y, 0xFF000000 | (x * 37 + y * 101) * 0x010307);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    /**
     * Writes a texture's bytes at the path its id names.
     *
     * @param blockDir the pack's {@code textures/block} directory
     * @param textureId the namespaced texture id
     * @param bytes the file's bytes
     * @throws IOException if writing fails
     */
    private static void write(@NotNull Path blockDir, @NotNull String textureId, byte @NotNull [] bytes) throws IOException {
        Files.write(blockDir.resolve(name(textureId) + ".png"), bytes);
    }

    /**
     * The file name a {@code minecraft:block/<name>} texture id is stored under, without its extension.
     *
     * @param textureId the namespaced texture id
     * @return the bare file name
     */
    private static @NotNull String name(@NotNull String textureId) {
        return textureId.substring(textureId.lastIndexOf('/') + 1);
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
