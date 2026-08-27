package lib.minecraft.renderer.engine.kit;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.renderer.BlockRenderer;
import lib.minecraft.renderer.engine.RendererDebug;
import lib.minecraft.renderer.engine.light.Lighting;
import lib.minecraft.renderer.engine.raster.SurfaceTraits;
import lib.minecraft.renderer.engine.raster.VisibleTriangle;
import lib.minecraft.renderer.face.CornerPhase;
import lib.minecraft.renderer.face.Face;
import lib.minecraft.renderer.face.FaceTextures;
import lib.minecraft.renderer.face.Unwrap;
import lib.minecraft.renderer.parity.Parity;
import lib.minecraft.renderer.tensor.Box;
import lib.minecraft.renderer.tensor.Vector2f;
import lib.minecraft.renderer.tensor.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The geometry every subject is built out of: the box, the quad it is split into, and the authoring
 * grid both are measured against.
 * <p>
 * Nothing here is about any one subject, which is the whole reason it is not in one of the three kits
 * that are. {@link BlockGeometryKit} walks block and item model elements, {@link EntityGeometryKit}
 * walks a bone tree and {@link FluidGeometryKit} builds a fluid's faces - and each of them reaches
 * this for the two primitives below. So do the renderers that draw a box directly and the kits that
 * emit their own quads.
 * <p>
 * All direction-aware logic lives in the face package: normals on {@link Face}, vertex winding on
 * {@link CornerPhase}, default UV derivation on {@link Unwrap}.
 */
@Parity(claim = "box-builder")
@UtilityClass
public class GeometryKit {

    /**
     * Edge length of a full block in vanilla model-authoring units. Every vanilla {@code block/}
     * and {@code item/} model JSON authors coordinates against this grid - element
     * {@code from} / {@code to} values of {@code [0, 0, 0]} and {@code [16, 16, 16]} describe a
     * full unit cube, face UVs run from {@code 0} to {@code 16}, and {@code display.*.translation}
     * values are in the same space. This kit and its consumers ({@link Unwrap.Element#rect},
     * {@link BlockRenderer}, item renderer's display-transform path) divide by this constant to
     * normalise into the engine's {@code [-0.5, +0.5]} unit-cube space before projection.
     */
    public static final float VANILLA_PIXEL_UNITS_PER_BLOCK = 16f;

    /**
     * The engine's normalized cube - the {@code [-0.5, +0.5]} span on every axis that every block
     * element is converted into, and the box {@link #unitCube} builds.
     */
    private static final @NotNull Box UNIT_CUBE = new Box(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f);

    /**
     * Builds a list of 12 triangles (2 per face) describing a unit cube centered at the origin
     * with the given per-face textures.
     * <p>
     * Every face uses the full {@code [0, 1]} UV rectangle.
     *
     * @param textures the texture each face paints
     * @param tintArgb the ARGB tint applied to every face, or {@code 0xFFFFFFFF} for no tint
     * @return the 12-triangle list, ready for rasterization
     */
    public static @NotNull ConcurrentList<VisibleTriangle> unitCube(
        @NotNull FaceTextures textures,
        int tintArgb
    ) {
        return buildBox(UNIT_CUBE, textures, tintArgb);
    }

