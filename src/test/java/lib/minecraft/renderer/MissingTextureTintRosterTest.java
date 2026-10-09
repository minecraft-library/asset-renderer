package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.engine.texture.MissingSprite;
import lib.minecraft.renderer.store.diff.RenderDigest;
import lib.minecraft.renderer.support.ClientAssetsExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Coverage that a substituted checkerboard still carries its subject's own tint - the inventory icons
 * whose sprite is neutral until a tint is multiplied into it, rendered with that sprite forced absent.
 * <p>
 * What this pins is placement rather than colour. A checkerboard arriving downstream of the tint stage
 * would render bare magenta on every row here and nothing else in the suite would notice, so each row
 * asserts the product of the tint and the sprite rather than the sprite. Magenta is the probe that
 * makes it legible: its green channel is zero and both branches take zero to zero, so a tinted product
 * must have green zero and the red-to-blue ratio is what tells one tint from another.
 * <p>
 * Every expected value here is derived from this repo's own arithmetic, never read off a render. The
 * two branches round differently - the flat path rounds up at {@code 127/255}, the isometric one at
 * the shade quantiser's own tie point - so they keep separate literals even where they agree, and a
 * change to either rule fails a row rather than silently redefining both.
 * <p>
 * Reads the client assets through {@link ClientAssetsExtension}, which abandons the class
 * where nothing has extracted the client yet.
 */
@DisplayName("Missing-texture substitution carries the subject's tint")
@ExtendWith(ClientAssetsExtension.class)
class MissingTextureTintRosterTest {

    private static final int SIZE = 64;

    /** Canvas indices whose source texel falls in an anti-diagonal (magenta) quadrant of the sprite. */
    private static final int[] MAGENTA_AT = {63, 4032, 2016};

    /** Canvas indices whose source texel falls in a leading-diagonal (black) quadrant. */
    private static final int[] BLACK_AT = {0, 4095, 2015};

    private static RendererContext context;

    @BeforeAll
    static void bootstrapPipeline() {
        context = ClientAssetsExtension.context();
    }

    @Test
    @DisplayName("oak leaves tint the checkerboard with their item definition's constant")
    void oakLeavesTintFoliage() {
        // The definition's constant is 0xFF48B518, the colour the foliage colormap answers at no
        // biome; the plains sample, which a plain block render takes, is 0xFF77AB2F.
        assertIsometric("minecraft:oak_leaves", "minecraft:block/oak_leaves",
            Set.of(0xFF000000, 0xFF460017, 0xFF2D000F, 0xFF1C0009));
    }

    @Test
    @DisplayName("mangrove leaves take their item definition's constant, not the foliage colour")
    void mangroveLeavesTakeTheirDefinitionConstant() {
        // The slot icon and the held view both read the item definition, whose constant 0xFF92C648
        // is not the foliage colour 0xFF48B518 that the block tint table gives - which is what the
        // placed block and the harness's block reference carry. This row catches an icon that read
        // the block's table or a biome; birch and spruce agree across all three either way.
        assertIsometric("minecraft:mangrove_leaves", "minecraft:block/mangrove_leaves",
            Set.of(0xFF000000, 0xFF8E0046, 0xFF5C002D, 0xFF39001C));
    }

    @Test
    @DisplayName("mangrove leaves held take their item definition's constant, not the foliage sample")
    void mangroveLeavesHeldTakeTheirDefinitionConstant() {
        // The held view reads the item definition, whose constant 0xFF92C648 is not the foliage
        // colour 0xFF48B518 the block tint table gives. Across a magenta texel the product's red to
        // blue is the tint's own - 146:72 for the constant, 72:24 for foliage - whatever the shade.
        int[] pixels = renderHiding("minecraft:mangrove_leaves", ItemOptions.Type.HELD_3D,
            "minecraft:block/mangrove_leaves");
        int tinted = 0;
        for (int pixel : pixels) {
            int red = pixel >>> 16 & 0xFF;
            int blue = pixel & 0xFF;
            // A black cell, or an edge nearly black, carries no ratio worth reading.
            if ((pixel >>> 24) != 0xFF || blue < 16) continue;
            tinted++;
            assertThat("red to blue of " + Integer.toHexString(pixel),
                (double) red / blue, closeTo(146.0 / 72.0, 0.15));
        }
        assertThat("the held leaves carry tinted magenta texels", tinted, greaterThan(0));
        assertNoGreen(pixels);
    }

