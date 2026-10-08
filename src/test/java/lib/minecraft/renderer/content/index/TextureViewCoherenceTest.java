package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.PixelBuffer;
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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * The contract the four texture lookups keep with one another, held over every context and every
 * wrapper. {@link RendererContext#resolveTexture}, {@link RendererContext#findMeta},
 * {@link RendererContext#findAnimation} and {@link RendererContext#findFlipbook} describe one texture,
 * so for every id:
 * <ul>
 *     <li>each metadata view is absent exactly when the texture is - a view is not there only for a
 *     texture that is not there;</li>
 *     <li>the animation is in the state the sidecar's animation section is in;</li>
 *     <li>a playback table plays only under an animation that is there, over a strip that decoded;</li>
 *     <li>a texture served with no pixels plays nothing, while keeping the sidecar it ships.</li>
 * </ul>
 * <p>
 * A wrong state is invisible to every render - a still frame is a still frame, whichever way it came to
 * be still - so these rows are the only witness. The production context is built over a pack holding an
 * animated texture, a static one, a zero-byte file, a truncated file beside an animated sidecar and
 * paletted permutations over readable and unreadable inputs, and over a second pack reached through its
 * pack id; the in-memory context over buffers, and over a source answering empty; and every wrapper over
 * each of them.
 * <p>
 * The reporting sets the substituting wrapper and the pack stack write to are static, so every id here
 * is one no other test names, and nothing asserts what was reported.
 */
@DisplayName("The four texture lookups describe one texture")
class TextureViewCoherenceTest {

    @TempDir
    static Path tmp;

    /** A strip of two frames beside a sidecar declaring an animation. */
    private static final String ANIMATED = "minecraft:block/coherence_animated";

    /** A texture with no sidecar beside it. */
    private static final String STATIC = "minecraft:block/coherence_static";

    /** A file of zero bytes with no sidecar beside it. */
    private static final String ZERO_BYTE = "minecraft:block/coherence_zero_byte";

    /** A PNG cut in half, beside a sidecar declaring an animation. */
    private static final String BROKEN_ANIMATED = "minecraft:block/coherence_broken_animated";

    /** The palette the fixture's permutations map from. */
    private static final String PALETTE_KEY = "minecraft:block/coherence_palette_key";

    /** The palette the fixture's permutations map to. */
    private static final String MATERIAL = "minecraft:block/coherence_material";

    /** A permutation base no pack ships. */
    private static final String UNSHIPPED_BASE = "minecraft:block/coherence_unshipped";

    /** The one permutation suffix the fixture's source declares. */
    private static final String SUFFIX = "tint";

    /** A permutation whose every input decodes. */
    private static final String PERMUTED = STATIC + "_" + SUFFIX;

    /** A permutation whose base is the zero-byte file. */
    private static final String PERMUTED_OVER_BROKEN = ZERO_BYTE + "_" + SUFFIX;

    /** A permutation whose base no pack ships. */
    private static final String PERMUTED_OVER_MISSING = UNSHIPPED_BASE + "_" + SUFFIX;

    /** The second pack, whose id names it in a texture id's prefix. */
    private static final PackId EXTRA = new PackId("coherence-extra");

    /** An animated texture reached through the second pack's id rather than through a namespace. */
    private static final String PACK_PREFIXED = "coherence-extra:block/coherence_extra_animated";

    /** A truncated file reached through the second pack's id. */
    private static final String PACK_PREFIXED_BROKEN = "coherence-extra:block/coherence_extra_broken";

    /** A path the second pack does not ship, asked for through its id. */
    private static final String PACK_PREFIXED_MISSING = "coherence-extra:block/coherence_nothing";

    /** A path no pack ships, in a live namespace. */
    private static final String UNSERVED = "minecraft:block/coherence_nothing";

    /** A prefix that is neither a namespace nor a pack id. */
    private static final String UNKNOWN_PREFIX = "coherence_unknown:block/coherence_nothing";

    /** The id the in-memory contexts hold a buffer for. */
    private static final String MEMORY_PRESENT = "minecraft:block/coherence_memory_present";

    /** The id the second in-memory context's source serves with no pixels. */
    private static final String MEMORY_EMPTY = "minecraft:block/coherence_memory_empty";

    /** An id only the substituting source serves. */
    private static final String SOURCE_ONLY = "minecraft:block/coherence_source_only";

    /** An id the substituting source serves with no pixels. */
    private static final String SOURCE_EMPTY = "minecraft:block/coherence_source_empty";

    /** The id the reserving wrapper answers with its own buffer. */
    private static final String RESERVED = "minecraft:block/coherence_reserved";

    /** The ids the hiding wrapper hides - one of each state some base serves. */
    private static final Set<String> HIDDEN = Set.of(ANIMATED, STATIC, BROKEN_ANIMATED, MEMORY_PRESENT, MEMORY_EMPTY);

    /** Every id each context is asked about. */
    private static final List<String> IDS = List.of(
        ANIMATED, STATIC, ZERO_BYTE, BROKEN_ANIMATED, PERMUTED, PERMUTED_OVER_BROKEN, PERMUTED_OVER_MISSING,
        PACK_PREFIXED, PACK_PREFIXED_BROKEN, PACK_PREFIXED_MISSING, UNSERVED, UNKNOWN_PREFIX,
        MEMORY_PRESENT, MEMORY_EMPTY, SOURCE_ONLY, SOURCE_EMPTY, RESERVED);

    /** The production context over the two fixture packs. */
    private static RendererContext production;

    /** The contexts every wrapper is built over, by name. */
    private static Map<String, RendererContext> bases;

    /**
     * Stages the two packs, indexes them with the real {@link TextureIndexer}, and builds the production
     * context over them beside the two in-memory contexts.
     *
     * @throws IOException if writing a fixture fails
     */
    @BeforeAll
    static void buildContexts() throws IOException {
        byte[] still = png(16, 16);
        byte[] truncated = Arrays.copyOf(still, still.length / 2);

        Path vanillaRoot = tmp.resolve("vanilla");
        Path vanillaBlocks = vanillaRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(vanillaBlocks);
        write(vanillaBlocks, ANIMATED, png(16, 32));
        animationSidecar(vanillaBlocks, ANIMATED);
        write(vanillaBlocks, STATIC, still);
        write(vanillaBlocks, ZERO_BYTE, new byte[0]);
        write(vanillaBlocks, BROKEN_ANIMATED, truncated);
        animationSidecar(vanillaBlocks, BROKEN_ANIMATED);
        write(vanillaBlocks, PALETTE_KEY, png(3, 1));
        write(vanillaBlocks, MATERIAL, png(3, 1));

        Path extraRoot = tmp.resolve("extra");
        Path extraBlocks = extraRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(extraBlocks);
        write(extraBlocks, PACK_PREFIXED, png(16, 32));
        animationSidecar(extraBlocks, PACK_PREFIXED);
        write(extraBlocks, PACK_PREFIXED_BROKEN, truncated);

        PackStack bare = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot), pack(EXTRA, extraRoot)));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
        TextureSynthesizer synthesizer = new TextureSynthesizer(List.of(new PalettedPermutationSource(
            PALETTE_KEY, Map.of(SUFFIX, MATERIAL), List.of(STATIC, ZERO_BYTE, UNSHIPPED_BASE))));

        production = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), synthesizer, Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList());

        PixelBuffer buffer = PixelBuffer.create(16, 16);
        bases = new LinkedHashMap<>();
        bases.put("production", production);
        bases.put("in-memory", RendererContext.builder().textures(Map.of(MEMORY_PRESENT, buffer)).build());
        bases.put("in-memory over a source answering empty", RendererContext.builder()
            .textures(id -> switch (id) {
                case MEMORY_PRESENT -> Possible.of(buffer);
                case MEMORY_EMPTY -> Possible.empty();
                default -> Possible.absent();
            })
            .build());
    }

    @Test
    @DisplayName("the production fixture reaches every state the rules speak of")
    void theFixtureReachesEveryState() {
        // Without this the rows below could hold over a fixture whose every id answered alike.
        assertStates(ANIMATED, Possible.State.PRESENT, Possible.State.PRESENT, Possible.State.PRESENT);
        assertStates(STATIC, Possible.State.PRESENT, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(ZERO_BYTE, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(BROKEN_ANIMATED, Possible.State.EMPTY, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(PERMUTED, Possible.State.PRESENT, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PERMUTED_OVER_BROKEN, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PERMUTED_OVER_MISSING, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED, Possible.State.PRESENT, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_BROKEN, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_MISSING, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);
        assertStates(UNSERVED, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);
        assertStates(UNKNOWN_PREFIX, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);
    }

    @TestFactory
    @DisplayName("every context and every wrapper over it keeps the four lookups coherent for every id")
    Stream<DynamicTest> everyContextKeepsTheLookupsCoherent() {
        return bases.entrySet().stream().flatMap(base -> wrappers(base.getValue()).entrySet().stream()
            .flatMap(context -> IDS.stream().map(id -> DynamicTest.dynamicTest(
                base.getKey() + ", " + context.getKey() + ": " + id,
                () -> assertCoherent(context.getValue(), id)))));
    }

    @Test
    @DisplayName("a hidden texture is absent from all four lookups")
    void aHiddenTextureIsAbsentEverywhere() {
        RendererContext hiding = production.hiding(ANIMATED);

        assertThat(hiding.resolveTexture(ANIMATED).isAbsent(), is(true));
        assertThat(hiding.findMeta(ANIMATED).isAbsent(), is(true));
        assertThat(hiding.findAnimation(ANIMATED).isAbsent(), is(true));
        assertThat(hiding.findFlipbook(ANIMATED).isAbsent(), is(true));
    }

    @Test
    @DisplayName("substituting, a texture nothing serves is the checkerboard with no sidecar - empty, never absent")
    void theCheckerboardHasAnEmptySidecar() {
        RendererContext substituting = production.withMissingTexture();

        assertThat(substituting.resolveTexture(UNSERVED).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
        assertThat(substituting.findMeta(UNSERVED).getState(), is(Possible.State.EMPTY));
        assertThat(substituting.findAnimation(UNSERVED).getState(), is(Possible.State.EMPTY));
        assertThat(substituting.findFlipbook(UNSERVED).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("substituting, an unreadable texture is the checkerboard, keeping its sidecar and playing nothing")
    void theCheckerboardNeverPlaysTheBrokenFilesTable() {
        RendererContext substituting = production.withMissingTexture();

        assertThat(substituting.resolveTexture(BROKEN_ANIMATED).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
        assertThat("the broken file's sidecar is still read", substituting.findAnimation(BROKEN_ANIMATED).isPresent(), is(true));
        assertThat("but no table is paired with the sprite",
            substituting.findFlipbook(BROKEN_ANIMATED).getState(), is(Possible.State.EMPTY));
    }

    /**
     * Asserts the four rules over one context's answers for one id.
     *
     * @param context the context asked
     * @param id the texture id asked about
     */
    private static void assertCoherent(@NotNull RendererContext context, @NotNull String id) {
        Possible<PixelBuffer> texture = context.resolveTexture(id);
        Possible<MCMeta> meta = context.findMeta(id);
        Possible<MCMeta.Animation> animation = context.findAnimation(id);
        Possible<Flipbook> flipbook = context.findFlipbook(id);

        assertThat("the sidecar is absent exactly when the texture is", meta.isAbsent(), is(texture.isAbsent()));
        assertThat("the animation is absent exactly when the texture is", animation.isAbsent(), is(texture.isAbsent()));
        assertThat("the playback table is absent exactly when the texture is", flipbook.isAbsent(), is(texture.isAbsent()));

        assertThat("the animation is the sidecar's own section, in its state", animation.getState(),
            is(meta.flatMap(document -> Possible.ofOptional(document.animation())).getState()));

        if (flipbook.isPresent()) {
            assertThat("a table plays under an animation that is there", animation.isPresent(), is(true));
            assertThat("a table plays over a strip that decoded", texture.isPresent(), is(true));
        }

        if (texture.getState() == Possible.State.EMPTY) {
            assertThat("a texture with no pixels plays nothing", flipbook.getState(), is(Possible.State.EMPTY));
            assertThat("and keeps its sidecar", meta.isAbsent(), is(false));
            assertThat("and the sidecar's animation section", animation.isAbsent(), is(false));
        }
    }

    /**
     * Asserts what the production context answers for one id on three of its lookups.
     *
     * @param id the texture id asked about
     * @param texture the state the texture lookup answers
     * @param meta the state the sidecar lookup answers
     * @param flipbook the state the playback-table lookup answers
     */
    private static void assertStates(@NotNull String id, @NotNull Possible.State texture,
                                     @NotNull Possible.State meta, @NotNull Possible.State flipbook) {
        assertThat(id + "'s texture", production.resolveTexture(id).getState(), is(texture));
        assertThat(id + "'s sidecar", production.findMeta(id).getState(), is(meta));
        assertThat(id + "'s playback table", production.findFlipbook(id).getState(), is(flipbook));
    }

    /**
     * Every wrapper over one context, by name, the context itself first.
     *
     * @param base the context wrapped
     * @return the context and its wrappers
     */
    private static @NotNull Map<String, RendererContext> wrappers(@NotNull RendererContext base) {
        PixelBuffer frame = PixelBuffer.create(16, 16);
        Map<String, RendererContext> contexts = new LinkedHashMap<>();
        contexts.put("unwrapped", base);
        contexts.put("withTextures", base.withTextures(id -> switch (id) {
            case ANIMATED, BROKEN_ANIMATED, SOURCE_ONLY -> Possible.of(frame);
            case SOURCE_EMPTY -> Possible.empty();
            default -> Possible.absent();
        }));
        contexts.put("withTexture", base.withTexture(RESERVED, frame));
        contexts.put("withMissingTexture", base.withMissingTexture());
        contexts.put("hiding", base.hiding(HIDDEN));
        contexts.put("hiding then withMissingTexture", base.hiding(HIDDEN).withMissingTexture());
        return contexts;
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
     * @param textureId the texture id, its last path segment naming the file
     * @param bytes the file's bytes
     * @throws IOException if writing fails
     */
    private static void write(@NotNull Path blockDir, @NotNull String textureId, byte @NotNull [] bytes) throws IOException {
        Files.write(blockDir.resolve(name(textureId) + ".png"), bytes);
    }

    /**
     * Writes a sidecar declaring an animation beside a texture.
     *
     * @param blockDir the pack's {@code textures/block} directory
     * @param textureId the texture id, its last path segment naming the file
     * @throws IOException if writing fails
     */
    private static void animationSidecar(@NotNull Path blockDir, @NotNull String textureId) throws IOException {
        Files.writeString(blockDir.resolve(name(textureId) + ".png.mcmeta"), "{\"animation\":{\"frametime\":2}}");
    }

    /**
     * The file name a texture id's last path segment names, without its extension.
     *
     * @param textureId the texture id
     * @return the bare file name
     */
    private static @NotNull String name(@NotNull String textureId) {
        return textureId.substring(textureId.lastIndexOf('/') + 1);
    }

}
