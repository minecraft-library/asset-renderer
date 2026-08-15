package lib.minecraft.renderer.visual;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.client.ClientAcquisition;
import lib.minecraft.renderer.client.ClientAssets;
import lib.minecraft.renderer.client.ClientOptions;
import lib.minecraft.renderer.engine.compose.Window;
import lib.minecraft.renderer.exception.PipelineException;
import lib.minecraft.renderer.option.ItemOptions;
import lib.minecraft.renderer.option.MenuOptions;
import lib.minecraft.renderer.pipeline.PipelineRendererContext;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic task that renders menus through {@link MenuRenderer} so a panel can be eyeballed. This is
 * a <b>functional / visual</b> tool ("does it render").
 * <p>
 * The roster is every screen the shipped art can be checked against - the four chest row counts one
 * sheet composes, the shulker box, the hopper, the dispenser and the crafting table - each with the
 * player's section drawn, so each names the same panel the client does. Beside them sit four subjects
 * that are about something other than the panel: a server-style menu, which is a six-row chest with
 * the caller's own slot map and a filler behind the rest, a crafting table whose enchanted slots
 * promote the whole menu through the compositor's animated branch, the same chest in a palette the
 * client ships nothing to compare against, and a panel at a size the client ships no sheet for at
 * all.
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

        // One sheet composes every chest row count, and each composition is its own panel - so the
        // four the client can build are four subjects rather than one at four heights. The player's
        // section is armed on each, because that is the panel the client draws.
        for (int rows : new int[] { 1, 2, 3, 6 }) {
            MenuOptions chest = MenuOptions.builder()
                .type(MenuOptions.Type.CHEST)
                .rows(rows)
                .playerInventory(true)
                .title(rows == 6 ? "Large Chest" : "Chest")
                .slots(slots(
                    slot(0, "minecraft:diamond"),
                    slot(4, "minecraft:iron_ingot"),
                    slot(rows * 9 - 1, "minecraft:diamond_sword")))
                .build();
            write("chest_" + rows + "row", renderer.render(chest), imageFactory);
        }

        // The four screens the client ships whole rather than composing. Each carries the title the
        // client gives it, which is what puts a centred one - the dispenser's - on a rendered panel.
        write("shulker_box", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.SHULKER_BOX)
            .playerInventory(true)
            .title("Shulker Box")
            .slots(slots(
                slot(0, "minecraft:diamond"),
                slot(13, "minecraft:iron_ingot"),
                slot(26, "minecraft:diamond_sword")))
            .build()), imageFactory);

        write("hopper", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.HOPPER)
            .playerInventory(true)
            .title("Item Hopper")
            .slots(slots(slot(0, "minecraft:diamond"), slot(4, "minecraft:iron_ingot")))
            .build()), imageFactory);

        write("dispenser", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.DISPENSER)
            .playerInventory(true)
            .title("Dispenser")
            .slots(slots(slot(0, "minecraft:diamond"), slot(4, "minecraft:iron_ingot"), slot(8, "minecraft:diamond")))
            .build()), imageFactory);

        write("crafting_table", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CRAFTING_TABLE)
            .playerInventory(true)
            .title("Crafting")
            .slots(slots(
                slot(0, "minecraft:iron_ingot"), slot(1, "minecraft:iron_ingot"), slot(2, "minecraft:iron_ingot"),
                slot(4, "minecraft:iron_ingot"),
                slot(7, "minecraft:iron_ingot"),
                slot(9, "minecraft:diamond_sword")))
            .build()), imageFactory);

        // A server menu is a chest with the caller's own slot map over it and a filler behind the
        // rest, which is the whole of what makes one: a screen a server dresses is not a shape of its
        // own. These are the positions such a menu puts a crafting grid and its output at.
        write("server_menu", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(6)
            .title("Craft Item")
            .fill(MenuOptions.Fill.of("minecraft:black_stained_glass_pane"))
            .slots(slots(
                slot(10, "minecraft:iron_ingot"), slot(11, "minecraft:iron_ingot"), slot(12, "minecraft:iron_ingot"),
                slot(19, "minecraft:iron_ingot"), slot(21, "minecraft:iron_ingot"),
                slot(28, "minecraft:iron_ingot"), slot(29, "minecraft:iron_ingot"), slot(30, "minecraft:iron_ingot"),
                slot(23, "minecraft:diamond_sword")))
            .build()), imageFactory);

        // An enchanted slot makes its item render animated, which promotes the whole menu through
        // the compositor's animated branch - the one path that reads a child's declared loop length
        // to decide how long the merged loop runs and which child frame each output frame samples.
        MenuOptions glintedCrafting = MenuOptions.builder()
            .type(MenuOptions.Type.CRAFTING_TABLE)
            .title("Enchanting")
            .slots(slots(
                slot(0, "minecraft:diamond"), enchantedSlot(4, "minecraft:diamond_sword"),
                enchantedSlot(9, "minecraft:diamond_sword")))
            .build();
        write("glinted_crafting", renderer.render(glintedCrafting), imageFactory);

        // The palette that is not vanilla's, on a shape one of the eight above already draws, so what
        // this is read for is the ink alone - the panel, the two bevels and the cells. It has no
        // client-side counterpart to be checked against, which is why it is looked at rather than
        // compared.
        write("themed_dark", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(3)
            .playerInventory(true)
            .theme(Window.Theme.DARK)
            .title("Dark Theme")
            .slots(slots(
                slot(0, "minecraft:diamond"),
                slot(4, "minecraft:iron_ingot"),
                slot(26, "minecraft:diamond_sword")))
            .build()), imageFactory);

        // A panel at a size no shipped sheet composes. The frame is four corner blocks and four bars
        // of a one-pixel period, so the same one serves any extent - what is read here is that the
        // corners arrive unstretched, the bars carry their period the whole way, and the cells stay
        // eighteen Minecraft pixels to the far corner. The five populated slots are the grid's four
        // corners and its middle, which is where a lattice that had drifted would show it.
        write("chest_19x13", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(13)
            .columns(19)
            .title("19 x 13")
            .slots(slots(
                slot(0, "minecraft:diamond"),
                slot(18, "minecraft:iron_ingot"),
                slot(123, "minecraft:diamond_sword"),
                slot(228, "minecraft:iron_ingot"),
                slot(246, "minecraft:diamond")))
            .build()), imageFactory);

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
        return Map.entry(index, MenuOptions.MenuSlotContent.of(options));
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
