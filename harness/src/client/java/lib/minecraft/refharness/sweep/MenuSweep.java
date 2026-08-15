package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.MenuFrameRenderer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Container-screen sweep. Renders each shipped menu through the client's own GUI pipeline, so the
 * reference is the composed screen - panel, labels and slots - rather than the panel's texture.
 *
 * <p>The roster is hand-written and it is the eight menus asset-renderer draws a vanilla shape for:
 * the four row counts one chest sheet composes, and the four screens the client ships whole. It is not
 * derived from a registry, because a menu is not a registry entry - which is also why the target
 * allowlist does not apply: filtering menu names against block and entity ids would render nothing.
 *
 * <p>Every subject is drawn with the player's own section, because that is the panel the client draws
 * and the one both of asset-renderer's gates compare against. The canvas is the screen's <b>drawn</b>
 * extent rather than its declared one - a chest declares a bottom scanline it never paints - so a
 * subject that agreed everywhere would still carry a row of difference if the declared box were used.
 */
public final class MenuSweep implements Sweep<MenuSweep.MenuSubject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * One menu to render.
     *
     * @param name the output stem, which is the subject's whole name because a menu has no namespace
     * @param height the panel's drawn height in Minecraft pixels, which is what the canvas is sized to
     * @param screen builds the screen from the player's inventory and the title
     */
    public record MenuSubject(
        String name, int height,
        BiFunction<Inventory, Component, AbstractContainerScreen<?>> screen
    ) {}

    /** every shipped container panel is nine cells and two margins across */
    private static final int PANEL_WIDTH = 176;

    /** the id every menu here is opened under; nothing reads it, the menus never being synchronised */
    private static final int CONTAINER_ID = 1;

    private final MenuFrameRenderer frameRenderer = new MenuFrameRenderer();

    @Override
    public String outputDir() {
        return "menus";
    }

    @Override
    public List<MenuSubject> enumerate(SweepContext ctx) {
        List<MenuSubject> subjects = List.of(
            new MenuSubject("chest_1row", 131,
                (inv, title) -> new ContainerScreen(ChestMenu.oneRow(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("chest_2row", 149,
                (inv, title) -> new ContainerScreen(ChestMenu.twoRows(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("chest_3row", 167,
                (inv, title) -> new ContainerScreen(ChestMenu.threeRows(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("chest_6row", 221,
                (inv, title) -> new ContainerScreen(ChestMenu.sixRows(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("shulker_box", 166,
                (inv, title) -> new ShulkerBoxScreen(new ShulkerBoxMenu(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("hopper", 133,
                (inv, title) -> new HopperScreen(new HopperMenu(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("dispenser", 166,
                (inv, title) -> new DispenserScreen(new DispenserMenu(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("crafting_table", 166,
                (inv, title) -> new CraftingScreen(new CraftingMenu(CONTAINER_ID, inv), inv, title)));

        LOG.info("MenuSweep built: {} targets", subjects.size());
        return subjects;
    }

    @Override
    public RefKey key(MenuSubject subject) {
        return RefKey.named(subject.name());
    }

    /**
     * The panel's own extent at the GUI scale. There is no fit to carry: a menu is not measured and
     * scaled into a frame the way an entity is - the screen's own layout decides where every pixel
     * goes, and the canvas is only as big as that layout came to.
     */
    @Override
    public Canvas canvas(SweepContext ctx, MenuSubject subject) {
        int scale = MenuFrameRenderer.GUI_SCALE;
        return new Canvas(PANEL_WIDTH * scale, subject.height() * scale, Optional.empty());
    }

    @Override
    public boolean render(SweepContext ctx, MenuSubject subject, Canvas canvas, Path out) throws IOException {
        if (ctx.client().player == null) return false;

        Inventory inventory = ctx.client().player.getInventory();
        AbstractContainerScreen<?> screen =
            subject.screen().apply(inventory, Component.literal(subject.name()));

        return frameRenderer.render(ctx.client(), screen, canvas, out);
    }

    /**
     * A menu name is not a registry id, so the allowlist has nothing to match it against and applying
     * one would render nothing at all.
     */
    @Override
    public boolean honoursTargetFilter() {
        return false;
    }
}
