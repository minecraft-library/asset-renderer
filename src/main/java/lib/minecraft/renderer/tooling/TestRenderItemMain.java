package lib.minecraft.renderer.tooling;

import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.exception.AssetPipelineException;
import lib.minecraft.renderer.options.ItemOptions;
import lib.minecraft.renderer.pipeline.AssetPipeline;
import lib.minecraft.renderer.pipeline.AssetPipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import lib.minecraft.renderer.pipeline.client.HttpFetcher;
import dev.simplified.image.ImageData;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders items to PNG files for visual inspection. Defaults to a mix of
 * normal items and trim variants to verify sprite layering and paletted permutation.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:testRenderItem [-PitemId=minecraft:diamond_sword] [-PrenderSize=256]}
 */
@UtilityClass
public final class TestRenderItemMain {

    /** Default item id list when no {@code args[0]} is supplied; mixes plain items with trim variants. */
    private static final String[] ITEM_TEST_1 = {
        "minecraft:diamond_sword",
        "minecraft:iron_chestplate",
        "minecraft:iron_chestplate_amethyst_trim",
        "minecraft:diamond_boots_gold_trim",
        "minecraft:netherite_helmet_redstone_trim",
        "minecraft:golden_apple",
        "minecraft:bow",
        "minecraft:compass"
    };

    /**
     * Runs the item renders.
     *
     * @param args {@code args[0]} is an optional semicolon-separated list of item ids;
     *     {@code args[1]} is an optional render size (defaults to 256)
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String[] itemIds = args.length > 0
            ? args[0].split(";")
            : ITEM_TEST_1;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;

        AssetPipeline.Result result;
        try {
            result = new AssetPipeline(new HttpFetcher()).run(AssetPipelineOptions.defaults());
        } catch (AssetPipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ItemRenderer renderer = new ItemRenderer(context);
        Path outputDir = Path.of("cache/test-render-item");
        Files.createDirectories(outputDir);

        for (String itemId : itemIds) {
            itemId = itemId.trim();
            String safeName = itemId.replace(":", "_");

            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(ItemOptions.Type.GUI_2D)
                .outputSize(size)
                .build();

            System.out.printf("Rendering item %s at %dx%d...%n", itemId, size, size);
            try {
                ImageData image = renderer.render(options);
                File outputFile = outputDir.resolve(safeName + ".png").toFile();
                ImageIO.write(image.toBufferedImage(), "PNG", outputFile);
                System.out.println("Wrote " + outputFile.getAbsolutePath());
            } catch (Exception ex) {
                System.err.println("  FAILED: " + ex.getMessage());
                ex.printStackTrace(System.err);
            }
        }
    }

}
