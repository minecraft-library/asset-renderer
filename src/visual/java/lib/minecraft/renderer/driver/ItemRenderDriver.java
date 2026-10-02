package lib.minecraft.renderer.driver;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.image.ImageData;
import lib.minecraft.renderer.ItemRenderer;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.ContentException;
import lib.minecraft.renderer.request.ItemModelContext;
import lib.minecraft.renderer.request.ItemOptions;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic task that renders items to PNG files under {@code cache/visual/item-render-2d/} for
 * visual inspection. Defaults to flat 2D GUI sprites ({@link ItemOptions.Type#GUI_2D}); pass
 * {@code -Ptype=held} for the 3D held-item view ({@link ItemOptions.Type#HELD_3D}) or
 * {@code -Ptype=icon} for the faithful inventory icon ({@link ItemOptions.Type#GUI_ICON}), which
 * routes by index membership and is the one mode that answers for every block-backed id;
 * {@code -Ptype=held} draws one whose item definition names its block model. With no
 * {@code -PitemId} it renders {@link #DEFAULT_ITEMS} - a mix of plain items and armor-trim variants
 * that exercises sprite layering and paletted trim permutation.
 * <p>
 * {@code -Psupersample} (SSAA) sharpens the held-item render only - the GUI icon is a sprite blit and
 * ignores it; {@code -PantiAlias} (FXAA) applies to both. Held and icon renders are written with a
 * {@code _held} or {@code _icon} filename suffix so they never overwrite a GUI sprite of the same
 * item.
 * <p>
 * {@code -PhideTextures} forces the named texture ids absent for the run, which is what makes a
 * texture miss reachable at all - a vanilla-only stack resolves everything, and deleting the file on
 * disk only makes the renderer re-extract it.
 * <p>
 * Usage: {@code ./gradlew itemRender2D [-PitemId=minecraft:diamond_sword]
 * [-PrenderSize=256] [-Ptype=gui|held|icon] [-Psupersample=2] [-PantiAlias=true]
 * [-PhideTextures=minecraft:item/stick]}.
 */
@UtilityClass
public final class ItemRenderDriver {

    /** Default item id list when no {@code args[0]} is supplied; mixes plain items with trim variants. */
    private static final String[] DEFAULT_ITEMS = {
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
     *     ({@code held} for {@link ItemOptions.Type#HELD_3D}, {@code icon} for
     *     {@link ItemOptions.Type#GUI_ICON}, otherwise {@link ItemOptions.Type#GUI_2D});
     *     {@code args[5]} is an optional semicolon-separated list of texture ids to force absent,
     *     which is how a texture miss is made reachable on a vanilla-only stack
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        String[] itemIds = args.length > 0
            ? args[0].split(";")
            : DEFAULT_ITEMS;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        int supersample = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        boolean antiAlias = args.length > 3 && Boolean.parseBoolean(args[3]);
        ItemOptions.Type type = resolveType(args.length > 4 ? args[4] : "");
        String[] hidden = args.length > 5 && !args[5].isBlank() ? args[5].split(";") : new String[0];

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ContentException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        RendererContext pipeline = RendererContext.load(result);
        RendererContext context = hidden.length == 0
            ? pipeline
            : pipeline.hiding(hidden);
        ItemRenderer renderer = new ItemRenderer(context);
        Path outputDir = Path.of("cache/visual/item-render-2d");
        Files.createDirectories(outputDir);

        for (String itemId : itemIds) {
            itemId = itemId.trim();
            String safeName = itemId.replace(":", "_") + switch (type) {
                case HELD_3D -> "_held";
                case GUI_ICON -> "_icon";
                default -> "";
            };

            ItemOptions options = ItemOptions.builder()
                .itemId(itemId)
                .type(type)
                .itemModel(callerItemModel(type))
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

    /**
     * Resolves the render type a {@code -Ptype} value names, defaulting to the flat GUI sprite.
     *
     * @param requested the caller's type name, empty when none was passed
     * @return the render mode to dispatch through
     */
    private static ItemOptions.@NotNull Type resolveType(@NotNull String requested) {
        if (requested.equalsIgnoreCase("held")) return ItemOptions.Type.HELD_3D;
        if (requested.equalsIgnoreCase("icon")) return ItemOptions.Type.GUI_ICON;
        return ItemOptions.Type.GUI_2D;
    }

    /**
     * Builds the item-definition evaluation context from the optional {@code asset.item.*} system
     * properties, so the caller-option spot checks can be driven without a rebuild:
     * {@code -Dasset.item.usingItem=true} (bow pulled), {@code -Dasset.item.broken=true} (a damaged
     * elytra), {@code -Dasset.item.trimMaterial=minecraft:gold} (leather trim case),
     * {@code -Dasset.item.time=0.5} (clock frame) and {@code -Dasset.item.compassAngle=0.25} (compass
     * bearing). Absent properties leave every input neutral at the display context the render type
     * draws, which is what the renderer resolves when no context is supplied, so the visual sweep's
     * default run is unaffected. {@code dyeColor}, {@code customModelData} and {@code components}
     * carry no property and stay at the neutral context's own {@code null}.
     *
     * @param type the render type whose display context the context resolves at
     * @return the evaluation context the renders resolve their item trees against
     */
    private static ItemModelContext callerItemModel(ItemOptions.@NotNull Type type) {
        boolean usingItem = Boolean.parseBoolean(System.getProperty("asset.item.usingItem", "false"));
        boolean broken = Boolean.parseBoolean(System.getProperty("asset.item.broken", "false"));
        String trimMaterial = System.getProperty("asset.item.trimMaterial");
        float time = Float.parseFloat(System.getProperty("asset.item.time", "0"));
        float compassAngle = Float.parseFloat(System.getProperty("asset.item.compassAngle", "0"));
        ItemModelContext neutral = ItemModelContext.gui();
        return new ItemModelContext(type.displayContext(), usingItem, broken, trimMaterial,
            neutral.dyeColor(), time, compassAngle, neutral.customModelData(), neutral.components());
    }

}
