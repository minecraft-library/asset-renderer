package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.TextRenderer;
import lib.minecraft.renderer.options.TextOptions;
import lib.minecraft.text.LineSegment;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import dev.simplified.image.data.ImageFrame;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders a pair of Hypixel SkyBlock-style lore tooltips end to end, so
 * the gradient border, background alpha, stat rows, obfuscated footer, and codec wrapping can
 * all be eyeballed against real content.
 * <p>
 * The source legacy strings below mirror the style of real tooltips and both include the line
 * {@code +5 ✦ Speed} in white so stat-roll rendering can be eyeballed in isolation.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testLore}. Outputs land in {@code cache/test-lore/}.
 */
@UtilityClass
public final class TestLoreMain {

    /** Output directory for all lore renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/test-lore");

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

        // Accessory is static - writes a single PNG.
        renderStatic("accessory", ACCESSORY_LEGACY);

        // Weapon carries obfuscated text on its last line, so the renderer produces an
        // animated frame sequence. Emit both GIF and WebP side by side so format-level
        // palette handling and codec wrapping can be A/B compared from a single run.
        renderAnimated("weapon", WEAPON_LEGACY);

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Renders a single-frame tooltip to PNG. Used for static tooltips without obfuscated text.
     */
    private static void renderStatic(@NotNull String slug, @NotNull String legacy) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
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
     */
    private static void renderAnimated(@NotNull String slug, @NotNull String legacy) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        TextOptions options = TextOptions.builder()
            .style(TextOptions.Style.LORE)
            .lines(lines)
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
        int[] threadSweep = { 1, 2, 4, -1 };   // -1 = writer default (availableProcessors)
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
            String label = mvThreads == -1
                ? "auto=" + Runtime.getRuntime().availableProcessors()
                : "t=" + mvThreads;
            mvThreadTable.append(" [").append(label).append(" ")
                .append(ms).append("ms ").append(fileSize).append("B]");
            if (mvThreads == -1) webpLossyMs = ms;        // record "default" time for summary line
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

    /** One-frame {@link ImageData} wrapping the first frame of an animated render. */
    private record StaticFirstFrame(@NotNull ImageFrame frame, boolean alpha) implements ImageData {
        @Override public @NotNull ConcurrentList<ImageFrame> getFrames() {
            ConcurrentList<ImageFrame> list = Concurrent.newList();
            list.add(frame);
            return list;
        }
        @Override public boolean hasAlpha() { return alpha; }
        @Override public int getWidth() { return frame.pixels().width(); }
        @Override public int getHeight() { return frame.pixels().height(); }
        @Override public boolean isAnimated() { return false; }
    }

}
