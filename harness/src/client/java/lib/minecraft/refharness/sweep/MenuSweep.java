package lib.minecraft.refharness.sweep;

import lib.minecraft.refharness.api.Canvas;
import lib.minecraft.refharness.api.RefKey;
import lib.minecraft.refharness.api.Sweep;
import lib.minecraft.refharness.api.SweepContext;
import lib.minecraft.refharness.frame.GuiFrameRenderer;
import lib.minecraft.refharness.mixin.AnvilScreenAccessor;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.parity.Subject;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Container-screen sweep. Renders each shipped menu through the client's own GUI pipeline, so the
 * reference is the composed screen - panel, labels and slots - rather than the panel's texture.
 *
 * <p>The roster is hand-written and it is the fifteen menus asset-renderer draws a vanilla shape or
 * state for: the four row counts one chest sheet composes, the five screens the client ships whole, and
 * the anvil six more times - the six subjects that are states rather than shapes. One has a name set
 * into its field whole, at the fifty characters the field caps it to. Three have slots the client's own
 * menu fills - the sword alone in the first input, the diamond alone in the second, and both with the
 * sword as the result - because the anvil's screen picks its name field by the first input and draws
 * its error cross by the inputs and the result, and between them and the empty anvil every one of those
 * conditions is told apart. One holds the enchanted golden apple alone in the first input, whose name
 * the client's field scrolls by its own steps rather than to the widest tail that fits. And one is
 * named so those steps leave the cursor out of view, the only ground truth for the field's highlight
 * band. It is not derived from a registry, because a menu is not a registry entry - which is also why
 * the target allowlist does not apply: filtering menu names against block and entity ids would render
 * nothing.
 *
 * <p>Every subject is drawn with the player's own section, because that is the panel the client draws
 * and the one both of asset-renderer's gates compare against. The canvas is the screen's <b>drawn</b>
 * extent rather than its declared one - a chest declares a bottom scanline it never paints - so a
 * subject that agreed everywhere would still carry a row of difference if the declared box were used.
 *
 * <p>The capture is the shared {@link GuiFrameRenderer}; laying each screen out at its panel's own
 * size and extracting both halves of it is the step this sweep hands it.
 */
@Parity(claim = "harness-menu-sweep", mode = Mode.DEMOTE, subject = Subject.MENU)
public final class MenuSweep implements Sweep<MenuSweep.MenuSubject> {

    private static final Logger LOG = LoggerFactory.getLogger("refharness");

    /**
     * One menu to render.
     *
     * @param name the output stem, which is the subject's whole name because a menu has no namespace
     * @param height the panel's drawn height in Minecraft pixels, which is what the canvas is sized to
     * @param screen builds the screen from the player's inventory and the title
     * @param afterInit says whatever the subject has to say to a widget the screen's own {@code init}
     * builds, or to its menu once {@code init} has made the screen that menu's slot listener - the only
     * point at which a widget both exists and has not yet been read, and at which the screen hears of
     * a slot its menu fills as the client's screen does
     */
    public record MenuSubject(
        String name, int height,
        BiFunction<Inventory, Component, AbstractContainerScreen<?>> screen,
        Consumer<AbstractContainerScreen<?>> afterInit
    ) {

        /** A subject that is whatever its screen is on being laid out. */
        MenuSubject(String name, int height, BiFunction<Inventory, Component, AbstractContainerScreen<?>> screen) {
            this(name, height, screen, ignored -> {});
        }

    }

    /** every shipped container panel is nine cells and two margins across */
    private static final int PANEL_WIDTH = 176;

    /** the id every menu here is opened under; nothing reads it, the menus never being synchronised */
    private static final int CONTAINER_ID = 1;

    private final GuiFrameRenderer capture = new GuiFrameRenderer("menu");

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
                (inv, title) -> new CraftingScreen(new CraftingMenu(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("anvil", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title)),
            new MenuSubject("anvil_named", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> nameAnvil(screen, ANVIL_NAME)),
            new MenuSubject("anvil_result", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> holdItems(screen, Items.DIAMOND_SWORD, Items.DIAMOND, Items.DIAMOND_SWORD)),
            new MenuSubject("anvil_error", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> holdItems(screen, Items.AIR, Items.DIAMOND, Items.AIR)),
            new MenuSubject("anvil_input", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> holdItems(screen, Items.DIAMOND_SWORD, Items.AIR, Items.AIR)),
            new MenuSubject("anvil_apple", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> holdItems(screen, Items.ENCHANTED_GOLDEN_APPLE, Items.AIR, Items.AIR)),
            new MenuSubject("anvil_highlight", 166,
                (inv, title) -> new AnvilScreen(new AnvilMenu(CONTAINER_ID, inv), inv, title),
                screen -> nameAnvil(screen, HIGHLIGHT_NAME)));

