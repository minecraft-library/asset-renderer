package lib.minecraft.renderer.driver;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.option.GifWriteOptions;
import dev.simplified.image.option.WebPWriteOptions;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.call.request.ChromeStyle;
import lib.minecraft.renderer.call.request.TextOptions;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.ClientException;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import lib.minecraft.text.TextSegment;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders Hypixel SkyBlock-style lore tooltips end to end through
 * {@link TextRenderer}, so the sprite-backed nine-slice chrome (notched background corners, open
 * gradient-ring corners, 80-row stretched gradient, the canvas ending at the box's first pixel), stat
 * rows, obfuscated footer, and codec wrapping can all be eyeballed against real content. The chrome is
 * the sprite box ({@link ChromeStyle#SPRITE}), the one a renderer holding a context draws a request
 * naming none in, resolving the vanilla {@code tooltip/background} + {@code tooltip/frame} sprites
 * through a real pack-stack context. This is a <b>functional / visual</b> tool ("does it render"), and
 * every file it writes is a member of the parity store's {@code manifest.visual}, compared byte for
 * byte against its promoted baseline.
 * <p>
 * Seven tooltips render. Two come from legacy strings: an {@link #ACCESSORY_LEGACY accessory}
 * (static, one PNG) and a {@link #WEAPON_LEGACY weapon} whose obfuscated last line makes
 * {@link TextRenderer} emit an animated frame sequence (GIF + lossless WebP). Both mirror the style of
 * real tooltips and include the white {@code +5 ✦ Speed} line so stat-roll rendering can be eyeballed
 * in isolation.
 * <p>
 * The remaining five are gradient sheets, each a tooltip whose lines carry an opt-in per-segment
 * {@link GradientSpec}, so every axis of that feature is eyeballable side by side: the four
 * {@link GradientSpec.Mode modes} at per-letter fidelity (one flat colour per glyph), the same four
 * at per-pixel fidelity in a smooth and a blocky band width, two italic segments whose per-pixel
 * bands shear parallel to the slanted stems, and two scrolling gradients that promote the render to
 * an animation looping seamlessly over one cycle, written as a GIF and a lossless WebP like the weapon.
 * <p>
 * Usage: {@code ./gradlew loreTooltip}. Takes no {@code -P} flags; outputs land in
 * {@code cache/visual/lore-tooltip/}.
 */
@UtilityClass
public final class LoreTooltipDriver {

    /** Output directory for all lore renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/lore-tooltip");

    /**
     * A simple accessory-style tooltip with a short effect line. Includes the white
     * {@code +5 ✦ Speed} line to match the weapon tooltip.
     */
    private static final String ACCESSORY_LEGACY = String.join("\n",
        "&6Zombie Talisman",
        "&7Reduces damage taken from zombies",
        "&7by &a10%&7.",
        "",
        "+5 ✦ Speed",
        "",
        "&8This item can be reforged!",
        "&f&lCOMMON ACCESSORY"
    );

    /**
     * A weapon-style tooltip with stat block, ability block, and rarity footer. Includes the
     * white {@code +5 ✦ Speed} line as an extra stat row above the ability block.
     */
    private static final String WEAPON_LEGACY = String.join("\n",
        "&6Aspect of the End",
        "&7Damage: &c+100",
        "&7Strength: &c+100",
        "&f+5 ✦ Speed",
        "",
        "&6Ability: Instant Transmission  &e&lRIGHT CLICK",
        "&7Teleport &a8 blocks &7ahead of you",
        "&7and gain &a+50 ✦ Speed &7for",
        "&a3 seconds&7.",
        "&8Mana Cost: &350",
        "",
        "&9&l&ka &r&9&lRARE SWORD &9&l&ka"
    );

    /**
     * Loads vanilla's context and runs the test matrix.
     *
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        // Build a renderer context so the sprite-backed tooltip chrome can resolve the vanilla
        // tooltip/background + tooltip/frame nine-slice sprites through the pack stack.
        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        run(RendererContext.load(result), args);
    }

    /**
     * Runs the test matrix.
     *
     * @param context the loaded vanilla context, through which the sprite chrome resolves its pair
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        // Accessory is static - writes a single PNG.
        renderStatic("accessory", ACCESSORY_LEGACY, context);

        // Weapon carries obfuscated text on its last line, so the renderer produces an
        // animated frame sequence. Emit both GIF and WebP side by side so the GIF's palette and
        // delay grid can be A/B compared against the lossless frames from a single run.
        renderAnimated("weapon", WEAPON_LEGACY, context);

        // Gradient text - opt-in per-segment GradientSpec. Per-letter fidelity
        // (bandPx 0): one flat color per glyph at its advance-span center, all four modes.
        renderGradient("gradient_perletter", gradientPerLetterLines(), context);

        // Per-pixel fidelity: bandPx 1 (smooth) and bandPx 8 (blocky), same four modes.
        renderGradient("gradient_band1", gradientBandLines(1), context);
        renderGradient("gradient_band8", gradientBandLines(8), context);

        // Italic segments with auto shear: per-pixel bands run parallel to the slanted stems.
        renderGradient("gradient_italic_shear", gradientItalicShearLines(), context);

        // Scrolling gradient: promotes to an animation, one seamless cycle at cycleTicks 40, written
        // as the weapon's GIF and lossless WebP pair.
        renderGradient("gradient_scroll", gradientScrollLines(), context);

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders a single-frame tooltip to PNG. Used for static tooltips without obfuscated text.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param legacy ampersand-coded legacy string parsed into {@link LineSegment} tooltip lines
     * @param context the renderer context the sprite chrome resolves its pair through
     * @throws IOException if the PNG cannot be written
     */
    private static void renderStatic(@NotNull String slug, @NotNull String legacy, @NotNull RendererContext context) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer(context);

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .build();

        long t0 = System.nanoTime();
        ImageData image = renderer.render(options).image();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        File out = OUTPUT_DIR.resolve(slug + ".png").toFile();
        ImageFactory.toFile(image, ImageFormat.PNG, out);
        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        System.out.printf("  %s -> %s (%d ms, %dx%d, 1 frame)%n",
            slug, out.getName(), elapsedMs, w, h);
    }

    /**
     * Renders an animated tooltip to the GIF and lossless WebP pair {@link #writeAnimated} writes.
     * Used for tooltips that carry obfuscated text, so the GIF's palette handling can be eyeballed
     * against frames the lossless WebP keeps exactly.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param legacy ampersand-coded legacy string parsed into {@link LineSegment} tooltip lines
     * @param context the renderer context the sprite chrome resolves its pair through
     * @throws IOException if any output file cannot be written
     */
    private static void renderAnimated(@NotNull String slug, @NotNull String legacy, @NotNull RendererContext context) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer(context);

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .build();

        long t0 = System.nanoTime();
        ImageData image = renderer.render(options).image();
        long renderMs = (System.nanoTime() - t0) / 1_000_000L;

        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();

        long pairStart = System.nanoTime();
        writeAnimated(slug, image);
        long pairMs = (System.nanoTime() - pairStart) / 1_000_000L;

        System.out.printf("  %s -> %s.{gif,webp} (gif+webp %d ms, render %d ms, %dx%d, %d frames)%n",
            slug, slug, pairMs, renderMs, w, h, frameCount);
    }

    /**
     * Writes an animated tooltip as {@code <slug>.gif} and {@code <slug>.webp}. The GIF flattens the
     * frames onto black and quantizes them to one 256-colour palette on a centisecond delay grid; the
     * lossless WebP keeps every frame's colour and its delay in milliseconds exactly. A change to the
     * GIF writer therefore moves only the GIF, and a change to the render moves the WebP, and the GIF
     * as well unless its flatten, palette or delay grid absorbs the move.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param image the animated render
     * @throws IOException if either file cannot be written
     */
    private static void writeAnimated(@NotNull String slug, @NotNull ImageData image) throws IOException {
        ImageFactory.toFile(
            image,
            ImageFormat.GIF,
            OUTPUT_DIR.resolve(slug + ".gif").toFile(),
            GifWriteOptions.builder()
                // GIF can't show the tooltip's 240-alpha background as partial transparency,
                // so flatten every pixel onto black before quantizing. Anything that would
                // have looked translucent over the game world gets composited onto the same
                // black letterbox most viewers/chat embeds display behind a GIF.
                .withBackgroundRgb(0x000000)
                .build()
        );
        ImageFactory.toFile(
            image,
            ImageFormat.WEBP,
            OUTPUT_DIR.resolve(slug + ".webp").toFile(),
            WebPWriteOptions.builder()
                .isLossless()
                .isMultithreaded()
                .build()
        );
    }

    /**
     * The four gradient modes at per-letter fidelity (bandPx 0), one mode per tooltip line.
     *
     * @return the gradient sample lines
     */
    private static ConcurrentList<LineSegment> gradientPerLetterLines() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(gradientLine("Start to End", GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0xFF5555).addStop(0x5555FF).build(), false));
        lines.add(gradientLine("Fire Range Sweep", GradientSpec.builder(GradientSpec.Mode.RANGE)
            .addStop(0xFF0000).addStop(0xFFAA00).addStop(0xFFFF00).build(), false));
        lines.add(gradientLine("Specific Stops", GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
            .addStop(0x55FF55, 0.0f).addStop(0xFFFFFF, 0.5f).addStop(0x55FFFF, 1.0f).build(), false));
        lines.add(gradientLine("Rainbow Legendary", GradientSpec.builder(GradientSpec.Mode.RAINBOW)
            .hueCycles(1f).build(), false));
        return lines;
    }

    /**
     * The four gradient modes at per-pixel fidelity with the given band width.
     *
     * @param bandPx the band width in output px (1 = smooth, larger = blocky)
     * @return the gradient sample lines
     */
    private static ConcurrentList<LineSegment> gradientBandLines(int bandPx) {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(gradientLine("Start to End", GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0xFF5555).addStop(0x5555FF).bandPx(bandPx).build(), false));
        lines.add(gradientLine("Fire Range Sweep", GradientSpec.builder(GradientSpec.Mode.RANGE)
            .addStop(0xFF0000).addStop(0xFFAA00).addStop(0xFFFF00).bandPx(bandPx).build(), false));
        lines.add(gradientLine("Specific Stops", GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
            .addStop(0x55FF55, 0.0f).addStop(0xFFFFFF, 0.5f).addStop(0x55FFFF, 1.0f).bandPx(bandPx).build(), false));
        lines.add(gradientLine("Rainbow Legendary", GradientSpec.builder(GradientSpec.Mode.RAINBOW)
            .hueCycles(1f).bandPx(bandPx).build(), false));
        return lines;
    }

    /**
     * Two italic per-pixel gradients (auto shear) so the sheared bands can be eyeballed parallel to
     * the slanted stems.
     *
     * @return the gradient sample lines
     */
    private static ConcurrentList<LineSegment> gradientItalicShearLines() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        lines.add(gradientLine("Italic Rainbow Slant", GradientSpec.builder(GradientSpec.Mode.RAINBOW)
            .hueCycles(1f).bandPx(1).build(), true));
        lines.add(gradientLine("Italic Range Slant", GradientSpec.builder(GradientSpec.Mode.RANGE)
            .addStop(0xFF0000).addStop(0x00FF00).addStop(0x0000FF).bandPx(1).build(), true));
        return lines;
    }

    /**
     * Two scrolling per-pixel gradients at {@code cycleTicks 40}. Both repeat the first color as the
     * last stop so the sweep loops without a seam.
     *
     * @return the gradient sample lines
     */
    private static ConcurrentList<LineSegment> gradientScrollLines() {
        ConcurrentList<LineSegment> lines = Concurrent.newList();
        GradientSpec.Scroll scroll = new GradientSpec.Scroll(40, GradientSpec.Scroll.Direction.LEFT);
        lines.add(gradientLine("Scrolling Rainbow", GradientSpec.builder(GradientSpec.Mode.RAINBOW)
            .hueCycles(1f).bandPx(1).scroll(scroll).build(), false));
        lines.add(gradientLine("Scrolling Fire Loop", GradientSpec.builder(GradientSpec.Mode.RANGE)
            .addStop(0xFF0000).addStop(0xFFAA00).addStop(0xFFFF00).addStop(0xFF0000).bandPx(1).scroll(scroll).build(), false));
        return lines;
    }

    /**
     * Wraps one gradient-carrying {@link TextSegment} as a single-segment tooltip line.
     *
     * @param text the line text
     * @param spec the gradient spec
     * @param italic whether the segment is italic (drives auto shear on the per-pixel path)
     * @return the line
     */
    private static LineSegment gradientLine(@NotNull String text, @NotNull GradientSpec spec, boolean italic) {
        return LineSegment.builder()
            .segments(Concurrent.newList(TextSegment.builder().text(text).gradient(spec).italic(italic).build()))
            .build();
    }

    /**
     * Renders pre-built gradient tooltip lines. Static gradients write a PNG; a scrolling gradient
     * promotes the render to a frame sequence, written as the weapon's GIF and lossless WebP pair
     * through {@link #writeAnimated}.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param lines the tooltip lines (may carry per-segment gradients)
     * @param context the renderer context the sprite chrome resolves its pair through
     * @throws IOException if the output cannot be written
     */
    private static void renderGradient(@NotNull String slug, @NotNull ConcurrentList<LineSegment> lines, @NotNull RendererContext context) throws IOException {
        TextRenderer renderer = new TextRenderer(context);

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .build();

        ImageData image = renderer.render(options).image();
        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();

        String name;
        if (image.isAnimated()) {
            writeAnimated(slug, image);
            name = slug + ".{gif,webp}";
        } else {
            File out = OUTPUT_DIR.resolve(slug + ".png").toFile();
            ImageFactory.toFile(image, ImageFormat.PNG, out);
            name = out.getName();
        }
        System.out.printf("  %s -> %s (%dx%d, %d frame(s))%n", slug, name, w, h, frameCount);
    }

}
