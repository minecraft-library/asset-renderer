package dev.sbs.renderer.tooling;

import dev.sbs.renderer.TextRenderer;
import dev.sbs.renderer.options.TextOptions;
import dev.sbs.renderer.text.LineSegment;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.codec.webp.WebPWriteOptions;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders a pair of Hypixel SkyBlock-style lore tooltips side by side at
 * {@code sampling=1} and {@code sampling=4} so the effect of supersampling on the gradient
 * border, background alpha, and sub-pixel decoration geometry can be compared directly.
 * <p>
 * The source legacy strings below mirror the style of real tooltips and both include the line
 * {@code +5 ✦ Speed} in white so stat-roll rendering can be eyeballed in isolation.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testLore [-Psamples=1,4]}. Outputs land in
 * {@code cache/test-lore/} as {@code <slug>_ss<factor>.png}.
 */
@UtilityClass
public final class TestLoreMain {

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

    public static void main(String @NotNull [] args) throws IOException {
        int[] samples = parseSamples(args);
        Files.createDirectories(OUTPUT_DIR);

        // Accessory is static - writes a single PNG per sampling factor.
        renderStatic("accessory", ACCESSORY_LEGACY, samples);

        // Weapon carries obfuscated text on its last line, so the renderer produces an
        // animated frame sequence. Emit both GIF and WebP side by side so format-level
        // palette handling and codec wrapping can be A/B compared from a single run.
        renderAnimated("weapon", WEAPON_LEGACY, samples);

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    private static void renderStatic(@NotNull String slug, @NotNull String legacy, int @NotNull [] samples) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        for (int s : samples) {
            TextOptions options = TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(lines)
                .sampling(s)
                .build();

            long t0 = System.nanoTime();
            ImageData image = renderer.render(options);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            File out = OUTPUT_DIR.resolve("%s_ss%d.png".formatted(slug, s)).toFile();
            imageFactory.toFile(image, ImageFormat.PNG, out);
            int w = image.getFrames().getFirst().pixels().width();
            int h = image.getFrames().getFirst().pixels().height();
            System.out.printf("  %s @ ss=%d -> %s (%d ms, %dx%d, 1 frame)%n",
                slug, s, out.getName(), elapsedMs, w, h);
        }
    }

    private static void renderAnimated(@NotNull String slug, @NotNull String legacy, int @NotNull [] samples) throws IOException {
        ConcurrentList<LineSegment> lines = LineSegment.fromLegacy(legacy, '&');
        TextRenderer renderer = new TextRenderer();
        ImageFactory imageFactory = new ImageFactory();

        for (int s : samples) {
            TextOptions options = TextOptions.builder()
                .style(TextOptions.Style.LORE)
                .lines(lines)
                .sampling(s)
                .build();

            long t0 = System.nanoTime();
            ImageData image = renderer.render(options);
            long renderMs = (System.nanoTime() - t0) / 1_000_000L;

            int w = image.getFrames().getFirst().pixels().width();
            int h = image.getFrames().getFirst().pixels().height();
            int frameCount = image.getFrames().size();

            File gifOut = OUTPUT_DIR.resolve("%s_ss%d.gif".formatted(slug, s)).toFile();
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

            File webpOut = OUTPUT_DIR.resolve("%s_ss%d.webp".formatted(slug, s)).toFile();
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
            File webpLossyOut = OUTPUT_DIR.resolve("%s_ss%d_lossy.webp".formatted(slug, s)).toFile();
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
            File webpStaticOut = OUTPUT_DIR.resolve("%s_ss%d_static.webp".formatted(slug, s)).toFile();
            StaticFirstFrame staticImage = new StaticFirstFrame(image.getFrames().getFirst(), image.hasAlpha());
            imageFactory.toFile(
                staticImage,
                ImageFormat.WEBP,
                webpStaticOut,
                WebPWriteOptions.builder().isLossless().build()
            );

            System.out.printf("  %s @ ss=%d -> %s (gif %d ms, webp %d ms, webp-lossy %d ms, render %d ms, %dx%d, %d frames)%n",
                slug, s, gifOut.getName().replace(".gif", ".{gif,webp,webp-lossy}"),
                gifMs, webpMs, webpLossyMs, renderMs, w, h, frameCount);
            System.out.println("    webp-lossy motionSearchThreads sweep:" + mvThreadTable);
        }
    }

    /** One-frame {@link dev.simplified.image.ImageData} wrapping the first frame of an animated render. */
    private record StaticFirstFrame(@NotNull dev.simplified.image.data.ImageFrame frame, boolean alpha)
        implements dev.simplified.image.ImageData {
        @Override public @NotNull dev.simplified.collection.ConcurrentList<dev.simplified.image.data.ImageFrame> getFrames() {
            dev.simplified.collection.ConcurrentList<dev.simplified.image.data.ImageFrame> list = dev.simplified.collection.Concurrent.newList();
            list.add(frame);
            return list;
        }
        @Override public boolean hasAlpha() { return alpha; }
        @Override public int getWidth() { return frame.pixels().width(); }
        @Override public int getHeight() { return frame.pixels().height(); }
        @Override public boolean isAnimated() { return false; }
    }

    private static int[] parseSamples(String @NotNull [] args) {
        if (args.length == 0) return new int[]{ 1, 4 };
        String[] parts = args[0].split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Math.max(1, Integer.parseInt(parts[i].trim()));
        return out;
    }

}
