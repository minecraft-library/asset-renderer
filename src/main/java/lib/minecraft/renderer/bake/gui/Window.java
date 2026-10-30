package lib.minecraft.renderer.bake.gui;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.PixelBuffer;
import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.Resample;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.bake.gui.chrome.ChromeDecomposition;
import lib.minecraft.renderer.bake.gui.chrome.ChromeSlicer;
import lib.minecraft.renderer.call.request.ThemeStyle;
import lib.minecraft.renderer.content.index.RendererContext;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.vanilla.gui.Mark;
import lib.minecraft.renderer.vanilla.gui.ScreenMetrics;
import lib.minecraft.renderer.vanilla.id.ResourceId;
import lib.minecraft.text.font.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.awt.Rectangle;
import java.util.Optional;

/**
 * The chrome of a container panel - its frame, its interior fill and its slot cells - painted at a
 * caller-chosen size.
 * <p>
 * A window speaks Minecraft pixels and reaches output pixels at {@link MinecraftFont#MC_PIXEL_SCALE},
 * the scale the labels and GUI sprites drawn with it are laid at. It lays its own chrome one Minecraft
 * pixel to a block of that side, and the GUI sprites it draws are laid by {@link NineSliceKit} at
 * output-pixel resolution, so a sprite authored at twice its size draws every texel.
 * <p>
 * A window carries its own ink. A {@link Theme} is vanilla's geometry in one of several palettes and
 * a {@link Sliced} is authored art, so neither takes a palette to paint with - one holds a palette
 * and the other is already coloured. Handing one in would mean the arm that cannot honour it
 * ignoring it.
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
public interface Window {

    /**
     * Paints the panel frame and its interior fill over the box.
     *
     * @param dest the buffer to paint into
     * @param box the panel rect in Minecraft pixels
     */
    void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box);

    /**
     * Paints one slot cell over the box.
     *
     * @param dest the buffer to paint into
     * @param box the cell rect in Minecraft pixels
     */
    void paintCell(@NotNull PixelBuffer dest, @NotNull Box box);

    /**
     * Paints one of a screen's marks over the box.
     * <p>
     * A window that draws from rules paints the mark, in its own inks wherever the mark is one the
     * panel owns; a window sliced from art paints the anvil's name field and cross as the GUI sprites
     * the client blits over its art, and no other mark, the arrow, the plus and the hammer being drawn
     * into the art it slices. What a mark's face holds is not painted here - a button's item and a
     * field's text are renders, and the renderer places them.
     *
     * @param dest the buffer to paint into
     * @param box the mark's rect in Minecraft pixels
     * @param decoration which mark to paint
     */
    void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Mark decoration);

    /**
     * Returns the smallest panel this window can paint, in Minecraft pixels - the size at which the
     * frame's own corners meet and the interior is empty. It is a floor the art imposes and never a
     * layout's: a screen with content to fit has its own larger floor, and the usable one is
     * whichever is greater.
     *
     * @return the minimum panel extent
     */
    @NotNull Extent minimum();

    /**
     * A rect in Minecraft pixels, laid at {@link MinecraftFont#MC_PIXEL_SCALE} output pixels to the
     * Minecraft pixel.
     *
     * @param x the left edge, in Minecraft pixels relative to the destination buffer's origin
     * @param y the top edge, in Minecraft pixels relative to the destination buffer's origin
     * @param width the width in Minecraft pixels
     * @param height the height in Minecraft pixels
     */
    record Box(int x, int y, int width, int height) {

        /**
         * A cell's square as a box.
         *
         * @param cell the cell
         * @return the cell box
         */
        public static @NotNull Box of(@NotNull ScreenMetrics.Cell cell) {
            return new Box(cell.x(), cell.y(), cell.size(), cell.size());
        }

        /**
         * A placed mark's rect as a box, at the extent its kind paints.
         *
         * @param placement the placed mark
         * @return the mark's box
         */
        public static @NotNull Box of(@NotNull Mark.Placement placement) {
            Extent extent = Extent.of(placement.kind());
            return new Box(placement.x(), placement.y(), extent.width(), extent.height());
        }

        /**
         * Lays one Minecraft pixel as a block of {@link MinecraftFont#MC_PIXEL_SCALE} output pixels a
         * side, clipped to the destination, as the client lays a GUI texel: an ink of alpha 0 leaves the
         * destination as it was, so a chamfered corner shows through what it is painted over, and any
         * other blends over it with {@link BlendMode#NORMAL}.
         *
         * @param dest the buffer to paint into
         * @param mcX the left edge within this box, in Minecraft pixels
         * @param mcY the top edge within this box, in Minecraft pixels
         * @param argb the colour, alpha 0 to leave the destination alone
         */
        void put(@NotNull PixelBuffer dest, int mcX, int mcY, int argb) {
            int scale = MinecraftFont.MC_PIXEL_SCALE;
            dest.fillRect((this.x + mcX) * scale, (this.y + mcY) * scale, scale, scale, argb, BlendMode.NORMAL);
        }

        /**
         * Draws one ring of a sunken bevel {@code inset} pixels in from this box's edge - the shadow
         * along its top and left, the light along its bottom and right.
         * <p>
         * One rule serves every sunken thing here at whatever depth it is read at: a slot cell is
         * this at zero, and a text field's well is this at zero and again at one in a second pair of
         * inks. The two corners where the shadow hands over to the light are left unwritten, so
         * whatever the ring is drawn over shows through them as it does under the frame's chamfers.
         *
         * @param dest the buffer to paint into
         * @param inset how many Minecraft pixels in from this box's edge the ring sits
         * @param shadow the ink along the top and left
         * @param light the ink along the bottom and right
         */
        void sink(@NotNull PixelBuffer dest, int inset, int shadow, int light) {
            int w = this.width;
            int h = this.height;

            for (int x = inset; x <= w - 2 - inset; x++) put(dest, x, inset, shadow);
            for (int y = inset; y <= h - 2 - inset; y++) put(dest, inset, y, shadow);
            for (int x = inset + 1; x <= w - 1 - inset; x++) put(dest, x, h - 1 - inset, light);
            for (int y = inset + 1; y <= h - 1 - inset; y++) put(dest, w - 1 - inset, y, light);
        }

    }

    /**
     * A size in Minecraft pixels.
     *
     * @param width the width
     * @param height the height
     */
    record Extent(int width, int height) {

        /**
         * The extent a mark paints, which is a property of its kind rather than of the screen
         * carrying it.
         *
         * @param mark the mark
         * @return the extent in Minecraft pixels
         */
        public static @NotNull Extent of(@NotNull Mark mark) {
            return MarkPainter.of(mark).extent();
        }

    }

    /**
     * The six inks a window paints in.
     *
     * @param outline the panel's outermost line
     * @param light the raised bevel, and a cell's lower and right edges
     * @param shadow the sunk bevel
     * @param panel the interior fill behind the cells
     * @param cellFill a cell's interior
     * @param cellShadow a cell's upper and left edges
     */
    record Palette(int outline, int light, int shadow, int panel, int cellFill, int cellShadow) {

        /**
         * The palette the vanilla container textures are drawn in, read off the shipped art.
         */
        public static final @NotNull Palette VANILLA =
            new Palette(0xFF000000, 0xFFFFFFFF, 0xFF555555, 0xFFC6C6C6, 0xFF8B8B8B, 0xFF373737);

        /**
         * A dark palette for the same geometry. Only {@code panel} and {@code cellFill} carry over
         * from anything measured; the three bevel roles are authored, because neither scaling
         * vanilla's ratios nor preserving its differences survives a panel this dark - the first
         * flattens the bevel to a few levels and the second clamps three roles to black.
         */
        public static final @NotNull Palette DARK =
            new Palette(0xFF000000, 0xFF606060, 0xFF141414, 0xFF303030, 0xFF1A1A1A, 0xFF0D0D0D);

    }

    /**
     * A window painted from authored container art rather than by drawing rules.
     * <p>
     * The panel is the art as the client composes it for the screen, and that is the size it is
     * painted at: the regions of its sheet the client blits, and without the player's section those
     * cut after the screen's own rows and closed by their last {@link ScreenMetrics#MARGIN margin}. Its
     * cells are the art's own. For a screen the client ships no sheet for, the panel is the texture at
     * its own size cut to what it draws, which the slicer resizes to the layout. The slicer reassembles
     * art at its own size unchanged, so one path paints both.
     * <p>
     * The cells, and the marks the client draws as widgets over the art - the anvil's name field in the
     * form its first input gives and its cross - are GUI sprites: each is read as the client's GUI atlas
     * holds it and drawn by {@link NineSliceKit} with its {@code gui.scaling}, as the client draws a GUI
     * sprite - a cell over each cell exactly where one is drawn, which is where the caller names one and
     * over a panel resized for a screen the client ships no sheet for, the slicer not moving the art's
     * own cells onto the layout's, and each mark where the screen places it.
     * <p>
     * The art carries its own colours, so this window has no palette. Re-inking sliced art is a
     * different operation from re-inking drawn geometry and is not one this offers.
     *
     * @param panelArt the panel the art draws at its own size
     * @param panel its decomposition
     * @param cellArt the GUI sprite drawn over each cell, empty where no cell is drawn
     * @param cellScaling its {@code gui.scaling}, present exactly when {@code cellArt} is
     * @param markArt the GUI sprite drawn at each mark the screen places that the client blits, keyed by
     *     the mark
     * @param markScaling each one's {@code gui.scaling}, for exactly the marks {@code markArt} holds
     */
    record Sliced(
        @NotNull PixelBuffer panelArt,
        @NotNull ChromeDecomposition panel,
        @NotNull Optional<PixelBuffer> cellArt,
        @NotNull Optional<MCMeta.GuiScaling> cellScaling,
        @NotNull ConcurrentMap<Mark, PixelBuffer> markArt,
        @NotNull ConcurrentMap<Mark, MCMeta.GuiScaling> markScaling
    ) implements Window {

        /**
         * The sprite the client draws a slot with outside container art, drawn over each cell of a panel
         * resized from art where a caller names none.
         */
        private static final @NotNull ResourceId SLOT_SPRITE = ResourceId.parse("minecraft:gui/sprites/container/slot");

        /** The sprite the client blits at an anvil's name field while its first input holds an item. */
        private static final @NotNull ResourceId TEXT_FIELD_SPRITE = ResourceId.parse("minecraft:gui/sprites/container/anvil/text_field");

        /** The sprite the client blits at an anvil's name field while its first input is empty. */
        private static final @NotNull ResourceId TEXT_FIELD_DISABLED_SPRITE = ResourceId.parse("minecraft:gui/sprites/container/anvil/text_field_disabled");

        /**
         * The sprite the client blits over an anvil's arrow while an input holds an item and its result
         * holds none.
         */
        private static final @NotNull ResourceId ERROR_SPRITE = ResourceId.parse("minecraft:gui/sprites/container/anvil/error");

        /**
         * Constructs a new {@code Sliced}, refusing a GUI sprite that comes apart from its scaling.
         *
         * @throws IllegalArgumentException where a sprite comes without its scaling or a scaling without
         *     its sprite
         */
        public Sliced {
            if (cellArt.isPresent() != cellScaling.isPresent())
                throw new IllegalArgumentException("Sliced window carries a '%s' sprite exactly when it carries its scaling".formatted("cell"));
            if (!markArt.keySet().equals(markScaling.keySet()))
                throw new IllegalArgumentException("Sliced window carries sprites for marks '%s' and scalings for '%s'"
                    .formatted(markArt.keySet(), markScaling.keySet()));

            markArt = Concurrent.newUnmodifiableMap(markArt);
            markScaling = Concurrent.newUnmodifiableMap(markScaling);
        }

        /**
         * Decomposes the panel and returns the window over it, with the GUI sprites drawn on it.
         * <p>
         * The panel is container art, which the client blits with no {@code gui} section, so its depths
         * are derived from its pixels. Each GUI sprite is drawn with the scaling given beside it.
         *
         * @param panelArt the panel the art draws at its own size
         * @param cellArt the GUI sprite drawn over each cell, empty where no cell is drawn
         * @param cellScaling its {@code gui.scaling}, present exactly when {@code cellArt} is
         * @param markArt the GUI sprite drawn at each mark the screen places that the client blits, keyed
         *     by the mark
         * @param markScaling each one's {@code gui.scaling}, for exactly the marks {@code markArt} holds
         * @return the window
         * @throws IllegalArgumentException where a sprite comes without its scaling or a scaling without
         *     its sprite
         */
        public static @NotNull Sliced of(
            @NotNull PixelBuffer panelArt,
            @NotNull Optional<PixelBuffer> cellArt, @NotNull Optional<MCMeta.GuiScaling> cellScaling,
            @NotNull ConcurrentMap<Mark, PixelBuffer> markArt, @NotNull ConcurrentMap<Mark, MCMeta.GuiScaling> markScaling
        ) {
            return new Sliced(panelArt, ChromeSlicer.decompose(panelArt, true), cellArt, cellScaling, markArt, markScaling);
        }

        /**
         * Answers the GUI sprite this window draws at a mark, as the client blits it there, and empty
         * for every other mark, none of which this window draws.
         * <p>
         * The switch has no default, so a mark added later does not compile until it says which sprite,
         * if any, is drawn at it.
         *
         * @param mark the mark
         * @return the sprite's id, empty where this window draws nothing at the mark
         */
        private static @NotNull Optional<ResourceId> spriteOf(@NotNull Mark mark) {
            return switch (mark) {
                case FIELD -> Optional.of(TEXT_FIELD_SPRITE);
                case FIELD_DISABLED -> Optional.of(TEXT_FIELD_DISABLED_SPRITE);
                case ERROR -> Optional.of(ERROR_SPRITE);
                case ARROW, BUTTON, PLUS, HAMMER -> Optional.empty();
            };
        }

        /**
         * Resolves named art through the pack stack and composes the panel the client draws from it
         * for the screen.
         * <p>
         * The sheet is read whole, as the client reads a container texture, which it never animates.
         * For a screen the client ships, it is read in the {@link ScreenMetrics#SHEET}-texel space every
         * container blit declares, so a sheet of another size is read at one texel per Minecraft pixel,
         * and the panel is the regions of it the client blits, laid one under the other; without the
         * player's section they are cut after the screen's own rows and closed by their last
         * {@link ScreenMetrics#MARGIN margin}. Either is the size it is painted at, its cells the art's
         * own, and no cell sprite is read unless the caller names one. For a screen the client ships no
         * sheet for, the panel is the texture at its own size cut to what it draws, resized to the
         * layout, and each cell is drawn with the named sprite or the client's slot.
         * <p>
         * A sheet no pack supplies, or that cannot be read, is the checkerboard read the same way, as
         * vanilla draws its missing texture for one it cannot load. It never falls back to the vanilla
         * panel, because a caller naming one has stated an intent a silently vanilla panel would not
         * honour - absence and failure are different states and only the first is expressible, by naming
         * nothing. The panel's sidecar is not read, the client reading none for a container blit.
         * <p>
         * The cell sprite, and the sprite of each mark the screen places that the client blits over the
         * art - the anvil's name field in the form its first input gives and its cross - are read after
         * the panel, in the order the screen places them, through {@link NineSliceKit}'s GUI-sprite read,
         * as the client's GUI atlas holds them, so a sprite no pack supplies, one that cannot be read and
         * one whose {@code gui} section the client's codec refuses is the missing texture, stretched.
         *
         * @param context the renderer context resolving textures and their sidecars
         * @param panelId the container sheet
         * @param cellId the sprite drawn over each cell, empty for the window's own - none on a screen
         *     the client ships, its slot on any other
         * @param screen the screen whose regions the sheet is composed from
         * @param layout the panel the window paints, which says whether the player's section is drawn
         * @return the window over the resolved art
         */
        public static @NotNull Sliced resolve(
            @NotNull RendererContext context, @NotNull ResourceId panelId, @NotNull Optional<ResourceId> cellId,
            @NotNull ScreenMetrics screen, @NotNull MenuLayout layout
        ) {
            PixelBuffer sheet = context.withMissingTexture().resolveTexture(panelId.id()).get();
            PixelBuffer panel = compose(sheet, screen, layout);
            Optional<ResourceId> cell = cellId.or(() -> screen.regions().isEmpty()
                ? Optional.of(SLOT_SPRITE)
                : Optional.empty());
            Optional<PixelBuffer> cellArt = cell.map(id -> NineSliceKit.sprite(context, id));
            ConcurrentMap<Mark, PixelBuffer> markArt = Concurrent.newMap();
            ConcurrentMap<Mark, MCMeta.GuiScaling> markScaling = Concurrent.newMap();
            for (Mark.Placement mark : screen.marks()) {
                spriteOf(mark.kind()).ifPresent(id -> {
                    PixelBuffer art = NineSliceKit.sprite(context, id);
                    markArt.put(mark.kind(), art);
                    markScaling.put(mark.kind(), NineSliceKit.scaling(context, id, art));
                });
            }

            return of(panel,
                cellArt, cellArt.map(art -> NineSliceKit.scaling(context, cell.orElseThrow(), art)),
                markArt, markScaling);
        }

        /**
         * Composes a container sheet into the panel the client draws from it for the screen.
         * <p>
         * A screen with no regions takes the texture at its own size cut to the pixels it draws, which
         * the slicer resizes to the layout. For any other, the texture is first read into the
         * {@link ScreenMetrics#SHEET}-texel space the client's blits declare. Each of the screen's
         * regions is then copied verbatim under the one before, clipped to the panel the screen draws
         * with the player's section, which drops a declared row its art leaves clear. The panel keeps
         * that composition's rows down to the layout's bottom margin and closes on the composition's
         * own, which is the whole of it where the player's section is drawn and cuts it after the
         * screen's own rows where it is not.
         *
         * @param texture the container sheet as resolved
         * @param screen the screen whose regions the sheet is composed from
         * @param layout the panel the window paints
         * @return the panel art the window slices
         */
        private static @NotNull PixelBuffer compose(
            @NotNull PixelBuffer texture, @NotNull ScreenMetrics screen, @NotNull MenuLayout layout
        ) {
            if (screen.regions().isEmpty()) return texture.trim();

            PixelBuffer sheet = texture.resize(ScreenMetrics.SHEET, ScreenMetrics.SHEET, Resample.NEAREST);
            MenuLayout drawn = MenuLayout.of(screen, true);
            PixelBuffer composed = PixelBuffer.create(drawn.width(), drawn.height());
            int y = 0;
            for (Rectangle region : screen.regions()) {
                composed.blit(sheet.crop(region.x, region.y, region.width, region.height), 0, y, BlendMode.REPLACE);
                y += region.height;
            }

            int open = layout.height() - ScreenMetrics.MARGIN;
            PixelBuffer panel = PixelBuffer.create(layout.width(), layout.height());
            panel.blit(composed.crop(0, 0, layout.width(), open), 0, 0, BlendMode.REPLACE);
            panel.blit(composed.crop(0, composed.height() - ScreenMetrics.MARGIN, layout.width(), ScreenMetrics.MARGIN),
                0, open, BlendMode.REPLACE);
            return panel;
        }

        /**
         * {@inheritDoc}
         * <p>
         * The art, reassembled at the box's size, is laid one Minecraft pixel to a block of
         * {@link MinecraftFont#MC_PIXEL_SCALE} output pixels, each as the client lays a GUI texel: alpha 0
         * leaves the destination as it was and the rest blends with {@link BlendMode#NORMAL}.
         */
        @Override
        public void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box) {
            int scale = MinecraftFont.MC_PIXEL_SCALE;
            PixelBuffer art = ChromeSlicer.assemble(this.panel, this.panelArt, box.width(), box.height());
            dest.blitScaled(art, box.x() * scale, box.y() * scale, art.width() * scale, art.height() * scale, BlendMode.NORMAL);
        }

        /** {@inheritDoc} */
        @Override
        public void paintCell(@NotNull PixelBuffer dest, @NotNull Box box) {
            this.cellArt.ifPresent(art ->
                NineSliceKit.draw(dest, art, this.cellScaling.orElseThrow(), box.x(), box.y(), box.width(), box.height(), 1.0f));
        }

        /**
         * {@inheritDoc}
         * <p>
         * The GUI sprite the client blits at the mark - the anvil's name field, in the form its first
         * input gives, over the flat-red hole the sheet leaves for it, and its cross over the arrow - and
         * nothing at any other mark, the arrow, the plus and the hammer being drawn into the art: a second
         * arrow here would be drawn over the first.
         */
        @Override
        public void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Mark decoration) {
            this.markArt.getOptional(decoration).ifPresent(art ->
                NineSliceKit.draw(dest, art, this.markScaling.get(decoration), box.x(), box.y(), box.width(), box.height(), 1.0f));
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Extent minimum() {
            return new Extent(this.panel.left() + this.panel.right(), this.panel.top() + this.panel.bottom());
        }

    }

    /**
     * Vanilla's container geometry, in one palette per theme.
     * <p>
     * Every constant here paints the same pixels and differs only in ink, because the geometry is
     * the measured vanilla one and a theme is a re-inking of it rather than a shape of its own.
     * Nothing here reads a texture.
     * <p>
     * Four marks are drawn in inks the theme does not own, and each carries its own rather than
     * borrowing a role: a {@link Mark#HAMMER} and the anvil's {@link Mark#ERROR} cross are pictures,
     * and the inside of either name field, {@link Mark#FIELD} or {@link Mark#FIELD_DISABLED}, is an
     * input widget's. None is something a panel would have drawn itself, which is the line - what
     * re-inks is what the panel owns.
     */
    @Getter(style = NamingStyle.FLUENT)
    @RequiredArgsConstructor
    enum Theme implements Window {

        /**
         * The palette the shipped container textures are drawn in. Reproduces them pixel for pixel:
         * the frame's four corner blocks and four one-pixel edge periods are the same in every
         * container texture that ships, and a cell's edges follow one rule at every cell size.
         */
        VANILLA(Palette.VANILLA),
        /**
         * A dark re-inking of the same geometry.
         */
        DARK(Palette.DARK);

        /**
         * The ink this theme paints the shared geometry in.
         */
        private final @NotNull Palette palette;

        /**
         * Role codes the frame's corner blocks and edge periods are written in. The code leaving the
         * destination untouched belongs to {@link Stencil}, so nothing here declares one.
         */
        private static final char OUTLINE = 'O', LIGHT = 'L', SHADOW = 'S', PANEL = 'P';

        /**
         * The top-left corner block.
         */
        private static final @NotNull Stencil TOP_LEFT = Stencil.of("..OO", ".OLL", "OLLL", "OLLL");

        /**
         * The top-right corner block.
         */
        private static final @NotNull Stencil TOP_RIGHT = Stencil.of("O...", "LO..", "LPO.", "PSSO");

        /**
         * The bottom-left corner block.
         */
        private static final @NotNull Stencil BOTTOM_LEFT = Stencil.of("OLLP", ".OPS", "..OS", "...O");

        /**
         * The bottom-right corner block.
         */
        private static final @NotNull Stencil BOTTOM_RIGHT = Stencil.of("SSSO", "SSSO", "SSO.", "OO..");

        /**
         * Side of a frame corner block, and with it the frame's inset, in Minecraft pixels. Three is
         * the visible depth - one line of outline and two of bevel - and the fourth column carries the
         * corner chamfer's last step, which reaches one pixel further in than the straight edge does.
         * It is read off a corner rather than declared, so it cannot disagree with one.
         */
        private static final int BORDER = TOP_LEFT.columns();

        /**
         * The top edge's one-pixel period, outermost first. The left edge repeats the same run, and the
         * bottom and right edges repeat it reversed, which is what makes the panel read as raised.
         */
        private static final @NotNull String EDGE_NEAR = "OLLP";

        /**
         * The bottom edge's one-pixel period, outermost last - {@link #EDGE_NEAR} reversed onto the
         * shadow.
         */
        private static final @NotNull String EDGE_FAR = "PSSO";

        /**
         * Returns the theme a style selects.
         *
         * @param style the style a caller names
         * @return the theme painting vanilla's geometry in that style's palette
         */
        public static @NotNull Theme of(@NotNull ThemeStyle style) {
            return switch (style) {
                case VANILLA -> VANILLA;
                case DARK -> DARK;
            };
        }

        /** {@inheritDoc} */
        @Override
        public void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box) {
            Palette palette = this.palette;
            Stencil.Ink ink = code -> ink(code, palette);
            int w = box.width();
            int h = box.height();
            if (w < BORDER * 2 || h < BORDER * 2) return;

            for (int y = BORDER; y < h - BORDER; y++)
                for (int x = BORDER; x < w - BORDER; x++)
                    box.put(dest, x, y, palette.panel());

            for (int x = BORDER; x < w - BORDER; x++) {
                for (int i = 0; i < BORDER; i++) {
                    box.put(dest, x, i, ink.of(EDGE_NEAR.charAt(i)));
                    box.put(dest, x, h - BORDER + i, ink.of(EDGE_FAR.charAt(i)));
                }
            }

            for (int y = BORDER; y < h - BORDER; y++) {
                for (int i = 0; i < BORDER; i++) {
                    box.put(dest, i, y, ink.of(EDGE_NEAR.charAt(i)));
                    box.put(dest, w - BORDER + i, y, ink.of(EDGE_FAR.charAt(i)));
                }
            }

            TOP_LEFT.stamp(dest, box, 0, 0, ink);
            TOP_RIGHT.stamp(dest, box, w - BORDER, 0, ink);
            BOTTOM_LEFT.stamp(dest, box, 0, h - BORDER, ink);
            BOTTOM_RIGHT.stamp(dest, box, w - BORDER, h - BORDER, ink);
        }

        /** {@inheritDoc} */
        @Override
        public void paintCell(@NotNull PixelBuffer dest, @NotNull Box box) {
            Palette palette = this.palette;
            int w = box.width();
            int h = box.height();
            if (w <= 0 || h <= 0) return;

            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    box.put(dest, x, y, palette.cellFill());

            box.sink(dest, 0, palette.cellShadow(), palette.light());
        }

        /** {@inheritDoc} */
        @Override
        public void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Mark decoration) {
            MarkPainter.of(decoration).paint(dest, box, this.palette);
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Extent minimum() {
            return new Extent(BORDER * 2, BORDER * 2);
        }

        /**
         * Resolves one of the frame's role codes to its ink.
         * <p>
         * This is the table the panel's own pictures are stamped through, and the palette is the data
         * it reads rather than the ink itself - which is what lets a picture drawn in colours no
         * palette holds go through the same stamp.
         */
        private static int ink(char code, @NotNull Palette palette) {
            return switch (code) {
                case OUTLINE -> palette.outline();
                case LIGHT -> palette.light();
                case SHADOW -> palette.shadow();
                case PANEL -> palette.panel();
                default -> throw new IllegalArgumentException("Unknown window role '%c'".formatted(code));
            };
        }

    }

}
