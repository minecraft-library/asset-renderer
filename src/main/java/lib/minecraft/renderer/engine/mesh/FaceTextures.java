package lib.minecraft.renderer.engine.mesh;

import dev.simplified.image.PixelBuffer;
import lib.minecraft.renderer.engine.draw.SampledTexture;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.geometry.Unwrap;
import lib.minecraft.renderer.engine.math.Vector4f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The texture a box paints on each of its six faces, answered one face at a time as the region of a
 * texture that face reads.
 * <p>
 * A box builder asks for the face it is about to emit, so a supplier names the face it is answering
 * for rather than laying six buffers out in an order the builder has to agree with. That is what
 * closes the one hazard a six-field holder could not: its correctness rested on field order matching
 * the face enum's declaration order, enforced by nothing but a javadoc line, and every one of its
 * construction sites was positional.
 * <p>
 * Each face is a {@link Region} - a rectangle of a whole texture, read through that texture's own
 * sampler - which is how the client lays a cube face: its four corners carry the rectangle's corners
 * as normalised texture coordinates, and every pixel reads the sheet where its interpolated coordinate
 * lands, the neighbouring texel past an edge included.
 * <p>
 * Suppliers come in three shapes. {@link #uniform} hands the whole of one buffer to every face, for a
 * slab that paints one composite on every side. The supplier of a box built upright from a cube's
 * strips - a body part's own skin rectangles in {@code HumanoidPart.textures}, or a worn shell cube's
 * own {@link Unwrap.Atlas#read strip} under the frame turn its unwrap is authored in, in
 * {@code WornBox.Mesh.textures} - hands {@link Face#DOWN} its rectangle with the rows reversed,
 * because a face map moves a strip between faces and cannot turn one in its own plane. The cape's,
 * {@code PlayerAssembly.capeTextures}, hands the two cap strips their rectangles turned half a turn,
 * the in-plane part of the yaw the cape hangs at.
 */
@FunctionalInterface
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
public interface FaceTextures {

    /**
     * Returns the region of a texture one face paints.
     *
     * @param face the face being emitted
     * @return that face's region
     */
    @NotNull Region byFace(@NotNull Face face);

    /**
     * Returns a supplier that paints the whole of one buffer on every face, read nearest and repeating -
     * used by item slabs (leather overlay, banner composite, flat sprite fallback), by the portal's
     * white shading pass and by the missing-model cube, {@code MissingMesh.cube}.
     *
     * @param all the buffer every face paints
     * @return a supplier answering the whole of {@code all} for all six faces
     */
    static @NotNull FaceTextures uniform(@NotNull PixelBuffer all) {
        Region whole = Region.whole(SampledTexture.of(all));
        return face -> whole;
    }

    /**
     * A rectangle of one texture a face reads, in the texture's normalised coordinates.
     * <p>
     * The rectangle is held as the coordinate at the face's left edge, at its top edge, at its right
     * edge and at its bottom edge, in that order, so a face read right to left or bottom up carries a
     * descending pair rather than a flag: a mirrored cube's face, the {@link Face#DOWN} strip a box built
     * upright reverses, and the cape's turned cap strips. The raster interpolates whichever way the pair
     * runs, as the client's polygon does.
     *
     * @param texture the whole texture, with the sampler it is read through
     * @param uv the face's left, top, right and bottom coordinates, normalised to the texture's size
     */
    record Region(@NotNull SampledTexture texture, @NotNull Vector4f uv) {

        /**
         * Answers the whole of a texture, left to right and top down.
         *
         * @param texture the texture
         * @return the region spanning {@code [0, 1]} on both axes
         */
        public static @NotNull Region whole(@NotNull SampledTexture texture) {
            return new Region(texture, new Vector4f(0f, 0f, 1f, 1f));
        }

        /**
         * Answers a pixel rectangle of a sheet, normalised by the sheet size its model declares.
         * <p>
         * Each coordinate is divided by the declared size on its own, as the client's cube divides each
         * corner's texel coordinate, so a texture of another size than the one declared is read whole,
         * stretched across the same rectangle.
         *
         * @param texture the whole sheet, with the sampler it is read through
         * @param pixels the rectangle as {@code (left, top, right, bottom)} in the declared sheet's texels
         * @param sheetWidth the sheet width the model declares, in texels
         * @param sheetHeight the sheet height the model declares, in texels
         * @param reverseU whether the face reads the rectangle right to left
         * @param reverseV whether the face reads the rectangle bottom up
         * @return the face's region
         */
        public static @NotNull Region of(
            @NotNull SampledTexture texture,
            @NotNull Vector4f pixels,
            float sheetWidth,
            float sheetHeight,
            boolean reverseU,
            boolean reverseV
        ) {
            float u0 = pixels.x() / sheetWidth;
            float v0 = pixels.y() / sheetHeight;
            float u1 = pixels.z() / sheetWidth;
            float v1 = pixels.w() / sheetHeight;
            return new Region(texture, new Vector4f(
                reverseU ? u1 : u0, reverseV ? v1 : v0,
                reverseU ? u0 : u1, reverseV ? v0 : v1));
        }

    }

}
