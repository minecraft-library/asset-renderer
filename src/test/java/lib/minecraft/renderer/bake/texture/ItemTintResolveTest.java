package lib.minecraft.renderer.bake.texture;

import dev.simplified.collection.Concurrent;
import dev.simplified.image.pixel.ColorMath;
import lib.minecraft.renderer.asset.ColorMap;
import lib.minecraft.renderer.asset.Item.LayerTint;
import lib.minecraft.renderer.port.RendererContext;
import lib.minecraft.renderer.request.DecorationOptions;
import lib.minecraft.renderer.request.ItemOptions;
import lib.minecraft.renderer.vanilla.TintSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * One item-definition tint calculated against the caller's overrides and the pack stack: a grass tint
 * samples the stack's own grass colormap at the definition's climate point, and a map-colour tint takes
 * the caller's colour else its default, opaque either way. The colours a model's faces pick by
 * tintindex fill slot 0 with the caller's colour only where the definition lists no tint.
 */
@DisplayName("An item-definition tint resolves against the options and the pack stack")
class ItemTintResolveTest {

    /** The colour every pixel of the fixture colormap holds but the sampled ones. */
    private static final int FIELD = 0xFF101010;

    /** Vanilla grass.png's pixel at (127, 127), where the six 26.1 grass tints sample. */
    private static final int INVENTORY_GRASS = 0xFF7CBD6B;

    /** The fixture's pixel at (0, 255), where temperature 1 and downfall 0 sample. */
    private static final int HOT_DRY = 0xFFBFB755;

    @Test
    @DisplayName("a grass tint samples the stack's grass colormap at its own climate point")
    void grassSamplesTheStacksColormap() {
        RendererContext context = RendererContext.builder()
            .colorMaps(Map.of(TintSource.GRASS, grassMap()))
            .build();

        assertThat(ItemTint.resolve(context, new LayerTint.Grass(0.5f, 1.0f), options()), is(INVENTORY_GRASS));
        assertThat(ItemTint.resolve(context, new LayerTint.Grass(1.0f, 0.0f), options()), is(HOT_DRY));
    }

    @Test
    @DisplayName("a grass tint answers the source's default where the stack carries no grass colormap")
    void grassWithoutAColormapFallsBack() {
        assertThat(ItemTint.resolve(RendererContext.builder().build(), new LayerTint.Grass(0.5f, 1.0f), options()),
            is(TintSource.GRASS.defaultArgb()));
        assertThat(TintSource.GRASS.defaultArgb(), is(ColorMath.WHITE));
    }

    @Test
    @DisplayName("a map-colour tint takes its default, else the caller's colour, opaque either way")
    void mapColorTakesTheCallersColourElseItsDefault() {
        RendererContext context = RendererContext.builder().build();
        LayerTint tint = new LayerTint.MapColor(0xFF46402E);

        assertThat(ItemTint.resolve(context, tint, options()), is(0xFF46402E));
        assertThat(ItemTint.resolve(context, tint, ItemOptions.builder()
            .itemId("minecraft:filled_map")
            .decoration(DecorationOptions.builder().tintColor(0x00123456).build())
            .build()), is(0xFF123456));
    }

    /**
     * Pins what a caller's custom colour reaches on a model's faces: tintindex 0, and only where the
     * item definition lists no tint of its own, as a flat sprite's {@code layer0} takes it. A
     * definition tint that falls back to the caller's colour, such as a dye, still takes it through
     * its own fallback.
     */
    @Test
    @DisplayName("the caller's colour fills slot 0 only where the definition lists no tint")
    void layerTintsFillSlotZeroOnlyWhereTheDefinitionHasNone() {
        RendererContext context = RendererContext.builder().build();
        int caller = 0xFF3060C0;
        int k1 = 0xFF112233;
        int k2 = 0xFF445566;

        assertThat("no tint and no caller colour",
            ItemTint.layerTints(context, Concurrent.newUnmodifiableList(), options()), is(new int[0]));
        assertThat("no tint, a caller colour",
            ItemTint.layerTints(context, Concurrent.newUnmodifiableList(), tinted(caller)), is(new int[]{ caller }));
        assertThat("a constant keeps its own colour",
            ItemTint.layerTints(context, Concurrent.newUnmodifiableList(new LayerTint.Constant(k1)), tinted(caller)),
            is(new int[]{ k1 }));
        assertThat("a dye falls back to the caller's colour",
            ItemTint.layerTints(context, Concurrent.newUnmodifiableList(new LayerTint.Dye(k1)), tinted(caller)),
            is(new int[]{ caller }));
        assertThat("two constants stand at their own indices",
            ItemTint.layerTints(context, Concurrent.newUnmodifiableList(new LayerTint.Constant(k1), new LayerTint.Constant(k2)), options()),
            is(new int[]{ k1, k2 }));
    }

    /**
     * Options for a filled map with a caller's custom colour.
     *
     * @param argb the caller's colour
     * @return the options
     */
    private static @NotNull ItemOptions tinted(int argb) {
        return ItemOptions.builder()
            .itemId("minecraft:filled_map")
            .decoration(DecorationOptions.builder().tintColor(argb).build())
            .build();
    }

    /**
     * Options for a filled map with no overrides.
     *
     * @return the options
     */
    private static @NotNull ItemOptions options() {
        return ItemOptions.builder().itemId("minecraft:filled_map").build();
    }

    /**
     * A 256x256 grass colormap holding {@link #FIELD} everywhere but the two pixels the cases sample.
     *
     * @return the colormap
     */
    private static @NotNull ColorMap grassMap() {
        byte[] pixels = new byte[256 * 256 * Integer.BYTES];
        for (int i = 0; i < 256 * 256; i++) put(pixels, i, FIELD);
        put(pixels, 127 * 256 + 127, INVENTORY_GRASS);
        put(pixels, 255 * 256, HOT_DRY);
        return new ColorMap("minecraft:colormap/grass", "fixture", TintSource.GRASS, pixels);
    }

    /**
     * Writes one ARGB pixel big-endian at its index.
     *
     * @param pixels the colormap's bytes
     * @param index the pixel's row-major index
     * @param argb the colour
     */
    private static void put(byte @NotNull [] pixels, int index, int argb) {
        int offset = index * Integer.BYTES;
        pixels[offset] = (byte) (argb >>> 24);
        pixels[offset + 1] = (byte) (argb >>> 16);
        pixels[offset + 2] = (byte) (argb >>> 8);
        pixels[offset + 3] = (byte) argb;
    }

}
