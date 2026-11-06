package lib.minecraft.renderer.content.index;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.exception.ImageException;
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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The contract the five texture lookups keep with one another, held over every context and every
 * wrapper. {@link RendererContext#resolveTexture}, {@link RendererContext#resolvePlainTexture},
 * {@link RendererContext#findMeta}, {@link RendererContext#findAnimation} and
 * {@link RendererContext#findFlipbook} describe one texture, so for every id:
 * <ul>
 *     <li>each metadata view is absent exactly when the texture is - a view is not there only for a
 *     texture that is not there;</li>
 *     <li>the animation is in the state the sidecar's animation section is in;</li>
 *     <li>a playback table plays only under an animation that is there, over a strip that decoded;</li>
 *     <li>a texture served with no pixels plays nothing, while keeping the sidecar it ships - which is
 *     itself empty where the sidecar is what does not parse;</li>
 *     <li>the plain read is absent exactly where the texture lookup is, but for an id only a paletted
 *     permutation serves, which no file backs, and an id an items or blocks atlas stitches from another
 *     file, which the texture lookup follows and the plain read does not; and where both answer a
 *     file's pixels and no atlas redirects the id, the pixels are one file's.</li>
 * </ul>
 * No relation sees a plain read that refuses what the client never reads, a sprite read refusing the
 * same file, so the plain read is also held to the state the production fixture puts it in, through
 * every wrapper, the fixture's atlases stitching no sprite from another file.
 * <p>
 * A wrong state is invisible to every render - a still frame is a still frame, whichever way it came to
 * be still - so these rows are the only witness. The production context is built over a pack holding an
 * animated texture, a static one, a zero-byte file, a truncated file beside an animated sidecar,
 * paletted permutations over readable and unreadable inputs, a truncated file shipped under the id of a
 * permutation whose inputs decode, a blank and a malformed sidecar, a sidecar whose animation section
 * the client's codec refuses and one carrying no animation section, strips their frame size does not
 * divide on either axis and one wider than tall that it does, and over a second pack reached through its
 * pack id; the in-memory context over buffers, and over a source answering empty; and every wrapper over
 * each of them.
 * <p>
 * A file the index serves and the decode refuses is served and empty, never absent. The zero-byte and
 * the truncated fixtures fail the decode on its two arms, such a file is decoded and reported once, it
 * plays nothing at any tick while keeping its sidecar, and it shadows a permutation registered under its
 * own id.
 * <p>
 * The reporting sets the substituting wrapper and the pack stack log through are static, so every id
 * here is one no other test names. What was logged is asserted twice, each over ids only that test
 * reads: of a broken file, reported once, and of a measuring context, which logs nothing. What a
 * substituting wrapper records lands in the collector of the context it wraps: every wrapper answers
 * that collector in either layering order and reports through to it, and both are asserted, the
 * collector being the context's own. A measuring context records nothing.
 */
@DisplayName("The five texture lookups describe one texture")
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

    /** A texture that decodes, beside a sidecar holding no characters but whitespace. */
    private static final String BLANK_SIDECAR = "minecraft:block/coherence_blank_sidecar";

    /** A texture that decodes, beside a sidecar that is not JSON. */
    private static final String MALFORMED_SIDECAR = "minecraft:block/coherence_malformed_sidecar";

    /** A strip that decodes, beside a sidecar whose animation section the client's codec refuses. */
    private static final String REFUSED_ANIMATION = "minecraft:block/coherence_refused_animation";

    /** A texture that decodes, beside a sidecar that parses and carries no animation section. */
    private static final String SECTIONLESS_SIDECAR = "minecraft:block/coherence_sectionless_sidecar";

    /** A strip whose declared frame height does not divide its height. */
    private static final String RAGGED_HEIGHT = "minecraft:block/coherence_ragged_height";

    /** A strip whose declared frame width does not divide its width. */
    private static final String RAGGED_WIDTH = "minecraft:block/coherence_ragged_width";

    /** A strip declaring no frame size, taller than wide by less than a whole frame. */
    private static final String RAGGED_UNDECLARED = "minecraft:block/coherence_ragged_undeclared";

    /** A strip shorter than the frame its sidecar declares. */
    private static final String SHORT_STRIP = "minecraft:block/coherence_short_strip";

    /** A strip declaring no frame size, wider than tall by a whole number of square frames. */
    private static final String WIDE = "minecraft:block/coherence_wide";

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

    /** A permutation base whose file decodes. */
    private static final String SHADOWED_BASE = "minecraft:block/coherence_shadowed";

    /** A permutation whose inputs all decode, under whose id the pack ships a truncated file. */
    private static final String SHADOWED = SHADOWED_BASE + "_" + SUFFIX;

    /** A truncated file whose report is read, by one test alone. */
    private static final String REPORTED_ONCE = "minecraft:block/coherence_reported_once";

    /** The second pack, whose id names it in a texture id's prefix. */
    private static final PackId EXTRA = new PackId("coherence-extra");

    /** An animated texture reached through the second pack's id rather than through a namespace. */
    private static final String PACK_PREFIXED = "coherence-extra:block/coherence_extra_animated";

    /** A truncated file reached through the second pack's id. */
    private static final String PACK_PREFIXED_BROKEN = "coherence-extra:block/coherence_extra_broken";

    /** A texture beside a sidecar that is not JSON, reached through the second pack's id. */
    private static final String PACK_PREFIXED_MALFORMED = "coherence-extra:block/coherence_extra_malformed";

    /** A strip its frame size does not divide, reached through the second pack's id. */
    private static final String PACK_PREFIXED_RAGGED = "coherence-extra:block/coherence_extra_ragged";

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

    /** An id no pack ships, read through a measuring context alone. */
    private static final String MEASURED_MISSING = "minecraft:block/coherence_measured_missing";

    /** An id served with no pixels, read through a measuring context alone. */
    private static final String MEASURED_UNREADABLE = "minecraft:block/coherence_measured_unreadable";

    /** The ids the hiding wrapper hides - one of each state some base serves. */
    private static final Set<String> HIDDEN = Set.of(
        ANIMATED, STATIC, BROKEN_ANIMATED, MALFORMED_SIDECAR, RAGGED_HEIGHT, MEMORY_PRESENT, MEMORY_EMPTY);

    /** Every id each context is asked about. */
    private static final List<String> IDS = List.of(
        ANIMATED, STATIC, ZERO_BYTE, BROKEN_ANIMATED, BLANK_SIDECAR, MALFORMED_SIDECAR, REFUSED_ANIMATION,
        SECTIONLESS_SIDECAR, RAGGED_HEIGHT, RAGGED_WIDTH, RAGGED_UNDECLARED, SHORT_STRIP, WIDE,
        PERMUTED, PERMUTED_OVER_BROKEN, PERMUTED_OVER_MISSING,
        PACK_PREFIXED, PACK_PREFIXED_BROKEN, PACK_PREFIXED_MALFORMED, PACK_PREFIXED_RAGGED, PACK_PREFIXED_MISSING,
        UNSERVED, UNKNOWN_PREFIX, MEMORY_PRESENT, MEMORY_EMPTY, SOURCE_ONLY, SOURCE_EMPTY, RESERVED);

    /** The ids only a paletted permutation serves, which no file backs, so no plain read answers them. */
    private static final Set<String> PERMUTATION_ONLY = Set.of(PERMUTED, PERMUTED_OVER_BROKEN, PERMUTED_OVER_MISSING);

    /** The ids the substituting source serves with pixels, on both reads. */
    private static final Set<String> SOURCE_SERVED = Set.of(ANIMATED, BROKEN_ANIMATED, MALFORMED_SIDECAR, RAGGED_HEIGHT, SOURCE_ONLY);

    /**
     * The state the production context's plain read answers each id in: whole where only an animation
     * section is refused or a frame size does not divide, empty where the image does not decode or the
     * sidecar does not parse, and absent where no file is shipped - a permutation's id among them.
     */
    private static final Map<String, Possible.State> PLAIN_STATES = Map.ofEntries(
        Map.entry(ANIMATED, Possible.State.PRESENT),
        Map.entry(STATIC, Possible.State.PRESENT),
        Map.entry(REFUSED_ANIMATION, Possible.State.PRESENT),
        Map.entry(SECTIONLESS_SIDECAR, Possible.State.PRESENT),
        Map.entry(RAGGED_HEIGHT, Possible.State.PRESENT),
        Map.entry(RAGGED_WIDTH, Possible.State.PRESENT),
        Map.entry(RAGGED_UNDECLARED, Possible.State.PRESENT),
        Map.entry(SHORT_STRIP, Possible.State.PRESENT),
        Map.entry(WIDE, Possible.State.PRESENT),
        Map.entry(PACK_PREFIXED, Possible.State.PRESENT),
        Map.entry(PACK_PREFIXED_RAGGED, Possible.State.PRESENT),
        Map.entry(ZERO_BYTE, Possible.State.EMPTY),
        Map.entry(BROKEN_ANIMATED, Possible.State.EMPTY),
        Map.entry(BLANK_SIDECAR, Possible.State.EMPTY),
        Map.entry(MALFORMED_SIDECAR, Possible.State.EMPTY),
        Map.entry(PACK_PREFIXED_BROKEN, Possible.State.EMPTY),
        Map.entry(PACK_PREFIXED_MALFORMED, Possible.State.EMPTY),
        Map.entry(PERMUTED, Possible.State.ABSENT),
        Map.entry(PERMUTED_OVER_BROKEN, Possible.State.ABSENT),
        Map.entry(PERMUTED_OVER_MISSING, Possible.State.ABSENT),
        Map.entry(PACK_PREFIXED_MISSING, Possible.State.ABSENT),
        Map.entry(UNSERVED, Possible.State.ABSENT),
        Map.entry(UNKNOWN_PREFIX, Possible.State.ABSENT),
        Map.entry(MEMORY_PRESENT, Possible.State.ABSENT),
        Map.entry(MEMORY_EMPTY, Possible.State.ABSENT),
        Map.entry(SOURCE_ONLY, Possible.State.ABSENT),
        Map.entry(SOURCE_EMPTY, Possible.State.ABSENT),
        Map.entry(RESERVED, Possible.State.ABSENT));

    /** The bytes of every truncated fixture: a real PNG cut in half. */
    private static byte[] truncated;

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
        truncated = Arrays.copyOf(still, still.length / 2);

        Path vanillaRoot = tmp.resolve("vanilla");
        Path vanillaBlocks = vanillaRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(vanillaBlocks);
        write(vanillaBlocks, ANIMATED, png(16, 32));
        animationSidecar(vanillaBlocks, ANIMATED);
        write(vanillaBlocks, STATIC, still);
        write(vanillaBlocks, ZERO_BYTE, new byte[0]);
        write(vanillaBlocks, BROKEN_ANIMATED, truncated);
        animationSidecar(vanillaBlocks, BROKEN_ANIMATED);
        write(vanillaBlocks, BLANK_SIDECAR, still);
        sidecar(vanillaBlocks, BLANK_SIDECAR, "  \n");
        write(vanillaBlocks, MALFORMED_SIDECAR, still);
        sidecar(vanillaBlocks, MALFORMED_SIDECAR, "{ \"animation\": ");
        write(vanillaBlocks, REFUSED_ANIMATION, png(16, 32));
        sidecar(vanillaBlocks, REFUSED_ANIMATION, "{\"animation\":{\"frametime\":0}}");
        write(vanillaBlocks, SECTIONLESS_SIDECAR, still);
        sidecar(vanillaBlocks, SECTIONLESS_SIDECAR, "{\"villager\":{\"hat\":\"full\"}}");
        write(vanillaBlocks, RAGGED_HEIGHT, png(16, 40));
        sidecar(vanillaBlocks, RAGGED_HEIGHT, "{\"animation\":{\"height\":16}}");
        write(vanillaBlocks, RAGGED_WIDTH, png(24, 16));
        sidecar(vanillaBlocks, RAGGED_WIDTH, "{\"animation\":{\"width\":16}}");
        write(vanillaBlocks, RAGGED_UNDECLARED, png(16, 40));
        animationSidecar(vanillaBlocks, RAGGED_UNDECLARED);
        write(vanillaBlocks, SHORT_STRIP, png(16, 8));
        sidecar(vanillaBlocks, SHORT_STRIP, "{\"animation\":{\"height\":16}}");
        write(vanillaBlocks, WIDE, png(32, 16));
        animationSidecar(vanillaBlocks, WIDE);
        write(vanillaBlocks, PALETTE_KEY, png(3, 1));
        write(vanillaBlocks, MATERIAL, png(3, 1));
        write(vanillaBlocks, SHADOWED_BASE, still);
        write(vanillaBlocks, SHADOWED, truncated);
        write(vanillaBlocks, REPORTED_ONCE, truncated);

        Path extraRoot = tmp.resolve("extra");
        Path extraBlocks = extraRoot.resolve("assets/minecraft/textures/block");
        Files.createDirectories(extraBlocks);
        write(extraBlocks, PACK_PREFIXED, png(16, 32));
        animationSidecar(extraBlocks, PACK_PREFIXED);
        write(extraBlocks, PACK_PREFIXED_BROKEN, truncated);
        write(extraBlocks, PACK_PREFIXED_MALFORMED, still);
        sidecar(extraBlocks, PACK_PREFIXED_MALFORMED, "{ \"animation\": ");
        write(extraBlocks, PACK_PREFIXED_RAGGED, png(16, 40));
        animationSidecar(extraBlocks, PACK_PREFIXED_RAGGED);

        PackStack bare = PackStack.of(Concurrent.newList(pack(PackId.VANILLA, vanillaRoot), pack(EXTRA, extraRoot)));
        PackStack stack = bare.withTextureIndex(TextureIndexer.index(bare));
        TextureSynthesizer synthesizer = new TextureSynthesizer(List.of(new PalettedPermutationSource(
            PALETTE_KEY, Map.of(SUFFIX, MATERIAL), List.of(STATIC, ZERO_BYTE, UNSHIPPED_BASE, SHADOWED_BASE))));

        production = new IndexedRendererContext(
            stack, Concurrent.newMap(), Set.of(), Concurrent.newMap(), Set.of(), Concurrent.newMap(),
            new ResolvedModels(Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap()),
            Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(), Concurrent.newMap(),
            Concurrent.newMap(), synthesizer, Concurrent.newMap(),
            Concurrent.newUnmodifiableList(), Concurrent.newUnmodifiableList(),
            SpriteAtlas.load(stack, SpriteAtlas.ITEMS), SpriteAtlas.load(stack, SpriteAtlas.BLOCKS));

        PixelBuffer buffer = PixelBuffer.straight(16, 16);
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
        assertStates(BLANK_SIDECAR, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(MALFORMED_SIDECAR, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(SECTIONLESS_SIDECAR, Possible.State.PRESENT, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(RAGGED_HEIGHT, Possible.State.EMPTY, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(RAGGED_WIDTH, Possible.State.EMPTY, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(RAGGED_UNDECLARED, Possible.State.EMPTY, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(SHORT_STRIP, Possible.State.EMPTY, Possible.State.PRESENT, Possible.State.EMPTY);
        assertStates(WIDE, Possible.State.PRESENT, Possible.State.PRESENT, Possible.State.PRESENT);
        assertStates(PERMUTED, Possible.State.PRESENT, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PERMUTED_OVER_BROKEN, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PERMUTED_OVER_MISSING, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED, Possible.State.PRESENT, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_BROKEN, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_MALFORMED, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_RAGGED, Possible.State.EMPTY, Possible.State.EMPTY, Possible.State.EMPTY);
        assertStates(PACK_PREFIXED_MISSING, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);
        assertStates(UNSERVED, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);
        assertStates(UNKNOWN_PREFIX, Possible.State.ABSENT, Possible.State.ABSENT, Possible.State.ABSENT);

        // The ids whose plain read is in another state than the texture lookup's, so the plain rows can fail.
        for (String id : List.of(REFUSED_ANIMATION, RAGGED_HEIGHT, RAGGED_WIDTH, RAGGED_UNDECLARED, SHORT_STRIP,
            PACK_PREFIXED_RAGGED, PERMUTED, PERMUTED_OVER_BROKEN, PERMUTED_OVER_MISSING)) {
            Possible.State plain = production.resolvePlainTexture(id).getState();
            assertThat(id + "'s plain read", plain, is(PLAIN_STATES.get(id)));
            assertThat(id + "'s plain read is not in the texture lookup's state", plain,
                is(not(production.resolveTexture(id).getState())));
        }
    }

    @Test
    @DisplayName("the zero-byte and truncated fixtures fail the decode on its two arms")
    void theFixturesReachBothArms() {
        // Without this the broken rows could pass over two files that fail the same way, leaving the
        // checked IIOException a corrupt body raises unexercised.
        assertThrows(ImageException.class, () -> ImageFactory.fromByteArray(new byte[0]));
        assertThrows(IOException.class, () -> ImageFactory.fromByteArray(truncated));
    }

    @TestFactory
    @DisplayName("every context and every wrapper over it keeps the five lookups coherent for every id")
    Stream<DynamicTest> everyContextKeepsTheLookupsCoherent() {
        return bases.entrySet().stream().flatMap(base -> {
            // A context holding no sidecar and no atlas answers one read for both.
            Function<String, Possible.State> plainBeneath = base.getValue() == production
                ? PLAIN_STATES::get
                : id -> base.getValue().resolveTexture(id).getState();
            return wrappers(base.getValue(), plainBeneath).entrySet().stream()
                .flatMap(wrapped -> IDS.stream().map(id -> DynamicTest.dynamicTest(
                    base.getKey() + ", " + wrapped.getKey() + ": " + id,
                    () -> {
                        assertCoherent(wrapped.getValue().context(), id);
                        assertPlainState(wrapped.getValue(), id);
                    })));
        });
    }

    @Test
    @DisplayName("a hidden texture is absent from all five lookups")
    void aHiddenTextureIsAbsentEverywhere() {
        RendererContext hiding = production.hiding(ANIMATED);

        assertThat(hiding.resolveTexture(ANIMATED).isAbsent(), is(true));
        assertThat(hiding.resolvePlainTexture(ANIMATED).isAbsent(), is(true));
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

    @Test
    @DisplayName("a broken file plays nothing at any tick")
    void aBrokenFilePlaysNothingAtAnyTick() {
        assertThat(Flipbook.atTick(production.resolveTexture(BROKEN_ANIMATED), production.findFlipbook(BROKEN_ANIMATED), 5)
            .getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("a broken file is decoded and reported once, then answered from memory")
    void aBrokenFileIsReportedOnce() {
        String first = errDuring(() -> assertThat(
            production.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY)));
        String again = errDuring(() -> {
            assertThat(production.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY));
            assertThat(production.resolveTexture(REPORTED_ONCE).getState(), is(Possible.State.EMPTY));
        });

        assertThat(first, containsString("Unreadable texture '" + REPORTED_ONCE + "'"));
        assertThat("a lookup after the first neither decodes nor reports again", again, is(emptyString()));
    }

    @Test
    @DisplayName("a broken file shadows the permutation registered under its own id")
    void aBrokenFileShadowsAPermutation() {
        // The permutation's inputs all decode, so consulting the synthesizer would answer present, as it
        // does for PERMUTED; the file the pack ships under the same id answers first, unreadable as it is.
        assertThat(production.resolveTexture(SHADOWED).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("substituting, a strip its frame size does not divide is the checkerboard, keeping its sidecar and playing nothing")
    void theCheckerboardNeverPlaysARaggedStripsTable() {
        RendererContext substituting = production.withMissingTexture();

        assertThat(substituting.resolveTexture(RAGGED_HEIGHT).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
        assertThat("the strip's sidecar is still read", substituting.findAnimation(RAGGED_HEIGHT).isPresent(), is(true));
        assertThat("but no table is paired with the sprite",
            substituting.findFlipbook(RAGGED_HEIGHT).getState(), is(Possible.State.EMPTY));
    }

    @Test
    @DisplayName("every wrapper over a collecting context answers its collector, in either layering order")
    void everyWrapperAnswersTheCollector() {
        for (Map.Entry<String, UnaryOperator<RendererContext>> wrapper : wrapperOperators().entrySet()) {
            String name = wrapper.getKey();
            SubstitutionCollector collector = new SubstitutionCollector();

            assertThat(name + " over a collecting context",
                wrapper.getValue().apply(production.collecting(collector)).collector(), is(sameInstance(collector)));
            assertThat("a collecting context over " + name,
                wrapper.getValue().apply(production).collecting(collector).collector(), is(sameInstance(collector)));
            assertThat(name + " over a context collecting nothing",
                wrapper.getValue().apply(production).collector(), is(sameInstance(SubstitutionCollector.DISCARD)));
        }

        SubstitutionCollector inner = new SubstitutionCollector();
        SubstitutionCollector outer = new SubstitutionCollector();
        assertThat("the collecting context applied last answers its own",
            production.collecting(inner).collecting(outer).collector(), is(sameInstance(outer)));
    }

    @Test
    @DisplayName("substituting over a collecting context records each missing and unreadable texture, once each")
    void substitutingRecordsEachStandIn() {
        SubstitutionCollector collector = new SubstitutionCollector();
        RendererContext substituting = production.collecting(collector).withMissingTexture();

        for (String id : List.of(UNSERVED, ZERO_BYTE, UNSERVED, STATIC))
            substituting.resolveTexture(id);

        assertThat(collector.snapshot(), is(List.of(
            Substitution.texture(UNSERVED, Possible.State.ABSENT),
            Substitution.texture(ZERO_BYTE, Possible.State.EMPTY))));
    }

    @Test
    @DisplayName("a stand-in reported through any wrapper over a collecting context is recorded into its collector")
    void everyWrapperReportsIntoTheCollector() {
        for (Map.Entry<String, UnaryOperator<RendererContext>> wrapper : wrapperOperators().entrySet()) {
            SubstitutionCollector collector = new SubstitutionCollector();

            wrapper.getValue().apply(production.collecting(collector)).withMissingTexture().resolveTexture(UNSERVED);

            assertThat(wrapper.getKey() + " over a collecting context", collector.snapshot(),
                is(List.of(Substitution.texture(UNSERVED, Possible.State.ABSENT))));
        }
    }

    @Test
    @DisplayName("measuring, a missing or unreadable texture is still the checkerboard, and is neither logged nor recorded")
    void measuringSubstitutesAndReportsNothing() {
        SubstitutionCollector collector = new SubstitutionCollector();
        RendererContext measuring = production
            .withTextures(id -> MEASURED_UNREADABLE.equals(id) ? Possible.empty() : Possible.absent())
            .collecting(collector)
            .measuring();
        Map<String, RendererContext> substituting = new LinkedHashMap<>();
        substituting.put("measuring", measuring.withMissingTexture());
        for (Map.Entry<String, UnaryOperator<RendererContext>> wrapper : wrapperOperators().entrySet())
            substituting.put(wrapper.getKey() + " over measuring", wrapper.getValue().apply(measuring).withMissingTexture());

        String logged = errDuring(() -> {
            for (Map.Entry<String, RendererContext> context : substituting.entrySet())
                for (String id : List.of(MEASURED_MISSING, MEASURED_UNREADABLE))
                    assertThat(context.getKey() + ": " + id + " is the checkerboard",
                        context.getValue().resolveTexture(id).orElseThrow(), is(sameInstance(MissingSprite.sprite())));
        });

        assertThat("nothing is logged", logged, is(emptyString()));
        assertThat("nothing is recorded into the collector beneath", collector.snapshot(), is(List.of()));
        assertThat("the measuring context answers the collector that keeps nothing",
            measuring.collector(), is(sameInstance(SubstitutionCollector.DISCARD)));
        SubstitutionCollector over = new SubstitutionCollector();
        assertThat("a collecting context derived over it answers its own",
            measuring.collecting(over).collector(), is(sameInstance(over)));
    }

    /**
     * Asserts the five rules over one context's answers for one id.
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
            is(meta.flatMap(document -> document.animation().orAbsent(Possible::empty)).getState()));

        if (flipbook.isPresent()) {
            assertThat("a table plays under an animation that is there", animation.isPresent(), is(true));
            assertThat("a table plays over a strip that decoded", texture.isPresent(), is(true));
        }

        if (texture.getState() == Possible.State.EMPTY) {
            assertThat("a texture with no pixels plays nothing", flipbook.getState(), is(Possible.State.EMPTY));
            assertThat("and keeps its sidecar", meta.isAbsent(), is(false));
            assertThat("and the sidecar's animation section", animation.isAbsent(), is(false));
        }

        Possible<PixelBuffer> plain = context.resolvePlainTexture(id);
        // A substituting wrapper answers a permutation's plain read with the checkerboard, which is there.
        if (!PERMUTATION_ONLY.contains(id) || !plain.isPresent()) {
            assertThat("the plain read is absent exactly when the texture is, but for a permutation",
                plain.isAbsent(), is(texture.isAbsent() || PERMUTATION_ONLY.contains(id)));
        }

        // Where each read answers a file's pixels rather than the checkerboard, the file is one, the
        // fixture's atlases stitching no sprite from another file.
        if (plain.isPresent() && texture.isPresent()
            && !MissingSprite.isSprite(plain.get()) && !MissingSprite.isSprite(texture.get()))
            assertThat("both reads answer the one file's pixels", same(plain.get(), texture.get()), is(true));
    }

    /**
     * Asserts the state a wrapper's plain read answers for one id: the state the fixture puts the read beneath
     * it in, or, through a substituting wrapper, pixels always - the checkerboard exactly where the read
     * beneath holds none.
     *
     * @param wrapped the wrapper and the state its plain read is in beneath any substitution
     * @param id the texture id asked about
     */
    private static void assertPlainState(@NotNull Wrapped wrapped, @NotNull String id) {
        Possible<PixelBuffer> plain = wrapped.context().resolvePlainTexture(id);
        Possible.State beneath = wrapped.plain().apply(id);
        if (!wrapped.substitutes())
            assertThat("the plain read is in the state the fixture puts it in", plain.getState(), is(beneath));
        else {
            assertThat("a substituting plain read always answers pixels", plain.getState(), is(Possible.State.PRESENT));
            assertThat("the checkerboard exactly where the read beneath holds none",
                MissingSprite.isSprite(plain.get()), is(beneath != Possible.State.PRESENT));
        }
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
     * Every wrapper over one context, by name, the context itself first, each with the state its plain
     * read is in beneath any substitution.
     *
     * @param base the context wrapped
     * @param plainBeneath the state the base's own plain read answers each id in
     * @return the context and its wrappers
     */
    private static @NotNull Map<String, Wrapped> wrappers(@NotNull RendererContext base,
                                                          @NotNull Function<String, Possible.State> plainBeneath) {
        PixelBuffer frame = PixelBuffer.straight(16, 16);
        Function<String, Possible.State> served = id -> SOURCE_SERVED.contains(id) ? Possible.State.PRESENT
            : SOURCE_EMPTY.equals(id) ? Possible.State.EMPTY : plainBeneath.apply(id);
        Function<String, Possible.State> reserved = id -> RESERVED.equals(id) ? Possible.State.PRESENT : plainBeneath.apply(id);
        Function<String, Possible.State> hidden = id -> HIDDEN.contains(id) ? Possible.State.ABSENT : plainBeneath.apply(id);

        Map<String, Wrapped> contexts = new LinkedHashMap<>();
        contexts.put("unwrapped", new Wrapped(base, plainBeneath, false));
        contexts.put("withTextures", new Wrapped(base.withTextures(id -> SOURCE_SERVED.contains(id) ? Possible.of(frame)
            : SOURCE_EMPTY.equals(id) ? Possible.empty() : Possible.absent()), served, false));
        contexts.put("withTexture", new Wrapped(base.withTexture(RESERVED, frame), reserved, false));
        contexts.put("withMissingTexture", new Wrapped(base.withMissingTexture(), plainBeneath, true));
        contexts.put("hiding", new Wrapped(base.hiding(HIDDEN), hidden, false));
        contexts.put("hiding then withMissingTexture", new Wrapped(base.hiding(HIDDEN).withMissingTexture(), hidden, true));
        contexts.put("collecting", new Wrapped(base.collecting(new SubstitutionCollector()), plainBeneath, false));
        contexts.put("measuring", new Wrapped(base.measuring(), plainBeneath, false));
        contexts.put("measuring then withMissingTexture", new Wrapped(base.measuring().withMissingTexture(), plainBeneath, true));
        return contexts;
    }

    /**
     * One wrapper over a base context, with the state its plain read is in.
     *
     * @param context the wrapper
     * @param plain the state the wrapper's plain read answers each id in beneath any substitution
     * @param substitutes whether a {@link RendererContext#withMissingTexture} sits on top of it
     */
    private record Wrapped(
        @NotNull RendererContext context,
        @NotNull Function<String, Possible.State> plain,
        boolean substitutes
    ) {}

    /**
     * Every wrapper that changes a lookup, by name, as the operator applying it over a context.
     *
     * @return the wrappers
     */
    private static @NotNull Map<String, UnaryOperator<RendererContext>> wrapperOperators() {
        Map<String, UnaryOperator<RendererContext>> wrappers = new LinkedHashMap<>();
        wrappers.put("withTextures", context -> context.withTextures(id -> Possible.absent()));
        wrappers.put("withTexture", context -> context.withTexture(RESERVED, PixelBuffer.straight(16, 16)));
        wrappers.put("withMissingTexture", RendererContext::withMissingTexture);
        wrappers.put("hiding", context -> context.hiding(HIDDEN));
        wrappers.put("withEntities", context -> context.withEntities(Map.of()));
        return wrappers;
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
        sidecar(blockDir, textureId, "{\"animation\":{\"frametime\":2}}");
    }

    /**
     * Writes a sidecar of the given text beside a texture.
     *
     * @param blockDir the pack's {@code textures/block} directory
     * @param textureId the texture id, its last path segment naming the file
     * @param text the sidecar's text, which need not parse
     * @throws IOException if writing fails
     */
    private static void sidecar(@NotNull Path blockDir, @NotNull String textureId, @NotNull String text) throws IOException {
        Files.writeString(blockDir.resolve(name(textureId) + ".png.mcmeta"), text);
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
