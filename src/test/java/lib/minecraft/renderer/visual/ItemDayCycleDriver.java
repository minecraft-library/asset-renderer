package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.data.ImageFrame;
import dev.simplified.image.data.StaticImageData;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.asset.pack.item.SunAngle;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.AnimationOptions;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LOOK gate: bakes a whole in-game day for each item whose model tree branches on world time, writing
 * an animated GIF plus sample still frames per item to {@code cache/visual/item-day-cycle/}. What it
 * answers is "does the clock hand sweep, and does it sweep the right way round" - a dispatch table
 * that resolves is not on its own evidence the animation reads naturally.
 *
 * <p>Frame {@code 0} is noon, the instant the item parity references are captured at, so the first
 * still must match {@code cache/.../references/items/minecraft__clock.png}. The sun angle is eased
 * rather than linear, so a uniform sampling cadence lingers on some clock faces and skips others -
 * expect a few repeats across a 64-frame day, and expect the sweep to slow around noon and midnight.
 *
 * <p>The signal to read is how many of a subject's frames are <b>distinct</b>, since a stated frame
 * count says nothing about whether anything moved. Two controls render alongside the clock and must
 * both come out at exactly one distinct frame. The compass is the near miss: its tree has the same
 * shape, and a threshold table of its own, but dispatches on a bearing from the holder rather than on
 * the clock - so a compass that sweeps here means world time leaked into a needle no passage of time
 * turns, and a compass whose frame count gets derived means the search mistook that table for a
 * day. The sword is the plain control: no tree to branch at all.
 *
 * <p>By default each item's frame count is derived from its own dispatch table, so the clock is
 * sampled once per face it ships and an item with nothing time-driven stays a single still.
 * {@code -PdayFrames} states a count outright instead, to sample the sweep finer than the art.
 *
 * <p>Usage: {@code ./gradlew itemDayCycle [-PrenderSize=256] [-PdayFrames=64]}.
 */
@UtilityClass
public final class ItemDayCycleDriver {

    /** The time-driven icon, plus the bearing-driven and plain controls that must not move with it. */
    private static final String[] SUBJECTS = {
        "minecraft:clock",
        "minecraft:compass",
        "minecraft:diamond_sword"
    };

    /** Where each subject's GIF or single still, and the quarter-day sample stills, land. */
    private static final @NotNull Path OUTPUT_DIR = Path.of("cache/visual/item-day-cycle");

    /**
     * Renders each subject across one day and writes the results.
     *
     * @param args {@code args[0]} is an optional render size (defaults to 256); {@code args[1]} is an
     *     optional explicit frame count spanning the day (defaults to deriving it per item)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 256;
        int dayFrames = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ItemRenderer renderer = new ItemRenderer(context);
        ImageFactory imageFactory = new ImageFactory();
        Files.createDirectories(OUTPUT_DIR);

        // By default let each item's own dispatch table set the cadence, which is the path a caller
        // actually uses - "animate this item", without knowing which items are time-driven. An explicit
        // frame count instead states the day outright, for sampling the sweep finer than the art.
        AnimationOptions animation = dayFrames > 0
            ? AnimationOptions.builder()
                .frameCount(dayFrames)
                .ticksPerFrame(Math.max(1, SunAngle.TICKS_PER_DAY / dayFrames))
                .schedule(AnimationOptions.Schedule.GAME_TIME)
                .build()
            : AnimationOptions.builder().deriveTimeline(true).build();

        System.out.printf("Baking a day at %dx%d (%s)...%n", size, size,
            dayFrames > 0 ? dayFrames + " frames, stated" : "frame count derived per item");

        for (String itemId : SUBJECTS) {
            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(size).build())
                .animation(animation)
                .build();

            String safeName = itemId.replace(":", "_");
            try {
                ImageData image = renderer.render(options);
                int frames = image.getFrames().size();
                int distinct = distinctFrames(image);
                boolean moves = distinct > 1;

                // A subject the day never moves collapses to a single still, so a control that stays put
                // is one PNG rather than a GIF of the same frame over and over.
                File out = OUTPUT_DIR.resolve(safeName + (moves ? ".gif" : ".png")).toFile();
                if (moves)
                    imageFactory.toFile(image, ImageFormat.GIF, out, GifWriteOptions.builder().isTransparent().build());
                else
                    imageFactory.toFile(StaticImageData.of(image.getFrames().getFirst().pixels().toBufferedImage()),
                        ImageFormat.PNG, out);

                // Sample stills at the quarter-days, so the sweep can be read without a GIF viewer and
                // the noon frame can be diffed against the parity reference directly.
                if (moves)
                    for (int quarter = 0; quarter < 4; quarter++) {
                        int frame = quarter * frames / 4;
                        File still = OUTPUT_DIR.resolve("%s_f%03d.png".formatted(safeName, frame)).toFile();
                        imageFactory.toFile(StaticImageData.of(image.getFrames().get(frame).pixels().toBufferedImage()),
                            ImageFormat.PNG, still);
                    }

                System.out.printf("  %-22s -> %-28s %2d distinct of %d frames%s%n",
                    itemId, out.getName(), distinct, frames, moves ? "" : "  (static, as a control should be)");
            } catch (Exception ex) {
                System.err.printf("  %-22s FAILED: %s%n", itemId, ex.getMessage());
            }
        }
    }

    /**
     * Counts how many of a bake's frames differ from one another, comparing pixels outright rather
     * than hashing them - this is the number the gate is read on, and an under-count from a collision
     * would read exactly like a subject that failed to animate.
     *
     * @param image the baked frames
     * @return how many frames are pairwise distinct
     */
    private static int distinctFrames(@NotNull ImageData image) {
        List<int[]> seen = new ArrayList<>();
        for (ImageFrame frame : image.getFrames()) {
            BufferedImage pixels = frame.pixels().toBufferedImage();
            int width = pixels.getWidth();
            int[] argb = pixels.getRGB(0, 0, width, pixels.getHeight(), null, 0, width);
            if (seen.stream().noneMatch(other -> Arrays.equals(other, argb))) seen.add(argb);
        }
        return seen.size();
    }

}
