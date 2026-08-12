package lib.minecraft.renderer.visual;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import dev.simplified.image.data.ImageFrame;
import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.engine.compose.TooltipChrome;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.TextOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.LineSegment;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Diagnostic task that renders Hypixel SkyBlock-style lore tooltips end to end through
 * {@link TextRenderer}, so the sprite-backed nine-slice chrome (notched background corners, open
 * gradient-ring corners, 80-row stretched gradient, padding 4), stat rows, obfuscated footer, and
 * codec wrapping can all be eyeballed against real content. The chrome resolves the vanilla
 * {@code tooltip/background} + {@code tooltip/frame} sprites through a real pack-stack context
 * ({@link TooltipChrome.Vanilla#SPRITE}). This is a <b>functional / visual</b> tool ("does it
 * render") - there is no parity gate.
 * <p>
 * Seven tooltips render. Two come from legacy strings: an {@link #ACCESSORY_LEGACY accessory}
 * (static, one PNG) and a {@link #WEAPON_LEGACY weapon} whose obfuscated last line makes
 * {@link TextRenderer} emit an animated frame sequence (GIF + lossless WebP + lossy WebP + a static
 * first-frame WebP). Both mirror the style of real tooltips and include the white
 * {@code +5 ✦ Speed} line so stat-roll rendering can be eyeballed in isolation.
 * <p>
 * The remaining five are gradient sheets, each a tooltip whose lines carry an opt-in per-segment
 * {@link GradientSpec}, so every axis of that feature is eyeballable side by side: the four
 * {@link GradientSpec.Mode modes} at per-letter fidelity (one flat colour per glyph), the same four
 * at per-pixel fidelity in a smooth and a blocky band width, two italic segments whose per-pixel
 * bands shear parallel to the slanted stems, and two scrolling gradients that promote the render to
 * an animated GIF looping seamlessly over one cycle.
 * <p>
 * Usage: {@code ./gradlew loreTooltip}. Takes no {@code -P} flags; outputs land in
 * {@code cache/visual/lore-tooltip/}.
 */
public final class LoreTooltipDriver {

    private LoreTooltipDriver() {}

    /** Output directory for all lore renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/lore-tooltip");

    /**
     * The motion-search thread count that asks the WebP writer for its own default, one thread per
     * available processor - the sweep's baseline, and the timing the summary line reports
     */
    private static final int WRITER_DEFAULT_MOTION_THREADS = -1;

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
     * Runs the test matrix.
     *
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        // Build a renderer context so the sprite-backed tooltip chrome can resolve the vanilla
        // tooltip/background + tooltip/frame nine-slice sprites through the pack stack.
        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            System.exit(1);
            return;
        }
        PipelineRendererContext context = PipelineRendererContext.of(result);
        Optional<TooltipChrome.ChromeSprites> chrome = TooltipChrome.ChromeSprites.resolve(context, null);
        if (chrome.isEmpty()) {
            System.err.println("Default tooltip chrome sprites did not resolve; aborting");
            System.exit(1);
            return;
        }

        // Accessory is static - writes a single PNG.
        renderStatic("accessory", ACCESSORY_LEGACY, chrome.get());

        // Weapon carries obfuscated text on its last line, so the renderer produces an
        // animated frame sequence. Emit both GIF and WebP side by side so format-level
        // palette handling and codec wrapping can be A/B compared from a single run.
        renderAnimated("weapon", WEAPON_LEGACY, chrome.get());

        // Gradient text - opt-in per-segment GradientSpec. Per-letter fidelity
        // (bandPx 0): one flat color per glyph at its advance-span center, all four modes.
        renderGradient("gradient_perletter", gradientPerLetterLines(), chrome.get());

        // Per-pixel fidelity: bandPx 1 (smooth) and bandPx 8 (blocky), same four modes.
        renderGradient("gradient_band1", gradientBandLines(1), chrome.get());
        renderGradient("gradient_band8", gradientBandLines(8), chrome.get());

        // Italic segments with auto shear: per-pixel bands run parallel to the slanted stems.
        renderGradient("gradient_italic_shear", gradientItalicShearLines(), chrome.get());

        // Scrolling gradient: promotes to an animated GIF, one seamless cycle at cycleTicks 40.
        renderGradient("gradient_scroll", gradientScrollLines(), chrome.get());

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders a single-frame tooltip to PNG. Used for static tooltips without obfuscated text.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param legacy ampersand-coded legacy string parsed into {@link LineSegment} tooltip lines
     * @param sprites the resolved sprite chrome pair
     * @throws IOException if the PNG cannot be written
     */
    private static void renderStatic(@NotNull String slug, @NotNull String legacy, @NotNull TooltipChrome.ChromeSprites sprites) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .chrome(TooltipChrome.Vanilla.SPRITE)
            .chromeSprites(Optional.of(sprites))
            .build();

        long t0 = System.nanoTime();
        ImageData image = renderer.render(options);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        File out = OUTPUT_DIR.resolve(slug + ".png").toFile();
        imageFactory.toFile(image, ImageFormat.PNG, out);
        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        System.out.printf("  %s -> %s (%d ms, %dx%d, 1 frame)%n",
            slug, out.getName(), elapsedMs, w, h);
    }

    /**
     * Renders an animated tooltip to GIF + lossless WebP + lossy WebP (with a motion-search
     * thread sweep) + a static first-frame WebP. Used for tooltips that carry obfuscated text
     * so the codec-level palette handling and P-frame motion encoding can be eyeballed.
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param legacy ampersand-coded legacy string parsed into {@link LineSegment} tooltip lines
     * @param sprites the resolved sprite chrome pair
     * @throws IOException if any output file cannot be written
     */
    private static void renderAnimated(@NotNull String slug, @NotNull String legacy, @NotNull TooltipChrome.ChromeSprites sprites) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .chrome(TooltipChrome.Vanilla.SPRITE)
            .chromeSprites(Optional.of(sprites))
            .build();

        long t0 = System.nanoTime();
        ImageData image = renderer.render(options);
        long renderMs = (System.nanoTime() - t0) / 1_000_000L;

        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();

        File gifOut = OUTPUT_DIR.resolve(slug + ".gif").toFile();
        long gifStart = System.nanoTime();
        imageFactory.toFile(
            image,
            ImageFormat.GIF,
            gifOut,
            GifWriteOptions.builder()
                // GIF can't show the tooltip's 240-alpha background as partial transparency,
                // so flatten every pixel onto black before quantizing. Anything that would
                // have looked translucent over the game world gets composited onto the same
                // black letterbox most viewers/chat embeds display behind a GIF.
                .withBackgroundRgb(0x000000)
                .build()
        );
        long gifMs = (System.nanoTime() - gifStart) / 1_000_000L;

        File webpOut = OUTPUT_DIR.resolve(slug + ".webp").toFile();
        long webpStart = System.nanoTime();
        imageFactory.toFile(
            image,
            ImageFormat.WEBP,
            webpOut,
            WebPWriteOptions.builder()
                .isLossless()
                .isMultithreaded()
                .build()
        );
        long webpMs = (System.nanoTime() - webpStart) / 1_000_000L;

        // Lossy VP8 variant - exercises the whole lossy pipeline end-to-end on a
        // real tooltip at full resolution. Sweeps P-frame motion-search parallelism
        // so the effect of {@code motionSearchThreads} on wall-clock encode time
        // is visible run-over-run.
        File webpLossyOut = OUTPUT_DIR.resolve(slug + "_lossy.webp").toFile();
        int[] threadSweep = { 1, 2, 4, WRITER_DEFAULT_MOTION_THREADS };
        long webpLossyMs = -1;
        StringBuilder mvThreadTable = new StringBuilder();
        for (int mvThreads : threadSweep) {
            long start = System.nanoTime();
            imageFactory.toFile(
                image,
                ImageFormat.WEBP,
                webpLossyOut,
                WebPWriteOptions.builder()
                    .isLossless(false)
                    .withQuality(1.0f)
                    .isMultithreaded()
                    .withMotionSearchThreads(mvThreads)
                    .build()
            );
            long ms = (System.nanoTime() - start) / 1_000_000L;
            long fileSize = webpLossyOut.length();
            String label = mvThreads == WRITER_DEFAULT_MOTION_THREADS
                ? "auto=" + Runtime.getRuntime().availableProcessors()
                : "t=" + mvThreads;
            mvThreadTable.append(" [").append(label).append(" ")
                .append(ms).append("ms ").append(fileSize).append("B]");
            if (mvThreads == WRITER_DEFAULT_MOTION_THREADS) webpLossyMs = ms;
        }

        // Also emit the first frame as a static WebP so static-only VP8L issues can
        // be isolated from animation-chunk wrapping issues.
        File webpStaticOut = OUTPUT_DIR.resolve(slug + "_static.webp").toFile();
        StaticFirstFrame staticImage = new StaticFirstFrame(image.getFrames().getFirst(), image.hasAlpha());
        imageFactory.toFile(
            staticImage,
            ImageFormat.WEBP,
            webpStaticOut,
            WebPWriteOptions.builder().isLossless().build()
        );

        System.out.printf("  %s -> %s (gif %d ms, webp %d ms, webp-lossy %d ms, render %d ms, %dx%d, %d frames)%n",
            slug, gifOut.getName().replace(".gif", ".{gif,webp,webp-lossy}"),
            gifMs, webpMs, webpLossyMs, renderMs, w, h, frameCount);
        System.out.println("    webp-lossy motionSearchThreads sweep:" + mvThreadTable);
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
     * Wraps one gradient-carrying {@link ColorSegment} as a single-segment tooltip line.
     *
     * @param text the line text
     * @param spec the gradient spec
     * @param italic whether the segment is italic (drives auto shear on the per-pixel path)
     * @return the line
     */
    private static LineSegment gradientLine(@NotNull String text, @NotNull GradientSpec spec, boolean italic) {
        return LineSegment.builder()
            .withSegments(ColorSegment.builder().withText(text).withGradient(spec).isItalic(italic).build())
            .build();
    }

    /**
     * Renders pre-built gradient tooltip lines. Static gradients write a PNG; a scrolling gradient
     * promotes the render to a frame sequence, written as a GIF (black-flattened, like the weapon).
     *
     * @param slug output filename stem under {@link #OUTPUT_DIR}
     * @param lines the tooltip lines (may carry per-segment gradients)
     * @param sprites the resolved sprite chrome pair
     * @throws IOException if the output cannot be written
     */
    private static void renderGradient(@NotNull String slug, @NotNull ConcurrentList<LineSegment> lines, @NotNull TooltipChrome.ChromeSprites sprites) throws IOException {
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
            .chrome(TooltipChrome.Vanilla.SPRITE)
            .chromeSprites(Optional.of(sprites))
            .build();

        ImageData image = renderer.render(options);
        int w = image.getFrames().getFirst().pixels().width();
        int h = image.getFrames().getFirst().pixels().height();
        int frameCount = image.getFrames().size();

        String name;
        if (image.isAnimated()) {
            File out = OUTPUT_DIR.resolve(slug + ".gif").toFile();
            imageFactory.toFile(image, ImageFormat.GIF, out, GifWriteOptions.builder().withBackgroundRgb(0x000000).build());
            name = out.getName();
        } else {
            File out = OUTPUT_DIR.resolve(slug + ".png").toFile();
            imageFactory.toFile(image, ImageFormat.PNG, out);
            name = out.getName();
        }
        System.out.printf("  %s -> %s (%dx%d, %d frame(s))%n", slug, name, w, h, frameCount);
    }

    /**
     * One-frame {@link ImageData} wrapping the first frame of an animated render, so a lone frame
     * can be re-encoded as a static WebP to isolate VP8L issues from animation-chunk wrapping.
     *
     * @param frame the one frame this image presents, taken off the front of an animated render
     * @param alpha whether that frame carries an alpha channel
     */
    private record StaticFirstFrame(@NotNull ImageFrame frame, boolean alpha) implements ImageData {

        /** {@inheritDoc} */
        @Override
        public @NotNull ConcurrentList<ImageFrame> getFrames() {
            ConcurrentList<ImageFrame> list = Concurrent.newList();
            list.add(frame);
            return list;
        }

        /** {@inheritDoc} */
        @Override
        public boolean hasAlpha() {
            return alpha;
        }

        /** {@inheritDoc} */
        @Override
        public int getWidth() {
            return frame.pixels().width();
        }

        /** {@inheritDoc} */
        @Override
        public int getHeight() {
            return frame.pixels().height();
        }

        /** {@inheritDoc} */
        @Override
        public boolean isAnimated() {
            return false;
        }

    }

}
