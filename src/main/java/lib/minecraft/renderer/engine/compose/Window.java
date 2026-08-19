package lib.minecraft.renderer.engine.compose;

import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The chrome of a container panel - its frame, its interior fill and its slot cells - painted at a
 * caller-chosen size.
 * <p>
 * A window speaks Minecraft pixels. {@link Box#scale()} is the only place output pixels enter, and
 * every implementation replicates one Minecraft pixel as a {@code scale x scale} block rather than
 * resampling, so chrome is exact at every integer scale.
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
     * @param box the panel rect in Minecraft pixels, with its output scale
     */
    void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box);

    /**
     * Paints one slot cell over the box.
     *
     * @param dest the buffer to paint into
     * @param box the cell rect in Minecraft pixels, with its output scale
     */
    void paintCell(@NotNull PixelBuffer dest, @NotNull Box box);

    /**
     * Paints one of a screen's marks over the box.
     * <p>
     * A window that draws from rules paints the mark, in its own inks wherever the mark is one the
     * panel owns; a window sliced from art paints nothing, because whatever marks that art carries
     * are already in the panel it slices. What a mark's face holds is not painted here - a button's
     * item and a field's text are renders, and the renderer places them.
     *
     * @param dest the buffer to paint into
     * @param box the mark's rect in Minecraft pixels, with its output scale
     * @param decoration which mark to paint
     */
    void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Decoration decoration);

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
     * A rect in Minecraft pixels together with the output scale it is painted at.
     *
     * @param x the left edge, in Minecraft pixels relative to the destination buffer's origin
     * @param y the top edge, in Minecraft pixels relative to the destination buffer's origin
     * @param width the width in Minecraft pixels
     * @param height the height in Minecraft pixels
     * @param scale the output pixels each Minecraft pixel occupies on a side
     */
    record Box(int x, int y, int width, int height, int scale) {

        /**
         * A box at the origin at scale one.
         *
         * @param width the width in Minecraft pixels
         * @param height the height in Minecraft pixels
         * @return the box
         */
        public static @NotNull Box of(int width, int height) {
            return new Box(0, 0, width, height, 1);
        }

        /**
         * Writes one Minecraft pixel as a {@code scale x scale} block, clipped to the destination.
         * A zero ink writes nothing, so a chamfered corner shows through what it is painted over.
         *
         * @param dest the buffer to paint into
         * @param mcX the left edge within this box, in Minecraft pixels
         * @param mcY the top edge within this box, in Minecraft pixels
         * @param argb the colour, zero to leave the destination alone
         */
        void put(@NotNull PixelBuffer dest, int mcX, int mcY, int argb) {
            if (argb == 0) return;

            int x0 = (this.x + mcX) * this.scale;
            int y0 = (this.y + mcY) * this.scale;

            for (int dy = 0; dy < this.scale; dy++) {
                int y = y0 + dy;
                if (y < 0 || y >= dest.height()) continue;

                for (int dx = 0; dx < this.scale; dx++) {
                    int x = x0 + dx;
                    if (x < 0 || x >= dest.width()) continue;
                    dest.setPixel(x, y, argb);
                }
            }
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
    record Extent(int width, int height) {}

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
     * A window painted by slicing authored art rather than by drawing rules.
     * <p>
     * The panel and the cell are two chrome images and go through the same decomposition, so a pack
     * supplying a bordered panel and an eighteen-pixel slot gets both resized by one mechanism. A
     * panel with no cell art paints none, which is what a menu whose slots are drawn into the panel
     * already wants.
     * <p>
     * The art carries its own colours, so this window has no palette. Re-inking sliced art is a
     * different operation from re-inking drawn geometry and is not one this offers.
     *
     * @param panelArt the panel image
     * @param panel its decomposition
     * @param cellArt the cell image, empty when the art draws its own cells
     * @param cell the cell's decomposition, present exactly when {@code cellArt} is
     */
    record Sliced(
        @NotNull PixelBuffer panelArt,
        @NotNull ChromeDecomposition panel,
        @NotNull Optional<PixelBuffer> cellArt,
        @NotNull Optional<ChromeDecomposition> cell
    ) implements Window {

        /**
         * Decomposes the art and returns the window over it.
         *
         * @param panelArt the panel image
         * @param panelBorder the border the panel's sidecar declares, empty when it has none
         * @param cellArt the cell image, empty when the art draws its own cells
         * @param cellBorder the border the cell's sidecar declares, empty when it has none
         * @return the window
         */
        public static @NotNull Sliced of(
            @NotNull PixelBuffer panelArt, @NotNull Optional<ChromeDecomposition.Border> panelBorder,
            @NotNull Optional<PixelBuffer> cellArt, @NotNull Optional<ChromeDecomposition.Border> cellBorder
        ) {
            return new Sliced(
                panelArt, ChromeSlicer.decompose(panelArt, panelBorder, true),
                cellArt, cellArt.map(art -> ChromeSlicer.decompose(art, cellBorder, true)));
        }

        /**
         * Resolves named art through the pack stack and decomposes it once.
         * <p>
         * A named sprite that does not resolve raises, because a caller naming one has stated an
         * intent that a silently vanilla panel would not honour - absence and failure are different
         * states and only the first is expressible, by naming nothing. Each sprite is paired with
         * whatever border its own sidecar declares, so authored art keeps its authored border and
         * only art declaring none has one derived.
         *
         * @param context the renderer context resolving textures and their sidecars
         * @param panelId the panel sprite
         * @param cellId the cell sprite, empty where the panel art draws its own cells
         * @return the window over the resolved art
         * @throws RenderException if either named sprite does not resolve
         */
        public static @NotNull Sliced resolve(
            @NotNull RendererContext context,
            @NotNull ResourceId panelId,
            @NotNull Optional<ResourceId> cellId
        ) {
            return of(
                art(context, panelId), border(context, panelId),
                cellId.map(id -> art(context, id)), cellId.flatMap(id -> border(context, id)));
        }

        /**
         * Resolves one sprite's pixels, pinned to tick zero where the art is animated, and raises
         * where the pack stack answers with nothing.
         */
        private static @NotNull PixelBuffer art(@NotNull RendererContext context, @NotNull ResourceId id) {
            PixelBuffer buffer = context.resolveTexture(id.id())
                .orElseThrow(() -> new RenderException("Window chrome sprite '%s' does not resolve", id));

            return context.findAnimation(id.id())
                .map(animation -> AnimationKit.sampleFrame(buffer, animation, 0))
                .orElse(buffer);
        }

        /**
         * The border a sprite's {@code gui.scaling} sidecar declares, empty where it declares none or
         * declares a mode that carries no border - which is what leaves the border to be derived.
         */
        private static @NotNull Optional<ChromeDecomposition.Border> border(
            @NotNull RendererContext context, @NotNull ResourceId id
        ) {
            return context.findMeta(id.id())
                .flatMap(MCMeta::gui)
                .filter(scaling -> scaling.type() == MCMeta.GuiScaling.Type.NINE_SLICE)
                .map(MCMeta.GuiScaling::border)
                .map(declared -> new ChromeDecomposition.Border(
                    declared.left(), declared.top(), declared.right(), declared.bottom()));
        }

        /** {@inheritDoc} */
        @Override
        public void paintPanel(@NotNull PixelBuffer dest, @NotNull Box box) {
            blit(dest, ChromeSlicer.assemble(this.panel, this.panelArt, box.width(), box.height()), box);
        }

        /** {@inheritDoc} */
        @Override
        public void paintCell(@NotNull PixelBuffer dest, @NotNull Box box) {
            if (this.cellArt.isEmpty() || this.cell.isEmpty()) return;
            blit(dest, ChromeSlicer.assemble(this.cell.get(), this.cellArt.get(), box.width(), box.height()), box);
        }

        /**
         * {@inheritDoc}
         * <p>
         * Nothing, because a panel sliced from art already carries whatever marks that art draws -
         * the shipped crafting panel has its arrow painted into the texture, so a second one here
         * would be drawn over the first.
         */
        @Override
        public void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Decoration decoration) {}

        /** {@inheritDoc} */
        @Override
        public @NotNull Extent minimum() {
            return new Extent(this.panel.left() + this.panel.right(), this.panel.top() + this.panel.bottom());
        }

        /**
         * Blits a painted Minecraft-pixel image into the box, replicating each pixel as a
         * {@code scale x scale} block and skipping a fully transparent one so the destination shows
         * through.
         */
        private static void blit(@NotNull PixelBuffer dest, @NotNull PixelBuffer painted, @NotNull Box box) {
            for (int my = 0; my < painted.height(); my++)
                for (int mx = 0; mx < painted.width(); mx++)
                    box.put(dest, mx, my, painted.getPixel(mx, my));
        }

    }

    /**
     * Vanilla's container geometry, in one palette per theme.
     * <p>
     * Every constant here paints the same pixels and differs only in ink, because the geometry is
     * the measured vanilla one and a theme is a re-inking of it rather than a shape of its own.
     * Nothing here reads a texture.
     * <p>
     * Two marks are drawn in inks the theme does not own, and both carry their own rather than
     * borrowing a role: a {@link Decoration#HAMMER} is a picture, and the inside of a
     * {@link Decoration#FIELD} is an input widget's. Neither is something a panel would have drawn
     * itself, which is the line - what re-inks is what the panel owns.
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
        public void paintDecoration(@NotNull PixelBuffer dest, @NotNull Box box, @NotNull Decoration decoration) {
            decoration.paint(dest, box, this.palette);
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
