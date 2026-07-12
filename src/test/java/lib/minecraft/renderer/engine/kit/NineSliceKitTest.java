package lib.minecraft.renderer.engine.kit;

import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.pipeline.pack.MCMeta;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Unit tests for {@link NineSliceKit}, exercising the three scaling modes against the real 26.1
 * tooltip sprites (background border 9 / frame border 10 + stretch_inner) at the exact blit geometry
 * the tooltip chrome uses, plus the pure-algorithm cases (stretch, tile, nominal downsample,
 * degenerate-rect clamp, alpha multiplier) on synthetic sprites so they run without the extraction.
 */
class NineSliceKitTest {

    /** The vanilla tooltip sprite directory in the offline extraction; the real-sprite tests skip when absent. */
    private static final Path TOOLTIP_DIR = Path.of(
        "cache/asset-renderer/vanilla/26.1/assets/minecraft/textures/gui/sprites/tooltip");

    private static final int PX_SCALE = 2;

    // The tooltip chrome blit geometry: a fresh canvas of canvasW x canvasH output px, with both
    // sprites drawn over the canvas inflated 16 output px each side (content box + vanilla's 12 mcPx
    // inflate, minus the 4 mcPx sprite padding, all scaled by PX_SCALE) so the background fill lands
    // flush to the canvas edge and the frame ring 1 mcPx inside it.
    private static final int CANVAS_W = 80;
    private static final int CANVAS_H = 40;
    private static final int INFLATE = 16;
    private static final int BLIT_X = -INFLATE;
    private static final int BLIT_Y = -INFLATE;
    private static final int BLIT_W = CANVAS_W + 2 * INFLATE;
    private static final int BLIT_H = CANVAS_H + 2 * INFLATE;

    private static PixelBuffer load(String name) {
        try {
            BufferedImage image = ImageIO.read(TOOLTIP_DIR.resolve(name).toFile());
            return PixelBuffer.wrap(image);
        } catch (IOException ex) {
            throw new AssertionError("Failed to read tooltip sprite '" + name + "'", ex);
        }
    }

    private static MCMeta.GuiScaling scaling(String mcmetaName) {
        try {
            String json = Files.readString(TOOLTIP_DIR.resolve(mcmetaName));
            MCMeta meta = MCMeta.parse(json, new ResourceId("minecraft", "tooltip"));
            return meta.gui().orElseThrow(() -> new AssertionError("no gui.scaling in " + mcmetaName));
        } catch (IOException ex) {
            throw new AssertionError("Failed to read mcmeta '" + mcmetaName + "'", ex);
        }
    }

    private static void assumeSprites() {
        Assumptions.assumeTrue(Files.isDirectory(TOOLTIP_DIR), "vanilla 26.1 extraction not present");
    }

    @Test
    @DisplayName("real background border-9: corner notched, fill flush to canvas edges")
    void backgroundNineSliceNotchAndFill() {
        assumeSprites();
        PixelBuffer bg = load("background.png");
        MCMeta.GuiScaling gui = scaling("background.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.create(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, bg, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, PX_SCALE, 1.0f);

        // Corner mcPixel is notched (sprite (8,8) is the cut corner) -> transparent.
        assertThat("notched top-left corner", ColorMath.alpha(canvas.getPixel(0, 0)), is(0));
        // Fill reaches the top edge mid-span and the left edge mid-span (only corners are cut).
        assertThat("fill flush top edge", canvas.getPixel(CANVAS_W / 2, 0), is(0xF0100010));
        assertThat("fill flush left edge", canvas.getPixel(0, CANVAS_H / 2), is(0xF0100010));
        assertThat("center fill", canvas.getPixel(CANVAS_W / 2, CANVAS_H / 2), is(0xF0100010));
    }

    @Test
    @DisplayName("real frame border-10 + stretch_inner: ring 1 mcPx in, open corners, stretched gradient")
    void frameNineSliceRingAndGradient() {
        assumeSprites();
        PixelBuffer frame = load("frame.png");
        MCMeta.GuiScaling gui = scaling("frame.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.create(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, frame, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, PX_SCALE, 1.0f);

        // Ring top stroke sits at output y=2 (1 mcPx inset) with the vanilla top gradient colour.
        assertThat("ring top", canvas.getPixel(CANVAS_W / 2, 2), is(0x505000FF));
        // Ring bottom stroke at output y=canvasH-3 with the bottom gradient colour.
        assertThat("ring bottom", canvas.getPixel(CANVAS_W / 2, CANVAS_H - 3), is(0x5028007F));
        // The ring corner pixel is transparent in the sprite -> open corner (destination untouched).
        assertThat("open ring corner", ColorMath.alpha(canvas.getPixel(2, 2)), is(0));
        // Left edge mid-height is the stretched gradient interior: border alpha 0x50, green constant 0,
        // colour strictly between the two endpoints.
        int mid = canvas.getPixel(2, CANVAS_H / 2);
        assertThat("left edge alpha", ColorMath.alpha(mid), is(0x50));
        assertThat("left edge green constant", ColorMath.green(mid), is(0));
        assertThat("left edge is interpolated (not top)", mid, is(not(0x505000FF)));
        assertThat("left edge is interpolated (not bottom)", mid, is(not(0x5028007F)));
    }

    @Test
    @DisplayName("alpha multiplier scales the frame ring alpha, leaving rgb untouched")
    void alphaMultiplierScalesRingAlpha() {
        assumeSprites();
        PixelBuffer frame = load("frame.png");
        MCMeta.GuiScaling gui = scaling("frame.png.mcmeta");
        PixelBuffer canvas = PixelBuffer.create(CANVAS_W, CANVAS_H);

        NineSliceKit.draw(canvas, frame, gui, BLIT_X, BLIT_Y, BLIT_W, BLIT_H, PX_SCALE, 0.5f);

        int top = canvas.getPixel(CANVAS_W / 2, 2);
        // 0x50 (80) * 0.5 = 40 = 0x28; rgb unchanged.
        assertThat("halved ring alpha", ColorMath.alpha(top), is(0x28));
        assertThat("ring rgb untouched", top & 0xFFFFFF, is(0x5000FF));
    }

    @Test
    @DisplayName("stretch mode expands a 2x2 sprite across quadrants")
    void stretchExpandsSprite() {
        int[] px = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF}; // R G B W, row-major
        PixelBuffer sprite = PixelBuffer.of(px, 2, 2);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.STRETCH, -1, -1, new MCMeta.GuiScaling.Border(0, 0, 0, 0), false);
        PixelBuffer dest = PixelBuffer.create(4, 4);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 4, 4, 1, 1.0f);

