package lib.minecraft.renderer.engine.compose;

import dev.simplified.collection.ConcurrentList;
import lib.minecraft.renderer.MenuRenderer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * What an authored chrome image is made of - four corner rects, four edge bands each repeating a
 * period and carrying the features that break it, and a classified interior.
 * <p>
 * The decomposition is what an assembler needs to rebuild the art at a size its author never drew.
 * Everything in it is in source pixels, so it says nothing about an output scale.
 *
 * @param width the source width
 * @param height the source height
 * @param left the left band's depth
 * @param top the top band's depth
 * @param right the right band's depth
 * @param bottom the bottom band's depth
 * @param bands one band per edge
 * @param interior what the middle is, once the bands are taken off
 * @param interiorPeriodX the interior's repeat period across, meaningful when it tiles
 * @param interiorPeriodY the interior's repeat period down, meaningful when it tiles
 * @param stretchInner whether an unmodelled run resamples when it grows rather than repeating
 */
@Parity(as = MenuRenderer.class, mode = Mode.DEMOTE)
public record ChromeDecomposition(
    int width, int height,
    int left, int top, int right, int bottom,
    @NotNull Map<Edge, Band> bands,
    @NotNull Interior interior,
    int interiorPeriodX, int interiorPeriodY,
    boolean stretchInner
) {

    /**
     * The four edges a band can sit on. {@link #TOP} and {@link #BOTTOM} run across, so their slices
     * are columns; {@link #LEFT} and {@link #RIGHT} run down, so theirs are rows.
     */
    public enum Edge {

        /** The top band, sliced into columns. */
        TOP(true),
        /** The bottom band, sliced into columns. */
        BOTTOM(true),
        /** The left band, sliced into rows. */
        LEFT(false),
        /** The right band, sliced into rows. */
        RIGHT(false);

        /**
         * Whether this band's slices are columns, which is what decides the axis its length is
         * measured on.
         */
        private final boolean across;

        Edge(boolean across) {
            this.across = across;
        }

        /**
         * Returns whether this band runs across the image rather than down it.
         *
         * @return true for the top and bottom bands
         */
        public boolean across() {
            return this.across;
        }

    }

    /**
     * Where a feature stays when the band it sits on changes length.
     */
    public enum Anchor {

        /** Pinned a fixed distance from the band's start. */
        START,
        /** Pinned a fixed distance from the band's end. */
        END,
        /** Held at the band's middle, with a signed nudge off the floor of centre. */
        CENTER

    }

    /**
     * What the region inside the four bands is, which decides how it grows.
     */
    public enum Interior {

        /** One colour throughout. */
        SOLID,
        /** A repeating cell. */
        TILE,
        /** Constant across each row, varying down. */
        GRADIENT_V,
        /** Constant down each column, varying across. */
        GRADIENT_H,
        /** None of the above, so it is resampled or repeated whole. */
        STRETCH

    }

    /**
     * A run of slices a band's period does not explain, together with where it stays when the band
     * grows.
     *
     * @param edge the band this sits on
     * @param offset the slice index the run starts at in the source band
     * @param width the run's length in slices
     * @param anchor which end, if either, the run is pinned to
     * @param place the distance from that end, or for a centred feature the signed nudge off centre
     */
    public record Feature(@NotNull Edge edge, int offset, int width, @NotNull Anchor anchor, int place) {}

    /**
     * One edge band - how long it is, how deep, what it repeats, and what breaks the repeat.
     *
     * @param edge which edge this is
     * @param length the band's length in slices
     * @param depth the band's depth in pixels
     * @param period the repeat length in slices, zero when no repeat was found
     * @param tile one source slice index per residue, empty when there is no period
     * @param features the runs the period does not explain
     */
    public record Band(
        @NotNull Edge edge,
        int length, int depth, int period,
        int @NotNull [] tile,
        @NotNull ConcurrentList<Feature> features
    ) {

        /**
         * Returns whether this band carries any pixels at all, which a zero depth or a zero length
         * denies.
         *
         * @return true when the band is drawable
         */
        public boolean present() {
            return this.depth > 0 && this.length > 0;
        }

    }

    /**
     * A declared nine-slice border, which when present supplies the four band depths outright.
     *
     * @param left the left depth
     * @param top the top depth
     * @param right the right depth
     * @param bottom the bottom depth
     */
    public record Border(int left, int top, int right, int bottom) {

        /**
         * Returns whether the client's own nine-slice validation accepts this border at the given
         * size. It refuses a border whose two opposing depths meet or cross, and a border it refuses
         * never reaches a draw, so one that fails here is not a declaration at all.
         *
         * @param width the sprite width
         * @param height the sprite height
         * @return true when the border loads
         */
        public boolean loadableAt(int width, int height) {
            return this.left + this.right < width && this.top + this.bottom < height;
        }

    }

}
