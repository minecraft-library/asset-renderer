package lib.minecraft.renderer.engine.texture;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.engine.kit.TrimKit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Pins {@link TextureSynthesizer}: a registered {@code <base>_<permutation>} id synthesises to the
 * exact {@link TrimKit#permute} output of its resolved inputs (byte-identical to the trim path by
 * construction), an unregistered id returns empty, and a missing input aborts cleanly.
 */
@DisplayName("TextureSynthesizer paletted-permutation synthesis")
class TextureSynthesizerTest {

    private static final String PALETTE_KEY = "minecraft:trims/color_palettes/trim_palette";
    private static final String MATERIAL = "minecraft:trims/color_palettes/amethyst";
    private static final String BASE = "minecraft:trims/items/chestplate_trim";
    private static final String SYNTH_ID = "minecraft:trims/items/chestplate_trim_amethyst";

    private static final PixelBuffer PALETTE = PixelBuffer.of(new int[]{0xFF00000A, 0xFF000014, 0xFF00001E}, 3, 1);
    private static final PixelBuffer MATERIAL_STRIP = PixelBuffer.of(new int[]{0x00FF0000, 0x0000FF00, 0x000000FF}, 3, 1);
    private static final PixelBuffer BASE_PATTERN = PixelBuffer.of(new int[]{0xFF00000A, 0xFF000014, 0xFF00001E, 0x00000000}, 2, 2);

    private static final Map<String, PixelBuffer> TEXTURES = Map.of(
        PALETTE_KEY, PALETTE, MATERIAL, MATERIAL_STRIP, BASE, BASE_PATTERN);
    private static final Function<String, Optional<PixelBuffer>> RESOLVER = ref -> Optional.ofNullable(TEXTURES.get(ref));

    private static TextureSynthesizer synthesizer() {
        return new TextureSynthesizer(List.of(
            new PalettedPermutationSource(PALETTE_KEY, Map.of("amethyst", MATERIAL), List.of(BASE))));
    }

    @Test
    @DisplayName("a registered synthetic id permutes to the TrimKit.permute output byte-for-byte")
    void synthesizesTrimByteIdentical() {
        Optional<PixelBuffer> out = synthesizer().synthesize(ResourceId.parse(SYNTH_ID), RESOLVER);
        assertThat(out.isPresent(), is(true));
        assertPixelsEqual(out.get(), TrimKit.permute(BASE_PATTERN, PALETTE, MATERIAL_STRIP));
    }

    @Test
    @DisplayName("memoisation returns the same buffer on the second call")
    void memoises() {
        TextureSynthesizer synth = synthesizer();
        PixelBuffer first = synth.synthesize(ResourceId.parse(SYNTH_ID), RESOLVER).orElseThrow();
        PixelBuffer second = synth.synthesize(ResourceId.parse(SYNTH_ID), RESOLVER).orElseThrow();
        assertThat(first == second, is(true));
    }

    @Test
    @DisplayName("an unregistered id synthesises nothing")
    void unregisteredIdEmpty() {
        assertThat(synthesizer().synthesize(ResourceId.parse("minecraft:item/diamond_sword"), RESOLVER).isEmpty(), is(true));
    }

    @Test
    @DisplayName("a missing input texture aborts the synthesis")
    void missingInputEmpty() {
        Function<String, Optional<PixelBuffer>> partial = ref ->
            ref.equals(MATERIAL) ? Optional.empty() : RESOLVER.apply(ref);
        assertThat(synthesizer().synthesize(ResourceId.parse(SYNTH_ID), partial).isEmpty(), is(true));
    }

    @Test
    @DisplayName("the empty synthesizer never fires")
    void emptySynthesizer() {
        assertThat(TextureSynthesizer.EMPTY.synthesize(ResourceId.parse(SYNTH_ID), RESOLVER).isEmpty(), is(true));
    }

    private static void assertPixelsEqual(PixelBuffer actual, PixelBuffer expected) {
        assertThat("width", actual.width(), is(expected.width()));
        assertThat("height", actual.height(), is(expected.height()));
        for (int y = 0; y < expected.height(); y++)
            for (int x = 0; x < expected.width(); x++)
                assertThat("pixel " + x + "," + y, actual.getPixel(x, y), is(expected.getPixel(x, y)));
    }

}