        LOG.info("MenuSweep built: {} targets", subjects.size());
        return subjects;
    }

    /**
     * The name the renamed anvil carries, at the fifty characters {@code AnvilScreen.subInit} caps
     * its field to. It is a pangram with digits on the end rather than a plausible item name, so the
     * one subject that exercises the field's text covers as much of the font as fifty characters
     * can.
     */
    private static final String ANVIL_NAME = "The Quick Brown Fox Jumps Over The Lazy Dog 123456";

    /**
     * The name the highlight anvil carries, which the client's field, set to it whole, scrolls without
     * bringing its cursor into view. Its narrow characters stand ahead of its wide ones, so each of
     * {@code setValue}'s three passes moves the field's start past only as many characters as the head
     * shown from it left over - narrow ones, which free too little room for the wide ones still left -
     * and the head shown after the third still stops short of the end. The field then draws no caret
     * and adds its highlight band from a pixel inside the shown text's end to its right edge. Two passes
     * would show other text and four would bring the cursor into view, so the subject pins the count as
     * well. No vanilla item's name ends out of view, so it is set as the named anvil's is.
     */
    private static final String HIGHLIGHT_NAME = "lil' MAMMOTH WARHAMMER";

    /**
     * Sets an anvil's field to a name whole, through the field's own {@code setValue} - the call the
     * screen names the field through when an item is placed in the first input. It does not type the
     * name, which would scroll the field by other steps.
     *
     * <p>The screen's own responder sends a rename packet on every change, and a subject setting the
     * text is describing what the field shows rather than renaming anything, so it is dropped first.
     * Nothing else about the screen moves: the input slot stays empty, so the field keeps the
     * disabled sprite the unnamed subject draws and the two differ only by the text.
     */
    private static void nameAnvil(AbstractContainerScreen<?> screen, String name) {
        EditBox field = ((AnvilScreenAccessor) screen).refharness$name();
        field.setResponder(ignored -> {});
        field.setValue(name);
    }

    /**
     * Fills an anvil's slots through its own menu, so the screen draws them as the client does with
     * those items placed.
     *
     * <p>Each input is set through its slot, and the menu answers the change as it does in play:
     * {@code ItemCombinerMenu.slotsChanged} reports every changed slot to its listeners through
     * {@code broadcastChanges}, and then runs {@code createResult}. {@code init} made the screen that
     * listener, so the report on the first input is the screen's own {@code slotChanged}, which names
     * the field after the item placed and makes it editable - the field the client then draws enabled.
     * Naming the field runs its responder, which would rename through the menu, run
     * {@code createResult} again and send a rename packet, so the responder is dropped before the
     * first set and the field holds only what {@code slotChanged} put there.
     *
     * <p>The result goes in last, because every input set runs {@code createResult} and none of these
     * inputs makes one. Each leaves the cost at 0 on one of its paths: an empty first input empties the
     * result and sets the cost to 0, as does a repair material against an undamaged first input, and a
     * first input alone, with nothing to repair or combine and no name the menu was told of, counts no
     * work and so costs 0 and makes nothing. The result's own set is reported to the listeners and runs
     * nothing, the menu recomputing for its input container alone. So a filled result stands at a cost
     * of 0, which draws no cost label - a state the menu's own rules never produce, and the one whose
     * field, cross and cells a renderer drawing no cost can be measured against.
     *
     * @param screen the anvil screen, after its {@code init}
     * @param first the item in the first input, air for none
     * @param second the item in the second input, air for none
     * @param result the item in the result slot, air for none
     */
    private static void holdItems(AbstractContainerScreen<?> screen, Item first, Item second, Item result) {
        ((AnvilScreenAccessor) screen).refharness$name().setResponder(ignored -> {});

        AbstractContainerMenu menu = screen.getMenu();
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(new ItemStack(first));
        menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(second));
        menu.getSlot(AnvilMenu.RESULT_SLOT).set(new ItemStack(result));
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
        int scale = GuiFrameRenderer.GUI_SCALE;
        return new Canvas(PANEL_WIDTH * scale, subject.height() * scale, Optional.empty());
    }

    @Override
    public boolean render(SweepContext ctx, MenuSubject subject, Canvas canvas, Path out) throws IOException {
        if (ctx.client().player == null) return false;

        Inventory inventory = ctx.client().player.getInventory();
        AbstractContainerScreen<?> screen =
            subject.screen().apply(inventory, Component.literal(subject.name()));

        return capture.render(ctx.client(), canvas, out, extractor -> {
            // init sizes the screen to the panel itself, which is what makes leftPos and topPos zero.
            screen.init(canvas.width() / GuiFrameRenderer.GUI_SCALE, canvas.height() / GuiFrameRenderer.GUI_SCALE);

            // A screen builds its widgets in init, and an anvil's screen becomes its menu's slot
            // listener there, so anything a subject wants to say to either has to be said here -
            // before the extract reads them and after they exist.
            subject.afterInit().accept(screen);

            // The panel is the screen's background, and it is the caller that draws one - the two
            // halves of a container screen's state are extracted through separate entry points and
            // extractRenderState is only the second of them.
            screen.extractBackground(extractor, -1, -1, 0.0f);
            screen.extractRenderState(extractor, -1, -1, 0.0f);
        });
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
