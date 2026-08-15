package lib.minecraft.renderer.engine.compose;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.asset.ResourceId;
import lib.minecraft.renderer.asset.pack.MCMeta;
import lib.minecraft.renderer.engine.RendererContext;
import lib.minecraft.renderer.engine.kit.AnimationKit;
import lib.minecraft.renderer.exception.RenderException;
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
            return context.findGuiScaling(id.id())
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
            int scale = box.scale();

            for (int my = 0; my < painted.height(); my++) {
                for (int mx = 0; mx < painted.width(); mx++) {
                    int argb = painted.getPixel(mx, my);
                    if (argb == 0) continue;

                    int x0 = (box.x() + mx) * scale;
                    int y0 = (box.y() + my) * scale;
                    for (int dy = 0; dy < scale; dy++) {
                        int y = y0 + dy;
                        if (y < 0 || y >= dest.height()) continue;

                        for (int dx = 0; dx < scale; dx++) {
                            int x = x0 + dx;
                            if (x < 0 || x >= dest.width()) continue;
                            dest.setPixel(x, y, argb);
                        }
                    }
                }
            }
        }

    }

    /**
     * Vanilla's container geometry, in one palette per theme.
     * <p>
     * Every constant paints the same pixels and differs only in ink, because the geometry is the
     * measured vanilla one and a theme is a re-inking of it rather than a shape of its own. Nothing
     * here reads a texture.
     */
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

        Theme(@NotNull Palette palette) {
            this.palette = palette;
        }

        /**
         * The ink this theme paints in, which is what anything drawn over its panel matches itself
         * against.
         *
         * @return the palette
         */
        public @NotNull Palette palette() {
            return this.palette;
        }

        /**
         * Side of a frame corner block, and with it the frame's inset, in Minecraft pixels. Three is
         * the visible depth - one line of outline and two of bevel - and the fourth column carries the
         * corner chamfer's last step, which reaches one pixel further in than the straight edge does.
         */
        private static final int BORDER = 4;

        /**
         * Role codes the frame's corner blocks and edge periods are written in: {@code .} leaves the
         * destination untouched, so the chamfered corners show through whatever they are painted over,
         * as vanilla's do.
         */
        private static final char TRANSPARENT = '.', OUTLINE = 'O', LIGHT = 'L', SHADOW = 'S', PANEL = 'P';

        /**
         * The top-left corner block, row by row.
         */
        private static final @NotNull String @NotNull [] TOP_LEFT = { "..OO", ".OLL", "OLLL", "OLLL" };

        /**
         * The top-right corner block, row by row.
         */
        private static final @NotNull String @NotNull [] TOP_RIGHT = { "O...", "LO..", "LPO.", "PSSO" };

        /**
         * The bottom-left corner block, row by row.
         */
        private static final @NotNull String @NotNull [] BOTTOM_LEFT = { "OLLP", ".OPS", "..OS", "...O" };

        /**
         * The bottom-right corner block, row by row.
         */
        private static final @NotNull String @NotNull [] BOTTOM_RIGHT = { "SSSO", "SSSO", "SSO.", "OO.." };

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
            int w = box.width();
            int h = box.height();
            if (w < BORDER * 2 || h < BORDER * 2) return;

            for (int y = BORDER; y < h - BORDER; y++)
                for (int x = BORDER; x < w - BORDER; x++)
                    put(dest, box, x, y, palette.panel());

            for (int x = BORDER; x < w - BORDER; x++) {
                for (int i = 0; i < BORDER; i++) {
                    put(dest, box, x, i, ink(EDGE_NEAR.charAt(i), palette));
                    put(dest, box, x, h - BORDER + i, ink(EDGE_FAR.charAt(i), palette));
                }
            }

            for (int y = BORDER; y < h - BORDER; y++) {
                for (int i = 0; i < BORDER; i++) {
                    put(dest, box, i, y, ink(EDGE_NEAR.charAt(i), palette));
                    put(dest, box, w - BORDER + i, y, ink(EDGE_FAR.charAt(i), palette));
                }
            }

            stamp(dest, box, TOP_LEFT, 0, 0, palette);
            stamp(dest, box, TOP_RIGHT, w - BORDER, 0, palette);
            stamp(dest, box, BOTTOM_LEFT, 0, h - BORDER, palette);
            stamp(dest, box, BOTTOM_RIGHT, w - BORDER, h - BORDER, palette);
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
                    put(dest, box, x, y, palette.cellFill());

            for (int x = 0; x < w - 1; x++) put(dest, box, x, 0, palette.cellShadow());
            for (int y = 0; y < h - 1; y++) put(dest, box, 0, y, palette.cellShadow());
            for (int x = 1; x < w; x++) put(dest, box, x, h - 1, palette.light());
            for (int y = 1; y < h; y++) put(dest, box, w - 1, y, palette.light());
        }

        /** {@inheritDoc} */
        @Override
        public @NotNull Extent minimum() {
            return new Extent(BORDER * 2, BORDER * 2);
        }

        /**
         * Paints one corner block at the given Minecraft-pixel offset within the box.
         */
        private static void stamp(
            @NotNull PixelBuffer dest, @NotNull Box box,
            @NotNull String @NotNull [] block, int originX, int originY,
            @NotNull Palette palette
        ) {
            for (int row = 0; row < block.length; row++)
                for (int col = 0; col < block[row].length(); col++)
                    put(dest, box, originX + col, originY + row, ink(block[row].charAt(col), palette));
        }

        /**
         * Resolves a role code to its ink, answering zero for the code that leaves the destination
         * untouched.
         */
        private static int ink(char role, @NotNull Palette palette) {
            return switch (role) {
                case OUTLINE -> palette.outline();
                case LIGHT -> palette.light();
                case SHADOW -> palette.shadow();
                case PANEL -> palette.panel();
                case TRANSPARENT -> 0;
                default -> throw new IllegalArgumentException("Unknown window role '%c'".formatted(role));
            };
        }

        /**
         * Writes one Minecraft pixel as a {@code scale x scale} block, clipped to the destination.
         * A zero ink writes nothing, so a chamfered corner shows through what it is painted over.
         */
        private static void put(@NotNull PixelBuffer dest, @NotNull Box box, int mcX, int mcY, int argb) {
            if (argb == 0) return;

            int scale = box.scale();
            int x0 = (box.x() + mcX) * scale;
            int y0 = (box.y() + mcY) * scale;

            for (int dy = 0; dy < scale; dy++) {
                int y = y0 + dy;
                if (y < 0 || y >= dest.height()) continue;

                for (int dx = 0; dx < scale; dx++) {
                    int x = x0 + dx;
                    if (x < 0 || x >= dest.width()) continue;
                    dest.setPixel(x, y, argb);
                }
            }
        }

    }

}
