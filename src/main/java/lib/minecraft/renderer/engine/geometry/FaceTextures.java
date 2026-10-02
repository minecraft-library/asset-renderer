package lib.minecraft.renderer.engine.geometry;

import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
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
 * Suppliers come in three shapes. {@link #uniform} hands one buffer to every face, for a slab that
 * paints one composite on every side. The supplier of a box built upright from a cube's strips - a
 * body part's own skin rectangles in {@code HumanoidPart.textures}, or a worn shell cube's own
 * {@link Unwrap.Atlas#crop crop} under the frame turn its unwrap is authored in, in
 * {@code WornBox.Mesh.textures} - hands each face its crop with {@link Face#DOWN}'s rows reversed,
 * because a face map moves a strip between faces and cannot turn one in its own plane. The cape's,
 * {@code PlayerAssembly.capeTextures}, hands each face its crop with the two cap strips turned half
 * a turn, the in-plane part of the yaw the cape hangs at.
 */
@FunctionalInterface
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
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
     * overlay, banner composite, flat sprite fallback), by the portal's white shading pass and by
     * the missing-model cube, {@code MissingMesh.cube}.
     *
     * @param all the texture every face paints
     * @return a supplier answering {@code all} for all six faces
     */
    static @NotNull FaceTextures uniform(@NotNull PixelBuffer all) {
        return face -> all;
    }

}
