package lib.minecraft.renderer;

import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.StaticImageData;
import dev.simplified.image.pixel.AlphaMode;
import dev.simplified.image.pixel.Background;
import lib.minecraft.renderer.call.request.OutputOptions;
import lib.minecraft.renderer.call.request.PlayerOptions;
import lib.minecraft.renderer.call.request.SkinOptions;
import lib.minecraft.renderer.call.request.TextureOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Coverage of a render laid over a background, which shows the fill through as drawing the subject
 * there would.
 * <p>
 * A flat skull draws its skin's head with one source-over blit onto a transparent buffer, with no
 * lighting, supersample or anti-aliasing, so each see-through texel is held with its colour scaled by
 * its alpha and the render is marked {@link AlphaMode#PREMULTIPLIED}. Laid over
 * {@code Background.solid(0xFF404040)}, each drawn pixel is then that held colour plus the fill scaled
 * by what the texel lets through, which is the colour source-over drawing the texel onto the fill gives,
 * and the fill shows unchanged wherever nothing was drawn. The skin is supplied as PNG bytes and the
 * render reads no other texture, so no pack is needed.
 * <p>
 * Pixels are read by what the clear render drew rather than by position, so the flat layout is free to
 * place the head anywhere on the canvas. The canvas is wider than the head, so every render holds
 * pixels nothing draws beside the drawn ones, and both kinds are checked.
 */
@DisplayName("A render over a background shows the fill through as drawing it there would")
class PlayerRendererBackgroundTest {

    /** The opaque fill the render is laid over. */
    private static final int FILL = 0xFF404040;

    @Test
    @DisplayName("each see-through skin texel lands over the fill as source-over drawing onto it lands")
    void aRenderOverAFillShowsItThroughAsDrawn() {
        // { the skin texel, straight; the pixel drawn over transparent; the pixel laid over FILL }
        int[][] rows = {
            { 0x80FF0000, 0x80800000, 0xFFA02020 },
            { 0xF0100010, 0xF00F000F, 0xFF130413 },
            { 0x404080C0, 0x40102030, 0xFF405060 }
        };
        PlayerRenderer renderer = new PlayerRenderer(RendererContext.builder().build());

        for (int[] row : rows) {
            String label = "skin texel %08X".formatted(row[0]);

            // The head's whole base layer, so whichever face the flat layout crops reads the texel; the
            // hat layer from x 32 stays clear.
            PixelBuffer sheet = PixelBuffer.straight(64, 64);
            sheet.fillRect(0, 0, 32, 16, row[0]);
            byte[] png = ImageFactory.toByteArray(StaticImageData.of(sheet), ImageFormat.PNG);

            ImageData clear = renderer.render(skull(png, Background.TRANSPARENT)).image();
            ImageData filled = renderer.render(skull(png, Background.solid(FILL))).image();
            int[] drawn = clear.toPixelBuffer().data();
            int[] laid = filled.toPixelBuffer().data();
            assertThat(label + " canvas", laid.length, is(drawn.length));

            boolean drewAny = false;
            boolean undrawnAny = false;
            for (int i = 0; i < drawn.length; i++) {
                if (drawn[i] == 0) {
                    assertThat(label + " pixel " + i + " over the fill", "%08X".formatted(laid[i]), is("%08X".formatted(FILL)));
                    undrawnAny = true;
                    continue;
                }

                assertThat(label + " pixel " + i + " drawn", "%08X".formatted(drawn[i]), is("%08X".formatted(row[1])));
                assertThat(label + " pixel " + i + " over the fill", "%08X".formatted(laid[i]), is("%08X".formatted(row[2])));
                drewAny = true;
            }

            assertThat(label + " draws the head", drewAny, is(true));
            assertThat(label + " leaves pixels undrawn", undrawnAny, is(true));
            assertThat(label + " is marked premultiplied", clear.getAlphaMode(), is(AlphaMode.PREMULTIPLIED));
        }
    }

    /**
     * Builds a flat skull over a skin supplied as PNG bytes, at a small canvas without anti-aliasing,
     * laid over the given background. The head scales to 32 pixels on the 36-pixel canvas, which leaves
     * a margin it does not draw.
     *
     * @param png the skin sheet, encoded
     * @param background the fill behind the render
     * @return the options
     */
    private static @NotNull PlayerOptions skull(byte @NotNull [] png, @NotNull Background background) {
        return PlayerOptions.builder()
            .type(PlayerOptions.Type.SKULL)
            .dimension(PlayerOptions.Dimension.TWO_D)
            .skin(SkinOptions.builder().skin(TextureOptions.builder().bytes(Optional.of(png)).build()).build())
            .output(OutputOptions.builder().canvasSize(36).antiAlias(false).build())
            .background(background)
            .build();
    }

}
