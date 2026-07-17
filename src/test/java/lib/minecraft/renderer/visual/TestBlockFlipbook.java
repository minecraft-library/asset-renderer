package lib.minecraft.renderer.visual;

import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import dev.simplified.image.codec.gif.GifWriteOptions;
import dev.simplified.image.data.StaticImageData;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.BlockOptions;
import lib.minecraft.renderer.option.spec.AnimationOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LOOK gate: renders the vanilla animated-texture blocks with the animation
 * opted in ({@link AnimationOptions#isDeriveTimeline() deriveTimeline}, the {@code AUTO} path) so each
 * flipbook plays, dumping an animated GIF per block to {@code cache/visual/block-flipbook/}. Diagnostic
 * only ("does the flipbook play"); there is no parity gate here (block defaults stay static/byte-identical
 * - that is what the {@code blockRender3D} sweep proves). A static PNG is written instead when a block's
 * AUTO derivation finds no animated texture (e.g. {@code minecraft:water}, whose animation lives on the
 * fluid renderer, not the block model).
 *
 * <p>{@code water / fire / prismarine / sea_lantern / magma} are
 * animated TEXTURE stems, not block ids. {@code minecraft:water} is a fluid (its flipbook is covered by
 * {@code TestFluidRenderer}'s {@code water_animated_iso}) and {@code minecraft:fire} is not in the block
 * index (a non-item block), so this gate renders the block-index members carrying those animated
 * textures - {@code magma_block} (block/magma, interpolated), {@code prismarine} (interpolated, loop
 * over the 200-tick cap), {@code sea_lantern} (stepped) - plus {@code command_block} (scrolling
 * command texture) for a fourth distinct flipbook.
 *
 * <p>Usage: {@code ./gradlew :asset-renderer:blockFlipbook [-PrenderSize=256]}.
 */
@UtilityClass
public final class TestBlockFlipbook {

    /** Block-index members carrying vanilla animated textures (see the class javadoc for the mapping). */
    private static final String[] FLIPBOOK_BLOCKS = {
        "minecraft:magma_block",
        "minecraft:prismarine",
        "minecraft:sea_lantern",
        "minecraft:command_block"
    };

    private static final @NotNull Path OUTPUT_DIR = Path.of("cache/visual/block-flipbook");

    /**
     * Renders each flipbook block with {@code deriveTimeline} on, writing a GIF (animated) or PNG
     * (derived static) per block.
     *
     * @param args {@code args[0]} is an optional render size (defaults to 256)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 256;

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        BlockRenderer renderer = new BlockRenderer(context);
        ImageFactory imageFactory = new ImageFactory();
        Files.createDirectories(OUTPUT_DIR);

        for (String blockId : FLIPBOOK_BLOCKS) {
            BlockOptions options = BlockOptions.builder()
                .blockId(blockId)
                .type(BlockOptions.Type.ISOMETRIC_3D)
                .output(OutputOptions.builder()
                    .canvasSize(size)
                    .supersample(2)
                    .antiAlias(true)
                    .build())
                // AUTO: probe the block's animated face textures and derive frameCount/ticksPerFrame.
                .animation(AnimationOptions.builder().deriveTimeline(true).build())
                .build();

            try {
                ImageData image = renderer.render(options);
                int frameCount = image.getFrames().size();
                boolean animated = frameCount > 1;
                String extension = animated ? "gif" : "png";
                File out = OUTPUT_DIR.resolve(blockId.replace(":", "_") + "." + extension).toFile();
                if (animated)
                    imageFactory.toFile(image, ImageFormat.GIF, out, GifWriteOptions.builder().isTransparent().build());
                else
                    imageFactory.toFile(image, ImageFormat.PNG, out);
                // Dump three sample still frames (first / middle / last) as PNGs so the flipbook can be
                // LOOK-verified without a GIF viewer - the frames MUST differ for the animation to play.
                if (animated) {
                    int[] samples = {0, frameCount / 2, frameCount - 1};
                    for (int s : samples) {
                        File frameFile = OUTPUT_DIR.resolve("%s_f%03d.png".formatted(blockId.replace(":", "_"), s)).toFile();
                        imageFactory.toFile(StaticImageData.of(image.getFrames().get(s).pixels().toBufferedImage()), ImageFormat.PNG, frameFile);
                    }
                }
                System.out.printf("  %-24s -> %s (%d frame%s)%n",
                    blockId, out.getName(), frameCount, frameCount == 1 ? "" : "s");
            } catch (Exception ex) {
                System.err.println("  FAILED " + blockId + ": " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        }
    }
}