    /**
     * Builds a list of 12 triangles describing an opaque, back-face-culled box, emitting untagged
     * triangles.
     *
     * @param box the box in model space
     * @param textures the texture each face paints
     * @param tintArgb the ARGB tint applied to every face
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildBox(
        @NotNull Box box,
        @NotNull FaceTextures textures,
        int tintArgb
    ) {
        return buildBox(box, textures, tintArgb, SurfaceTraits.OPAQUE_BODY, null);
    }

    /**
     * Builds a list of 12 triangles describing a box, carrying the surface character and the
     * per-pixel-trace name the caller declares.
     * <p>
     * <b>One builder serves every box this renderer draws</b> - a block's unit cube, an item slab, the
     * player's own body boxes, the cape, and a worn armour shell. Armour geometry does not differ from
     * block geometry by a code path; it differs by the two bits between
     * {@link SurfaceTraits#OPAQUE_BODY} and {@link SurfaceTraits#WORN_SHELL}, which is why those two
     * constants are the whole of the distinction and why they carry their own reasons.
     * <p>
     * {@code debugPart} names the box for the per-pixel trace and each face's two triangles carry
     * {@code part:face}. Without a tag an armour fragment logs {@code tag=null} and the trace skips it
     * entirely, which has misread the armour seam twice - so a caller holding a name passes it whenever
     * {@link RendererDebug#tracingPixels()} reports the dump armed. The name reaches the triangle as an
     * argument rather than being recovered downstream from emission order.
     *
     * @param box the box in model space
     * @param textures the texture each face paints
     * @param tintArgb the ARGB tint applied to every face
     * @param surface the surface character every triangle of the box carries
     * @param debugPart the box's name for the per-pixel trace, or {@code null} to emit untagged
     * @return the 12-triangle list
     */
    public static @NotNull ConcurrentList<VisibleTriangle> buildBox(
        @NotNull Box box,
        @NotNull FaceTextures textures,
        int tintArgb,
        @NotNull SurfaceTraits surface,
        @Nullable String debugPart
    ) {
        ConcurrentList<VisibleTriangle> triangles = Concurrent.newList();
        // The full [0, 1] rectangle - the UV every face of a box takes. One quartet serves all six
        // faces because Vector2f is immutable, so the instances are shared rather than re-derived.
        Vector2f[] uv = {
            new Vector2f(0f, 0f), new Vector2f(0f, 1f), new Vector2f(1f, 1f), new Vector2f(1f, 0f)
        };

        Face.forEach(face -> {
            Vector3f[] corners = CornerPhase.BAKERY.corners(face, box);
            Vector3f normal = face.normal();
            addQuad(
                triangles, corners, uv,
                textures.byFace(face), tintArgb,
                normal, Lighting.inventory(normal),
                surface,
                debugPart == null ? null : debugPart + ":" + face.direction()
            );
        });

        return triangles;
    }

    /**
     * The renderer's one quad emitter: splits a planar quad into its two triangles and appends them to
     * {@code out}.
     * <p>
     * <b>The fan diagonal is chosen here, and every planar quad the renderer draws is split by this
     * one line.</b> The triangles are {@code (0, 1, 2)} and {@code (0, 2, 3)}, so they meet along the
     * corner-0 / corner-2 pair - the diagonal {@link CornerPhase} pins. That is not a matter of taste:
     * two coplanar quads split on opposite diagonals fight along the seam they share, an equal depth
     * passes ({@code GL_LEQUAL}, last-drawn-wins), and the winner is then decided by emission order. A
     * block element, a bone cube, a fluid side and the shield used to spell this split independently
     * and agreed by luck.
     * <p>
     * Both triangles carry one {@code normal}, one {@code shading} and one {@code traits} instance -
     * that sharing is what makes them one quad rather than two triangles. A caller whose halves need
     * different values has no shared per-quad value to hand in and emits its own pair; the renderer's
     * only such site is a fluid's sloped top, whose four corners are not coplanar.
     * <p>
     * The shade is the caller's: {@link Lighting#inventory} for a box, a bone cube, a fluid face or the
     * shield, {@link BlockGeometryKit#elementShade} where a block element declares {@code shade} or
     * {@code light_emission}, and {@code Shading.UNLIT} for an entity cube, whose shade a later pass
     * over the folded stack resolves. Baking it here instead would make the emitter answer a lighting
     * question only some of its callers ask.
     *
     * @param out the list both triangles are appended to
     * @param corners the quad's four vertices, in the order the caller's {@link CornerPhase} walks them
     * @param uv the four UVs, {@code uv[i]} pairing with {@code corners[i]}
     * @param texture the texture both triangles sample
     * @param tintArgb the ARGB tint applied to each sampled pixel, or {@code 0xFFFFFFFF} for no tint
     * @param normal the surface normal both triangles carry
     * @param shading the shade scalar both triangles carry
     * @param traits the surface character both triangles carry, declared by the caller
     * @param debugTag the {@code part:face} label for the per-pixel trace, or {@code null} when the
     *     dump is not armed
     */
    static void addQuad(
        @NotNull ConcurrentList<VisibleTriangle> out,
        @NotNull Vector3f @NotNull [] corners,
        @NotNull Vector2f @NotNull [] uv,
        @NotNull PixelBuffer texture,
        int tintArgb,
        @NotNull Vector3f normal,
        float shading,
        @NotNull SurfaceTraits traits,
        @Nullable String debugTag
    ) {
        out.add(new VisibleTriangle(corners[0], corners[1], corners[2], uv[0], uv[1], uv[2], texture, tintArgb, normal, shading, traits, debugTag));
        out.add(new VisibleTriangle(corners[0], corners[2], corners[3], uv[0], uv[2], uv[3], texture, tintArgb, normal, shading, traits, debugTag));
    }

}
