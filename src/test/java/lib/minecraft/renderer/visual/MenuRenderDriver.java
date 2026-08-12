package lib.minecraft.renderer.visual;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.pipeline.ClientAcquisition;
import lib.minecraft.renderer.pipeline.ClientAssets;
import lib.minecraft.renderer.pipeline.ClientOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic task that renders the vanilla-style chest chrome menus through {@link MenuRenderer} so
 * the chest-slot chrome can be eyeballed. Covers the three menus that draw the gray "vanilla chest"
 * chrome ({@code drawVanillaChestChrome}): a {@link MenuOptions.Type#SKYBLOCK_CRAFTING} 9x6 chest with
 * a filled border, a {@link MenuOptions.Type#VANILLA_CRAFTING} 3x3 table, and a second 3x3 table whose
 * enchanted slots promote the whole menu through the compositor's animated branch. This is a
 * <b>functional / visual</b> tool ("does it render") - there is no parity gate.
 * <p>
 * Usage: {@code ./gradlew menuRender}. Outputs land in {@code cache/visual/menu-render/}.
 */
public final class MenuRenderDriver {

    private MenuRenderDriver() {}

    /** Output directory for the menu renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/menu-render");

    /**
     * Renders the chest-chrome menu matrix.
     *
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (PipelineException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            System.exit(1);
            return;
        }
        PipelineRendererContext context = PipelineRendererContext.of(result);
        MenuRenderer renderer = new MenuRenderer(context);
        ImageFactory imageFactory = new ImageFactory();

        MenuOptions skyblockCrafting = MenuOptions.builder()
            .type(MenuOptions.Type.SKYBLOCK_CRAFTING)
            .title("Craft Item")
            .fill(MenuOptions.Fill.BLACK_STAINED_GLASS_PANE)
            .slots(slots(
                slot(0, "minecraft:iron_ingot"), slot(1, "minecraft:iron_ingot"), slot(2, "minecraft:iron_ingot"),
                slot(3, "minecraft:iron_ingot"), slot(5, "minecraft:iron_ingot"),
                slot(6, "minecraft:iron_ingot"), slot(7, "minecraft:iron_ingot"), slot(8, "minecraft:iron_ingot"),
                slot(9, "minecraft:diamond_sword")))
            .build();
        write("skyblock_crafting", renderer.render(skyblockCrafting), imageFactory);

        MenuOptions vanillaCrafting = MenuOptions.builder()
            .type(MenuOptions.Type.VANILLA_CRAFTING)
            .title("Crafting")
            .slots(slots(
                slot(0, "minecraft:diamond"), slot(4, "minecraft:stick"), slot(7, "minecraft:stick"),
                slot(9, "minecraft:diamond_sword")))
            .build();
        write("vanilla_crafting", renderer.render(vanillaCrafting), imageFactory);

        // An enchanted slot makes its item render animated, which promotes the whole menu through
        // the compositor's animated branch - the one path that reads a child's declared loop length
        // to decide how long the merged loop runs and which child frame each output frame samples.
        MenuOptions glintedCrafting = MenuOptions.builder()
            .type(MenuOptions.Type.VANILLA_CRAFTING)
            .title("Enchanting")
            .slots(slots(
                slot(0, "minecraft:diamond"), enchantedSlot(4, "minecraft:diamond_sword"),
                enchantedSlot(9, "minecraft:diamond_sword")))
            .build();
        write("glinted_crafting", renderer.render(glintedCrafting), imageFactory);

        System.out.println("Done. Outputs in " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Builds a slot holding a foil item, the render that carries a glint animation.
     *
     * @param index the slot index within the menu
     * @param itemId the item id to draw in the slot
     * @return the slot entry
     */
    private static Map.Entry<Integer, MenuOptions.MenuSlotContent> enchantedSlot(int index, String itemId) {
        ItemOptions options = ItemOptions.builder()
            .itemId(itemId)
            .type(ItemOptions.Type.GUI_ICON)
            .enchanted(true)
            .build();
        return Map.entry(index, new MenuOptions.MenuSlotContent(itemId, options, 1));
    }

    /**
     * Builds a slot holding one plain item at its default icon options.
     *
     * @param index the slot index within the menu
     * @param itemId the item id to draw in the slot
     * @return the slot entry
     */
    private static Map.Entry<Integer, MenuOptions.MenuSlotContent> slot(int index, String itemId) {
        return Map.entry(index, MenuOptions.MenuSlotContent.of(itemId));
    }

    /**
     * Collects slot entries into the map the menu options take.
     *
     * @param entries the slot entries, in any order
     * @return the populated slot map
     */
    @SafeVarargs
    private static ConcurrentMap<Integer, MenuOptions.MenuSlotContent> slots(
        Map.Entry<Integer, MenuOptions.MenuSlotContent>... entries) {
        ConcurrentMap<Integer, MenuOptions.MenuSlotContent> map = Concurrent.newMap();
        for (Map.Entry<Integer, MenuOptions.MenuSlotContent> entry : entries)
            map.put(entry.getKey(), entry.getValue());
        return map;
    }

    /**
     * Writes one render, choosing the container format from whether it animated.
     *
     * @param slug the output file stem
     * @param image the render to write
     * @param imageFactory the encoder
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull String slug, @NotNull ImageData image, @NotNull ImageFactory imageFactory) throws IOException {
        // WebP for an animated menu - it stores delays in milliseconds, so a 33 ms glint frame
        // survives the write as itself rather than being rounded onto GIF's centisecond grid.
        ImageFormat format = image.isAnimated() ? ImageFormat.WEBP : ImageFormat.PNG;
        File out = OUTPUT_DIR.resolve(slug + "." + format.name().toLowerCase(Locale.ROOT)).toFile();
        imageFactory.toFile(image, format, out);
        System.out.printf("  %s -> %s (%dx%d, %d frame%s)%n",
            slug, out.getName(), image.getWidth(), image.getHeight(),
            image.getFrames().size(), image.getFrames().size() == 1 ? "" : "s");
    }

}