    @Test
    @DisplayName("birch leaves tint with their constant")
    void birchLeavesTintConstant() {
        assertIsometric("minecraft:birch_leaves", "minecraft:block/birch_leaves",
            Set.of(0xFF000000, 0xFF7C0053, 0xFF510036, 0xFF320021));
    }

    @Test
    @DisplayName("spruce leaves tint with their constant, whose red and blue are equal")
    void spruceLeavesTintConstant() {
        assertIsometric("minecraft:spruce_leaves", "minecraft:block/spruce_leaves",
            Set.of(0xFF000000, 0xFF5E005E, 0xFF3D003D, 0xFF260026));
    }

    @Test
    @DisplayName("stone is untinted, so its three magenta values are the bare shade table")
    void stoneIsUntintedAndPinsTheShades() {
        // The control. Stone carries no tint entry and no tintindex, so these three are the untinted
        // texel at the three visible shades - the table every other isometric row's product is built
        // on. If stone moves and the leaves move with it the shading changed; if the leaves move
        // alone, a tint did.
        assertIsometric("minecraft:stone", "minecraft:block/stone",
            Set.of(0xFF000000, 0xFFF800F8, 0xFFA100A1, 0xFF630063));
    }

    @Test
    @DisplayName("lily pad tints the flat checkerboard with its constant")
    void lilyPadTintsFlat() {
        assertFlat("minecraft:lily_pad", "minecraft:block/lily_pad", 0xFF6E0059);
    }

    @Test
    @DisplayName("short grass tints the flat checkerboard with the grass colormap at its declared climate")
    void shortGrassTintsWithTheGrassColormap() {
        // Its item definition's grass tint samples the grass colormap at (0.5, 1.0), vanilla's
        // 0xFF7CBD6B; its block tint entry is on the other branch entirely.
        assertFlat("minecraft:short_grass", "minecraft:block/short_grass", 0xFF790068);
    }

    @Test
    @DisplayName("sugar cane declares no layer tint at all, so its block tint never reaches the icon")
    void sugarCaneRendersUntinted() {
        assertFlat("minecraft:sugar_cane", "minecraft:item/sugar_cane", MissingSprite.MAGENTA_ARGB);
    }

    @Test
    @DisplayName("leather helmet tints its base layer under the untinted overlay")
    void leatherHelmetTintsUnderTheOverlay() {
        // Containment rather than an exact set: only layer0 is hidden, so the real overlay sprite
        // composites over the tinted checkerboard wherever it is opaque and no fixed coordinate is
        // safe. The absent assertion is the load-bearing half - without it the row would pass if the
        // tint had been dropped and the overlay happened to cover the bare checkerboard.
        int[] pixels = renderHiding("minecraft:leather_helmet", ItemOptions.Type.GUI_ICON,
            "minecraft:item/leather_helmet");

        assertThat(distinctOpaque(pixels), hasItems(0xFF9C003E, MissingSprite.BLACK_ARGB));
        assertThat("no untinted checkerboard survives",
            distinctOpaque(pixels), not(hasItems(MissingSprite.MAGENTA_ARGB)));
    }

    @Test
    @DisplayName("a grass block tints only the one face its model asks to be tinted")
    void grassBlockTintsOnlyItsTopFace() {
        // Only the top face carries a tint index, and its shade is exactly one, so the substituted top
        // is the flat-branch product. The four sides are real textures at no tint index. The tint is
        // the item definition's grass sample at (0.5, 1.0), 0xFF7CBD6B, the same product the short
        // grass row reads.
        int[] pixels = renderHiding("minecraft:grass_block", ItemOptions.Type.GUI_ICON,
            "minecraft:block/grass_block_top");

        assertThat(distinctOpaque(pixels), hasItems(0xFF790068, MissingSprite.BLACK_ARGB));
        assertThat("no untinted checkerboard survives",
            distinctOpaque(pixels), not(hasItems(MissingSprite.MAGENTA_ARGB)));
    }

    /**
     * Asserts an isometric-branch row: the id is block-backed, and the render carries exactly the
     * three shaded magenta products plus the black that cannot shade.
     *
     * @param blockId the subject, which must be absent from the item index
     * @param textureId the sprite id to force absent
     * @param expected the distinct opaque colours the render must carry, and no others
     */
    private static void assertIsometric(
        @NotNull String blockId, @NotNull String textureId, @NotNull Set<Integer> expected) {
        assertThat(blockId + " is absent from the item index",
            context.findItem(blockId).isPresent(), is(false));
        assertThat(blockId + " is carried by the block index",
            context.findBlock(blockId).isPresent(), is(true));

        int[] pixels = renderHiding(blockId, ItemOptions.Type.GUI_ICON, textureId);
        assertThat(distinctOpaque(pixels), is(expected));
        assertNoGreen(pixels);
    }

