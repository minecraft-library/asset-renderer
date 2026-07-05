package lib.minecraft.renderer.visual;

import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.spec.OutputOptions;
import lib.minecraft.renderer.pipeline.Pipeline;
import lib.minecraft.renderer.pipeline.PipelineOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import dev.simplified.image.ImageData;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders items to PNG files under {@code cache/visual/item-render-2d/} for
 * visual inspection. Defaults to flat 2D GUI sprites ({@link ItemOptions.Type#GUI_2D}); pass
 * {@code -Ptype=held} for the 3D held-item view ({@link ItemOptions.Type#HELD_3D}). With no
 * {@code -PitemId} it renders {@link #ITEM_TEST_1} - a mix of plain items and armor-trim variants
 * that exercises sprite layering and paletted trim permutation.
 * <p>
 * {@code -Psupersample} (SSAA) sharpens the held-item render only - the GUI icon is a sprite blit and
 * ignores it; {@code -PantiAlias} (FXAA) applies to both. Held renders are written with a
 * {@code _held} filename suffix so they never overwrite a GUI icon of the same item.
 * <p>
 * Usage: {@code ./gradlew :asset-renderer:itemRender2D [-PitemId=minecraft:diamond_sword]
 * [-PrenderSize=256] [-Ptype=gui|held] [-Psupersample=2] [-PantiAlias=true]}.
 */
@UtilityClass
public final class TestItemRender2D {

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
     *     {@code args[1]} is an optional render size (defaults to 256); {@code args[2]} is an
     *     optional supersample factor (defaults to 1, held items only); {@code args[3]} is an
     *     optional FXAA flag (defaults to false); {@code args[4]} is an optional render type
     *     ({@code held} for {@link ItemOptions.Type#HELD_3D}, otherwise {@link ItemOptions.Type#GUI_2D})
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String[] itemIds = args.length > 0
            ? args[0].split(";")
            : ITEM_TEST_1;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int supersample = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        boolean antiAlias = args.length > 3 && Boolean.parseBoolean(args[3]);
        ItemOptions.Type type = args.length > 4 && args[4].equalsIgnoreCase("held")
            ? ItemOptions.Type.HELD_3D
            : ItemOptions.Type.GUI_2D;

        Pipeline.Result result;
        try {
            result = Pipeline.run(PipelineOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("Pipeline bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        PipelineRendererContext context = PipelineRendererContext.of(result);
        ItemRenderer renderer = new ItemRenderer(context);
        Path outputDir = Path.of("cache/visual/item-render-2d");
        Files.createDirectories(outputDir);

        for (String itemId : itemIds) {
            itemId = itemId.trim();
            String safeName = itemId.replace(":", "_")
                + (type == ItemOptions.Type.HELD_3D ? "_held" : "");

            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(type)
                .output(ItemOptions.DEFAULT_OUTPUT.mutate().canvasSize(size).supersample(supersample).antiAlias(antiAlias).build())
                .build();

            System.out.printf("Rendering item %s (%s) at %dx%d (ssaa=%d, fxaa=%b)...%n",
                itemId, type, size, size, supersample, antiAlias);
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
