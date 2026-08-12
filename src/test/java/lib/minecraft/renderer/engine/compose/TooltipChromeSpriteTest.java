package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.pixel.ColorMath;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.support.MinecraftFontsExtension;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sprite-flip corner / canvas probes for {@link TooltipChrome.Vanilla#SPRITE}: renders a LORE tooltip
 * with the real 26.1 tooltip sprites and pins the intended visual delta (notched background
 * corners, open ring corners, ring inset 1 mcPixel, padding 4 canvas shrink) plus the alpha-override
 * multiplier redefinition and the missing-sprites guard.
 */
@ExtendWith(MinecraftFontsExtension.class)
class TooltipChromeSpriteTest {

    private static final Path TOOLTIP_DIR = Path.of(
        "cache/asset-renderer/vanilla/26.1/assets/minecraft/textures/gui/sprites/tooltip");

    /** Skips the calling test when the extraction holding the real tooltip sprites is absent. */
    private static void assumeSprites() {
        Assumptions.assumeTrue(Files.isDirectory(TOOLTIP_DIR), "vanilla 26.1 extraction not present");
    }

    /**
     * Reads one tooltip sprite out of the extraction.
     *
     * @param name the sprite's file name below the tooltip sprite directory
     * @return the decoded sprite
     * @throws IOException if the sprite cannot be read
     */
    private static PixelBuffer sprite(String name) throws IOException {
        BufferedImage image = ImageIO.read(TOOLTIP_DIR.resolve(name).toFile());
        return PixelBuffer.wrap(image);
    }

    /**
     * Reads a sprite's GUI scaling out of the sidecar shipped beside it.
     *
     * @param mcmetaName the sidecar's file name below the tooltip sprite directory
     * @return the nine-slice scaling the sidecar declares
     * @throws IOException if the sidecar cannot be read
     */
    private static MCMeta.GuiScaling scaling(String mcmetaName) throws IOException {
        MCMeta meta = MCMeta.parse(Files.readString(TOOLTIP_DIR.resolve(mcmetaName)), new ResourceId("minecraft", "tooltip"));
        return meta.gui().orElseThrow();
    }

    /**
     * Assembles the chrome pair from the real background and frame sprites, failing the test on an
     * unreadable file rather than declaring a checked exception every case would have to thread
     * through. A missing extraction is the {@link #assumeSprites()} skip; a present but unreadable one
     * is a hard failure.
     *
     * @return the resolved chrome sprite pair the render path consumes
     */
    private static TooltipChrome.ChromeSprites realSprites() {
        try {
            return new TooltipChrome.ChromeSprites(
                new ResourceId("minecraft", "gui/sprites/tooltip/background"), sprite("background.png"), scaling("background.png.mcmeta"),
                new ResourceId("minecraft", "gui/sprites/tooltip/frame"), sprite("frame.png"), scaling("frame.png.mcmeta"));
        } catch (IOException ex) {
            throw new AssertionError("Failed to load tooltip sprites", ex);
        }
    }

    /**
     * Builds a one-line LORE tooltip, left unbuilt so each case names its own chrome.
     *
     * @return the partly built text options
     */
    private static TextOptions.TextOptionsBuilder loreBuilder() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(LineSegment.builder().withSegments(ColorSegment.builder().withText("Sprite Chrome").build()).build());
        return TextOptions.builder().style(TextOptions.Style.LORE).lines(lines);
    }

    /**
     * Renders a tooltip and takes its only frame.
     *
     * @param options the text options to render
     * @return the rendered frame
     */
    private static PixelBuffer render(TextOptions options) {
        ImageData image = new TextRenderer().render(options);
        return image.getFrames().getFirst().pixels();
    }

    @Test
    @DisplayName("sprite background: corner notched, fill flush to the canvas edges")
    void notchedCornerAndFlushFill() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("notched top-left corner", ColorMath.alpha(buf.getPixel(0, 0)), is(0));
        assertThat("notched bottom-right corner", ColorMath.alpha(buf.getPixel(buf.width() - 1, buf.height() - 1)), is(0));
        assertThat("fill flush top edge", buf.getPixel(buf.width() / 2, 0), is(0xF0100010));
        assertThat("fill flush left edge", buf.getPixel(0, buf.height() / 2), is(0xF0100010));
    }

    @Test
    @DisplayName("sprite frame: ring 1 mcPx inset, open corners, gradient endpoints")
    void ringInsetAndOpenCorner() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("ring top gradient", buf.getPixel(buf.width() / 2, 2), is(0x505000FF));
        assertThat("ring bottom gradient", buf.getPixel(buf.width() / 2, buf.height() - 3), is(0x5028007F));
        // The ring corner texel is transparent in the sprite (open corner), so the background fill shows
        // through there - unlike PROCEDURAL, whose top/bottom strokes span the full width and paint the
        // ring corner purple.
        assertThat("open ring corner shows background fill", buf.getPixel(2, 2), is(0xF0100010));
    }

    @Test
    @DisplayName("sprite padding 4 shrinks the canvas 4 output px per axis vs procedural padding 5")
    void canvasShrinksWithPadding() {
        assumeSprites();
        PixelBuffer procedural = render(loreBuilder().chrome(TooltipChrome.Vanilla.PROCEDURAL).build());
        PixelBuffer spriteBuf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        // padding 5 -> 4 removes 1 mcPixel per side = 2 mcPixels per axis = 4 output px per axis.
        assertThat("width shrinks 4 px", spriteBuf.width(), is(procedural.width() - 4));
        assertThat("height shrinks 4 px", spriteBuf.height(), is(procedural.height() - 4));
    }

    @Test
    @DisplayName("default alphas leave the sprite bytes untouched (multiplier 1.0)")
    void multiplierNeutrality() {
        assumeSprites();
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).build());

        assertThat("background alpha untouched", ColorMath.alpha(buf.getPixel(buf.width() / 2, 0)), is(0xF0));
        assertThat("ring alpha untouched", ColorMath.alpha(buf.getPixel(buf.width() / 2, 2)), is(0x50));
    }

    @Test
    @DisplayName("lowered background alpha multiplies the sprite alpha proportionally")
    void alphaOverrideMultiplies() {
        assumeSprites();
        // backgroundAlpha 120 / vanilla 240 = 0.5 multiplier -> baked 0xF0 becomes 0x78.
        PixelBuffer buf = render(loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).chromeSprites(Optional.of(realSprites())).backgroundAlpha(120).build());

        int px = buf.getPixel(buf.width() / 2, 0);
        assertThat("halved background alpha", ColorMath.alpha(px), is(0x78));
        assertThat("background rgb untouched", px & 0xFFFFFF, is(0x100010));
    }

    @Test
    @DisplayName("SPRITE chrome without resolved sprites throws rather than silently falling back")
    void missingSpritesThrows() {
        TextOptions options = loreBuilder().chrome(TooltipChrome.Vanilla.SPRITE).build();
        assertThrows(RenderException.class, () -> new TextRenderer().render(options));
    }
}
