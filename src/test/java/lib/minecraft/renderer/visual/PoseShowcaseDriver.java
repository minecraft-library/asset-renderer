package lib.minecraft.renderer.visual;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.codec.gif.GifImageWriter;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.data.AnimatedImageData;
import dev.simplified.image.data.FrameBlend;
import dev.simplified.image.data.FrameDisposal;
import dev.simplified.image.data.ImageFrame;
import lib.minecraft.renderer.EntityRenderer;
import lib.minecraft.renderer.asset.Entity;
import lib.minecraft.renderer.author.pose.BuiltStyle;
import lib.minecraft.renderer.author.pose.Ease;
import lib.minecraft.renderer.author.pose.Poses;
import lib.minecraft.renderer.author.pose.Preset;
import lib.minecraft.renderer.author.pose.Side;
import lib.minecraft.renderer.author.pose.StyleRegistrar;
import lib.minecraft.renderer.author.pose.Turn;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.option.EntityOptions;
import lib.minecraft.renderer.option.OutputOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.loader.EntityModelLoader;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Renders the custom-pose cookbook through {@link StyleRegistrar} and {@link EntityRenderer} and
 * writes each subject to {@code cache/visual/pose-showcase/} for visual inspection - a moving
 * style as an animated GIF built from its rendered strip, with each frame keeping the delay the
 * renderer gave it, and a statue as a single PNG.
 * <p>
 * The roster installs on shipped rows: the zombie carries the humanoid animated set (wave, clap,
 * jog, levitate) and statues (dab), the armor stand the silhouette statues (sit, t_pose), the
 * wolf begs, the horse rears, and the allay flutters through the custom tier.
 * <p>
 * Usage: {@code ./gradlew poseShowcase [-PrenderSize=512] [-Ppose=wave]}.
 */
@UtilityClass
public final class PoseShowcaseDriver {

    /** Output directory for the per-pose renders, one GIF or PNG per (entity, style) pair. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/pose-showcase");

    /** Square edge length (pixels) for each render. */
    private static final int DEFAULT_SIZE = 512;

    /**
     * One showcase subject - a built style and the shipped row it installs on.
     *
     * @param entityId the shipped row the style installs on
     * @param style the built style
     */
    private record Showcase(@NotNull String entityId, @NotNull BuiltStyle style) {}

