package lib.minecraft.renderer.driver;

import api.simplified.mojang.exception.MojangApiException;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.ImageData;
import dev.simplified.image.ImageFactory;
import dev.simplified.image.ImageFormat;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.call.request.ItemOptions;
import lib.minecraft.renderer.call.request.MenuOptions;
import lib.minecraft.renderer.call.request.ThemeStyle;
import lib.minecraft.renderer.call.result.RenderResult;
import lib.minecraft.renderer.content.client.ClientAcquisition;
import lib.minecraft.renderer.content.client.ClientAssets;
import lib.minecraft.renderer.content.client.ClientOptions;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.exception.ClientException;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic task that renders menus through {@link MenuRenderer} so a panel can be eyeballed, and
 * every file it writes is a member of the parity store's {@code manifest.visual}, compared byte for
 * byte against its promoted baseline.
 * <p>
 * The roster is what the client cannot be checked against. Each screen the client ships - the four
 * chest row counts one sheet composes, the shulker box, the hopper, the dispenser, the crafting table
 * and the anvil - is a subject of the menu parity sweep, drawn with the player's section and held to
 * the client's own drawing, so it is not drawn here. What is: the anvil bare with its caret declined,
 * because the anvil is the only screen with a text field and a field is the one mark whose content is
 * the caller's; a server-style menu, which is a five-row chest with the caller's own slot map and a
 * filler behind the rest; a crafting table whose enchanted slots promote the whole menu through the
 * compositor's animated branch; a chest in a palette the client ships nothing to compare against; and
 * a panel at a size the client ships no sheet for at all.
 * <p>
 * Usage: {@code ./gradlew menuRender}. Outputs land in {@code cache/visual/menu-render/}.
 */
@UtilityClass
public final class MenuRenderDriver {

    /** Output directory for the menu renders. */
    private static final Path OUTPUT_DIR = Path.of("cache/visual/menu-render");

    /**
     * Loads vanilla's context and renders the menu roster.
     *
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void main(String @NotNull [] args) throws IOException {
        ClientAssets result;
        try {
            result = ClientAcquisition.acquire(ClientOptions.defaults());
        } catch (ClientException | MojangApiException ex) {
            System.err.println("ClientAcquisition bootstrap failed: " + ex.getMessage());
            throw ex;
        }

        run(RendererContext.load(result), args);
    }

    /**
     * Renders the menu roster.
     *
     * @param context the loaded vanilla context
     * @param args ignored
     * @throws IOException if the output directory cannot be created or a render cannot be written
     */
    public static void run(@NotNull RendererContext context, String @NotNull [] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        MenuRenderer renderer = new MenuRenderer(context);

        // The anvil with its field empty and its caret declined, which is the pair of choices no
        // other subject here has to make: a field is the one mark whose content is a caller's and
        // whose caret is a frozen instant of something that blinks.
        write("anvil_bare", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.ANVIL)
            .playerInventory(true)
            .title("Repair & Name")
            .caret(false)
            .build()));

        // A server menu is a chest with the caller's own slot map over it and a filler behind the
        // rest, which is the whole of what makes one: a screen a server dresses is not a shape of its
        // own. These are the positions such a menu puts a crafting grid and its output at.
        write("server_menu", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(5)
            .title("Craft Item")
            .fill(MenuOptions.Fill.of("minecraft:black_stained_glass_pane"))
            .playerInventory(false)
            .slots(slots(
                slot(10, "minecraft:iron_ingot"), slot(11, "minecraft:iron_ingot"), slot(12, "minecraft:iron_ingot"),
                slot(19, "minecraft:iron_ingot"), slot(21, "minecraft:iron_ingot"),
                slot(28, "minecraft:iron_ingot"), slot(29, "minecraft:iron_ingot"), slot(30, "minecraft:iron_ingot"),
                slot(23, "minecraft:barrier"),
                slot(25, "minecraft:diamond_sword")))
            .build()));

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
        write("glinted_crafting", renderer.render(glintedCrafting));

        // The palette that is not vanilla's, on a shape the client ships, so what this is read for is
        // the ink alone - the panel, the two bevels and the cells. It has no client-side counterpart
        // to be checked against, which is why it is looked at rather than compared.
        write("themed_dark", renderer.render(MenuOptions.builder()
            .type(MenuOptions.Type.CHEST)
            .rows(3)
            .playerInventory(true)
            .themeStyle(ThemeStyle.DARK)
            .title("Dark Theme")
            .slots(slots(
                slot(0, "minecraft:diamond"),
                slot(4, "minecraft:iron_ingot"),
                slot(26, "minecraft:diamond_sword")))
            .build()));

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
            .build()));

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
     * @param result the render to write
     * @throws IOException if the file cannot be written
     */
    private static void write(@NotNull String slug, @NotNull RenderResult result) throws IOException {
        ImageData image = result.image();
        // WebP for an animated menu - it stores delays in milliseconds, so a 33 ms glint frame
        // survives the write as itself rather than being rounded onto GIF's centisecond grid.
        ImageFormat format = image.isAnimated() ? ImageFormat.WEBP : ImageFormat.PNG;
        File out = OUTPUT_DIR.resolve(slug + "." + format.name().toLowerCase(Locale.ROOT)).toFile();
        ImageFactory.toFile(image, format, out);
        System.out.printf("  %s -> %s (%dx%d, %d frame%s)%n",
            slug, out.getName(), image.getWidth(), image.getHeight(),
            image.getFrames().size(), image.getFrames().size() == 1 ? "" : "s");
    }

}
