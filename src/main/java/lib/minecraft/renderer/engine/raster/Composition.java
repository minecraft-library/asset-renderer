package lib.minecraft.renderer.engine.raster;

import dev.simplified.image.pixel.BlendMode;
import dev.simplified.image.pixel.ColorMath;

/**
 * How a surviving fragment's colour is combined with what the frame buffer already holds.
 * <p>
 * Vanilla decides this per render pipeline rather than per texture. A pipeline declaring a blend
 * function composites against the destination; one declaring none writes each surviving fragment
 * over the destination verbatim, alpha included, which is what a cutout pipeline does. That second
 * case is {@link #REPLACE}, and it is the absence of a blend rather than another kind of one - which
 * is why it is modelled here beside the blends instead of as a {@link BlendMode}.
 * <p>
 * {@link #NORMAL} and {@link #REPLACE} agree on every fragment whose alpha is {@code 0} or
 * {@code 255}: the source-over blend returns the source unchanged at full alpha, and a fully
 * transparent fragment is discarded before it reaches the composite. They can therefore differ only
 * where a texel carries partial alpha or an overlay scales its alpha down - which is why declaring a
 * pass {@code cutout} is free for the overwhelming majority of geometry.
 */
public enum Composition {

    /** Source-over - the fragment is blended into the destination by its own alpha. */
    NORMAL,

    /** Additive - the fragment's colour is added to the destination, brightening it. */
    ADDITIVE,

    /** Porter-Duff source - the fragment replaces the destination outright, alpha included. */
    REPLACE;

    /**
     * Composites one fragment over the pixel already in the frame buffer.
     *
     * @param source the incoming fragment, already tinted, shaded and alpha-scaled
     * @param destination the pixel currently in the frame buffer
     * @return the composited pixel
     */
    public int composite(int source, int destination) {
        return switch (this) {
            case NORMAL -> ColorMath.blend(source, destination, BlendMode.NORMAL);
            case ADDITIVE -> ColorMath.blend(source, destination, BlendMode.ADD);
            case REPLACE -> source;
        };
    }
}