    /**
     * Runs the pose showcase sweep.
     *
     * @param args {@code args[0]} is an optional render size; {@code args[1]} an optional single
     *     style id (blank to render all)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 && !args[0].isBlank() ? Integer.parseInt(args[0]) : DEFAULT_SIZE;
        Optional<String> only = args.length > 1 && !args[1].isBlank() ? Optional.of(args[1]) : Optional.empty();

        Files.createDirectories(OUTPUT_DIR);

        ClientAssets assets = ClientAcquisition.acquire(ClientOptions.defaults());
        PipelineRendererContext context = PipelineRendererContext.of(assets);

        List<Showcase> showcases = showcases().stream()
            .filter(showcase -> only.map(id -> showcase.style().styleId().equals(id)).orElse(true))
            .toList();

        if (showcases.isEmpty()) {
            System.err.printf("No showcase style named '%s'; known ids: %s%n",
                only.orElse(""),
                showcases().stream().map(showcase -> showcase.style().styleId()).toList());
            return;
        }

        ConcurrentMap<String, Entity> pristine = EntityModelLoader.load();
        for (Showcase showcase : showcases) {
            Entity row = pristine.get(showcase.entityId());
            if (row != null)
                System.out.println(showcase.style().validate(row).report());
        }

        StyleRegistrar registrar = StyleRegistrar.ofShipped();
        for (Showcase showcase : showcases)
            registrar.add(showcase.entityId(), showcase.style());
        EntityRenderer renderer = registrar.renderer(context);

        System.out.printf("Rendering %d pose%s at %dx%d to %s%n",
            showcases.size(), showcases.size() == 1 ? "" : "s", size, size, OUTPUT_DIR.toAbsolutePath());

        for (Showcase showcase : showcases) {
            String styleId = showcase.style().styleId();
            String name = showcase.entityId().replace(':', '_') + "_" + styleId;

            try {
                ImageData image = renderer.render(EntityOptions.builder()
                    .entityId(showcase.entityId())
                    .style(styleId)
                    .output(OutputOptions.builder().canvasSize(size).supersample(2).antiAlias(true).build())
                    .build());

                ConcurrentList<ImageFrame> frames = image.getFrames();
                if (frames.size() > 1) {
                    Path out = OUTPUT_DIR.resolve(name + ".gif");
                    writeGif(out, frames);
                    System.out.printf("  %-28s -> %s.gif (%d frames, %d ms each)%n",
                        styleId + " on " + showcase.entityId(), name, frames.size(), frames.getFirst().delayMs());
                } else {
                    Path out = OUTPUT_DIR.resolve(name + ".png");
                    ImageIO.write(image.toBufferedImage(), "PNG", out.toFile());
                    System.out.printf("  %-28s -> %s.png (statue)%n", styleId + " on " + showcase.entityId(), name);
                }
            } catch (Exception ex) {
                System.err.printf("  %-28s FAILED: %s%n", styleId + " on " + showcase.entityId(), ex.getMessage());
            }
        }
    }

    /**
     * The showcase roster - every cookbook style on the shipped row it reads best on.
     */
    private static @NotNull List<Showcase> showcases() {
        return List.of(
            new Showcase("minecraft:zombie", Poses.humanoid("wave")
                .arm(Side.RIGHT, arm -> arm.rotate(-160, 0, 10)
                    .timeline(timeline -> timeline.swing(Turn.ROLL, -20, 20).over(0.6).ease(Ease.SMOOTH)))
                .head(head -> head.yaw(15))
                .build()),
            new Showcase("minecraft:zombie", Poses.humanoid("clap")
                .arms(arm -> arm.pitch(-90).yaw(-10)
                    .timeline(timeline -> timeline.swing(Turn.YAW, 0, -25).over(0.4)))
                .build()),
            new Showcase("minecraft:zombie", Poses.humanoid("jog")
                .keepStride()
                .torso(torso -> torso.pitchBy(12))
                .head(head -> head.pitchBy(-12))
                .arms(arm -> arm.pitchBy(-20))
                .build()),
            new Showcase("minecraft:zombie", Poses.humanoid("levitate")
                .arms(arm -> arm.roll(35))
                .legs(leg -> leg.pitch(-6))
                .hover(8, 2)
                .build()),
            new Showcase("minecraft:zombie", Poses.humanoid("dab")
                .head(head -> head.rotate(30, -35, 0))
                .arm(Side.LEFT, arm -> arm.rotate(-150, -35, 0))
                .arm(Side.RIGHT, arm -> arm.rotate(-160, 35, 0))
                .build()),
            new Showcase("minecraft:armor_stand", Poses.humanoid("sit")
                .preset(Preset.SITTING)
                .container(seat -> seat.offset(0, 7, 0))
                .build()),
            new Showcase("minecraft:armor_stand", Poses.humanoid("t_pose")
                .preset(Preset.T_POSE)
                .allAges()
                .build()),
            new Showcase("minecraft:wolf", Poses.quadruped("beg")
                .body(body -> body.pitch(-40))
                .hindLegs(leg -> leg.pitch(-70))
                .frontLegs(leg -> leg.pitch(-35))
                .head(head -> head.pitch(-15)
                    .timeline(timeline -> timeline.swing(Turn.ROLL, -8, 8).over(1.2).ease(Ease.SMOOTH)))
                .tail(tail -> tail.sway(Turn.YAW, -25, 25))
                .build()),
            new Showcase("minecraft:horse", Poses.quadruped("rear")
                .container(seat -> seat.pitch(-30))
                .frontLegs(leg -> leg.pitch(-65))
                .head(head -> head.pitch(25))
                .tail(tail -> tail.pitch(-30))
                .build()),
            new Showcase("minecraft:allay", Poses.custom("flutter")
                .bone("left_wing", wing -> wing.timeline(timeline -> timeline.swing(Turn.YAW, -50, 10).over(0.3)))
                .bone("right_wing", wing -> wing.timeline(timeline -> timeline.swing(Turn.YAW, 50, -10).over(0.3)))
                .bone("head", head -> head.pitchBy(-8))
                .hover(4, 1)
                .build())
        );
    }

    /**
     * Builds an animated GIF (infinite loop, transparent background) from the rendered frames,
     * each keeping its own delay.
     *
     * <p><b>Each frame clears the canvas behind it rather than painting over what was there.</b>
     * A GIF frame carries its own disposal and the default leaves the previous frame standing -
     * right for an opaque strip, wrong for a transparent one, where every pose the subject has
     * held accumulates behind the current frame and reads as smearing.
     */
    private static void writeGif(@NotNull Path out, @NotNull ConcurrentList<ImageFrame> frames) throws IOException {
        BufferedImage first = frames.getFirst().pixels().toBufferedImage();
        AnimatedImageData.Builder builder = AnimatedImageData.builder()
            .withWidth(first.getWidth())
            .withHeight(first.getHeight())
            .withLoopCount(0);
        for (ImageFrame frame : frames)
            builder.withFrame(ImageFrame.of(frame.pixels(), frame.delayMs(), 0, 0,
                FrameDisposal.RESTORE_TO_BACKGROUND, FrameBlend.SOURCE));

        GifWriteOptions options = GifWriteOptions.builder()
            .withLoopCount(0)
            .isTransparent(true)
            .withAlphaThreshold(8)
            .build();
        Files.write(out, new GifImageWriter().write(builder.build(), options));
    }

}