        assertThat(dest.getPixel(0, 0), is(0xFFFF0000));
        assertThat(dest.getPixel(3, 0), is(0xFF00FF00));
        assertThat(dest.getPixel(0, 3), is(0xFF0000FF));
        assertThat(dest.getPixel(3, 3), is(0xFFFFFFFF));
    }

    @Test
    @DisplayName("tile mode repeats the sprite from the top-left")
    void tileRepeatsSprite() {
        int[] px = {0xFFAAAAAA, 0xFFBBBBBB, 0xFFCCCCCC, 0xFFDDDDDD};
        PixelBuffer sprite = PixelBuffer.of(px, 2, 2);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.TILE, 2, 2, new MCMeta.GuiScaling.Border(0, 0, 0, 0), false);
        PixelBuffer dest = PixelBuffer.create(4, 4);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 4, 4, 1, 1.0f);

        assertThat("tile repeats horizontally", dest.getPixel(2, 0), is(dest.getPixel(0, 0)));
        assertThat("tile repeats vertically", dest.getPixel(0, 2), is(dest.getPixel(0, 0)));
        assertThat("period-2 second column", dest.getPixel(3, 0), is(dest.getPixel(1, 0)));
    }

    @Test
    @DisplayName("nominal downsample slices at the declared size, not the texture size")
    void nominalDownsampleTiles() {
        // A 4x2 sprite with four distinct columns, declared nominal 2x2: nominalize downsamples to
        // 2 columns, so a pxScale-1 tile repeats with period 2 (col0==col2), which a texture-size
        // (period-4) tile would not.
        int[] px = {0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444,
                    0xFF111111, 0xFF222222, 0xFF333333, 0xFF444444};
        PixelBuffer sprite = PixelBuffer.of(px, 4, 2);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.TILE, 2, 2, new MCMeta.GuiScaling.Border(0, 0, 0, 0), false);
        PixelBuffer dest = PixelBuffer.create(4, 2);

        NineSliceKit.draw(dest, sprite, gui, 0, 0, 4, 2, 1, 1.0f);

        assertThat("nominal period 2", dest.getPixel(2, 0), is(dest.getPixel(0, 0)));
        assertThat("nominal period 2", dest.getPixel(3, 0), is(dest.getPixel(1, 0)));
        assertThat("two distinct nominal columns", dest.getPixel(0, 0), is(not(dest.getPixel(1, 0))));
    }

    @Test
    @DisplayName("degenerate rect clamps borders so slices never overlap or overrun")
    void degenerateRectClamps() {
        int[] px = new int[20 * 20];
        java.util.Arrays.fill(px, 0xFF808080);
        PixelBuffer sprite = PixelBuffer.of(px, 20, 20);
        MCMeta.GuiScaling gui = new MCMeta.GuiScaling(MCMeta.GuiScaling.Type.NINE_SLICE, -1, -1, new MCMeta.GuiScaling.Border(9, 9, 9, 9), false);
        PixelBuffer dest = PixelBuffer.create(10, 10);

        // 9+9 border px exceed the 10 px destination; must clamp rather than throw / read OOB.
        NineSliceKit.draw(dest, sprite, gui, 0, 0, 10, 10, 1, 1.0f);

        assertThat("clamped center still painted", ColorMath.alpha(dest.getPixel(5, 5)), is(0xFF));
        assertThat("clamped corner painted", ColorMath.alpha(dest.getPixel(0, 0)), is(0xFF));
    }
}