    /**
     * Asserts a flat-branch row: the id is item-backed, and the substituted checkerboard fills the
     * canvas carrying the tinted magenta and the untouched black at their nearest-upscaled positions.
     *
     * @param itemId the subject, which must be carried by the item index
     * @param textureId the sprite id to force absent
     * @param magenta the expected product of the tint and the sprite's magenta texel
     */
    private static void assertFlat(
        @NotNull String itemId, @NotNull String textureId, int magenta) {
        assertThat(itemId + " is carried by the item index",
            context.findItem(itemId).isPresent(), is(true));

        int[] pixels = renderHiding(itemId, ItemOptions.Type.GUI_ICON, textureId);

        for (int index : MAGENTA_AT)
            assertThat("magenta quadrant at " + index, pixels[index], is(magenta));

        for (int index : BLACK_AT)
            assertThat("black quadrant at " + index, pixels[index], is(MissingSprite.BLACK_ARGB));

        // The set is what proves nothing else was drawn - and its size of two is also what separates
        // this branch from the isometric one, which answers four.
        assertThat(distinctOpaque(pixels), is(Set.of(magenta, MissingSprite.BLACK_ARGB)));
        assertNoGreen(pixels);
    }

    /**
     * Renders a subject with one texture id forced absent, having first established that the id really
     * does resolve without the wrapper and really does not with it, and that the wrapper moves no pixel
     * when it hides nothing.
     *
     * @param subjectId the block or item id to render
     * @param type the render mode to dispatch through
     * @param textureId the sprite id to force absent
     * @return the rendered frame's ARGB texels
     */
    private static int[] renderHiding(
        @NotNull String subjectId, ItemOptions.@NotNull Type type, @NotNull String textureId) {
        RendererContext inert = context.hiding();
        RendererContext hidden = context.hiding(textureId);

        assertThat(textureId + " resolves before it is hidden",
            context.resolveTexture(textureId).isPresent(), is(true));
        assertThat(textureId + " must be absent from the context the render sees",
            hidden.resolveTexture(textureId).isEmpty(), is(true));

        int[] raw = render(context, subjectId, type);
        assertThat("hiding nothing moves no pixel", render(inert, subjectId, type), is(raw));

        int[] substituted = render(hidden, subjectId, type);
        assertThat("hiding the sprite changes the picture", substituted, is(not(raw)));

        return substituted;
    }

    /**
     * Renders a subject through the given context at the shared test canvas.
     *
     * @param source the context to render over
     * @param subjectId the block or item id to render
     * @param type the render mode to dispatch through
     * @return the rendered frame's ARGB texels
     */
    private static int[] render(
        @NotNull RendererContext source, @NotNull String subjectId, ItemOptions.@NotNull Type type) {
        ImageData image = new ItemRenderer(source).render(ItemOptions.builder()
            .itemId(subjectId)
            .type(type)
            .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(SIZE).build())
            .build());

        return RenderDigest.firstFramePixels(image);
    }

    /**
     * Collects the distinct fully-opaque colours a frame carries.
     *
     * @param pixels the frame's ARGB texels
     * @return every opaque colour present, without duplicates
     */
    private static Set<Integer> distinctOpaque(int[] pixels) {
        Set<Integer> colours = new HashSet<>();
        for (int pixel : pixels)
            if ((pixel >>> 24) == 0xFF) colours.add(pixel);

        return colours;
    }

    /**
     * Asserts the invariant that survives every disagreement about an exact value: the sprite's only
     * colours have a zero green channel, and both branches take zero to zero whatever the tint.
     * <p>
     * It holds only where every opaque pixel came from the substituted sprite, so the two containment
     * rows do not use it - a real sprite composited over the checkerboard brings its own green, and
     * asserting this over those pixels would be asserting it about the wrong texels. Those rows get
     * the same guarantee from the expected product they name, whose own green byte is zero.
     *
     * @param pixels the frame's ARGB texels
     */
    private static void assertNoGreen(int[] pixels) {
        for (int pixel : pixels)
            if ((pixel >>> 24) == 0xFF)
                assertThat("green channel of " + Integer.toHexString(pixel),
                    (pixel >>> 8) & 0xFF, is(0));
    }

}
