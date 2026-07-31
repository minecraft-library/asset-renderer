package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Drops every cube polygon whose UV rectangle collapses to a line, gated on
 * {@code refharness.headless}.
 *
 * <h2>Why this exists</h2>
 * A plane cube - one authored with a zero-size axis, like a fish fin or a warden tendril - has two
 * faces with area and four edge faces that collapse. Vanilla builds all six polygons regardless, and
 * for the plain cube the four edges are zero-area, so the GPU discards them and they cost nothing.
 * <b>An overlay layer that inflates such a cube breaks that</b>: {@code CubeDeformation} moves the
 * corner positions but <b>not</b> the UV unwrap, which is derived from the authored size, so the four
 * edges become real slivers two inflates thick that still carry a <em>zero-width</em> UV strip. The
 * GPU then rasterizes them, sampling one texel column across a surface that has no texture extent.
 *
 * <p>At the harness's magnification that is visible. The tropical fish's pattern overlay inflates by
 * {@code 0.008}, which at {@code 256} px/block puts its fin edges {@code 0.181} px wide - narrow
 * enough to be invisible in game at any real entity size, wide enough here to catch a sample column
 * and paint a hard 1-px line down the reference, detached from the fish by up to 55 px. It cost the
 * six large-shape tropical fish rows a combined {@code 2.2} of parity against an asset renderer that
 * is right not to draw it.
 *
 * <p>The predicate is the harness's own, not a new one:
 * {@code EntityBoundsWalker.contributePolygonExtents} already drops these polygons from the
 * canvas measurement on the stated grounds that "they render no pixels" - true of the plain plane
 * cube and false of the inflated one. So the two halves of the harness disagreed and the render was
 * the wrong one; this makes the render agree with the measurement that sizes its canvas. Filtering at
 * cube construction is what keeps them agreeing, since both read the same {@code polygons} array.
 *
 * <p>Byte-neutral for every plain plane cube - those polygons are zero-area and drew nothing already
 * - so the only references that move are the ones carrying an inflated plane.
 *
 * <h2>When to remove this mixin</h2>
 * <b>Delete when the asset renderer draws an inflated plane cube's edge slivers.</b> Emitting them
 * there was built and declined: java's barycentric coverage of a sliver a fifth of a pixel wide does
 * not agree with the GPU's edge functions (the fin's own {@code down} sliver solves to a barycentric
 * of {@code -83}), so it recovered none of the six rows and cost two others.
 */
@Mixin(ModelPart.Cube.class)
public abstract class DegenerateUvPolygonMixin {

    @Shadow @Final @Mutable public ModelPart.Polygon[] polygons;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void refharness$dropDegenerateUvPolygons(
        int texCoordU,
        int texCoordV,
        float originX,
        float originY,
        float originZ,
        float sizeX,
        float sizeY,
        float sizeZ,
        float growX,
        float growY,
        float growZ,
        boolean mirror,
        float texScaleU,
        float texScaleV,
        Set<Direction> faces,
        CallbackInfo ci
    ) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        List<ModelPart.Polygon> kept = new ArrayList<>(this.polygons.length);
        for (ModelPart.Polygon polygon : this.polygons)
            if (!hasDegenerateUv(polygon)) kept.add(polygon);
        if (kept.size() != this.polygons.length)
            this.polygons = kept.toArray(new ModelPart.Polygon[0]);
    }

    /**
     * Returns whether a polygon's four vertex UVs span no width or no height - the unwrap of a face
     * whose normal lies along its cube's zero-size axis.
     *
     * @param polygon the polygon to test
     * @return {@code true} when the UV rectangle collapses to a line
     */
    private static boolean hasDegenerateUv(ModelPart.Polygon polygon) {
        ModelPart.Vertex[] vertices = polygon.vertices();
        float uMin = vertices[0].u();
        float uMax = uMin;
        float vMin = vertices[0].v();
        float vMax = vMin;
        for (ModelPart.Vertex vertex : vertices) {
            uMin = Math.min(uMin, vertex.u());
            uMax = Math.max(uMax, vertex.u());
            vMin = Math.min(vMin, vertex.v());
            vMax = Math.max(vMax, vertex.v());
        }
        return uMin == uMax || vMin == vMax;
    }
}
