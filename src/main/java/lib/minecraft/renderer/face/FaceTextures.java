package lib.minecraft.renderer.face;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * The texture a box paints on each of its six faces, answered one face at a time.
 * <p>
 * A box builder asks for the face it is about to emit, so a supplier names the face it is answering
 * for rather than laying six buffers out in an order the builder has to agree with. That is what
 * closes the one hazard a six-field holder could not: its correctness rested on field order matching
 * the face enum's declaration order, enforced by nothing but a javadoc line, and every one of its
 * construction sites was positional.
 * <p>
 * Suppliers come in three shapes and all three are trivial: {@link #uniform} for a slab that paints
 * one composite on every side, a cube's own {@link Unwrap.Atlas#crop crop} under the frame turn its
 * unwrap is authored in, and a body part's own skin rectangles.
 */
@FunctionalInterface
public interface FaceTextures {

    /**
     * Returns the texture to paint on one face.
     *
     * @param face the face being emitted
     * @return that face's texture
     */
    @NotNull PixelBuffer byFace(@NotNull Face face);

    /**
     * Returns a supplier that paints the same texture on every face - used by item slabs (leather
     * overlay, banner composite, flat sprite fallback) and by the portal's white shading pass.
     *
     * @param all the texture every face paints
     * @return a supplier answering {@code all} for all six faces
     */
    static @NotNull FaceTextures uniform(@NotNull PixelBuffer all) {
        return face -> all;
    }

}
