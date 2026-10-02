package lib.minecraft.renderer.engine.light;

import dev.simplified.annotations.UtilityClass;
import lib.minecraft.renderer.engine.geometry.Face;
import lib.minecraft.renderer.engine.math.Vector3f;
import lib.minecraft.renderer.parity.Mode;
import lib.minecraft.renderer.parity.Parity;
import org.jetbrains.annotations.NotNull;

/**
 * The cardinal shade a face takes under vanilla's inventory light rig, and the cardinal a surface
 * normal resolves to before it is read.
 */
@UtilityClass
@Parity(claim = "face-vocabulary", mode = Mode.DEMOTE)
public final class FaceShade {

    /**
     * The shade factor applied to a face under vanilla's {@code Lighting.ITEMS_3D} GUI pose.
     * <p>
     * The per-axis values are <b>reversed</b> relative to world-lit block brightness from
     * {@code Direction.getBrightness}: vanilla's inventory pipeline uses two directional lights
     * offset in X so the left-hand (E/W) face ends up brighter than the right-hand (N/S) face after
     * the standard {@code [30, 225, 0]} gui rotation. Rather than replicate the dual-directional
     * light shader, each face takes a pre-baked scalar that approximates the vanilla inventory
     * output ({@code 0.8} for E/W, {@code 0.6} for N/S, {@code 1.0} for UP, {@code 0.5} for DOWN).
     * <p>
     * Entity rendering does not read it at all - vanilla's {@code Lighting.Entry.ENTITY_IN_UI} is a
     * dual-directional Lambertian shader, so an entity's shade is computed per-vertex from the
     * surface normal and baked into each triangle at kit time.
     *
     * @param face the face being shaded
     * @return the shade factor for that face
     */
    public static float of(@NotNull Face face) {
        return switch (face) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.6f;
            case WEST, EAST -> 0.8f;
        };
    }

    /**
     * Resolves the dominant cardinal face for a surface normal - the single {@code Face} vanilla would
     * assign a baked quad with this geometric normal. Replicates vanilla's
     * {@code FaceBakery.findClosestDirection} plus the {@code BakedQuad} constructor's
     * {@code requireNonNullElse(direction, UP)} degenerate fallback in one place: it is the shared
     * cardinal resolver for both inventory-style lighting and the block-icon relight snap.
     * <p>
     * The largest-magnitude axis wins; the sign of that component picks between the two opposing
     * faces on that axis. Only component-magnitude ordering matters, so the result is invariant to
     * a positive uniform scale of {@code normal} - an un-normalized normal works too. The
     * magnitude {@code if}-chain is used over an equivalent six-cardinal dot-product loop: it
     * resolves the same face with three {@code abs} calls and at most three comparisons, no array
     * or iteration.
     * <p>
     * <b>Ties</b> resolve to the earlier axis in {@code Y > Z > X} order (the {@code >=}
     * comparisons), matching the {@code Direction.values()} (DOWN, UP, NORTH, SOUTH, WEST, EAST)
     * first-wins iteration order of vanilla's {@code findClosestDirection}. This is load-bearing
     * for exact 45-degree faces: a sculk-sensor tendril whose authored normal has {@code |x| ==
     * |z|} must snap to the Z cardinal (shade 0.40), not EAST/WEST (0.65/0.49). The element-
     * rotation matrix yields a bit-symmetric {@code |x| == |z|} normal, so the tie falls to the
     * earlier (Z) axis and reproduces the reference shade. (Folding the relight snap onto the
     * opposite {@code Y > X > Z}-style tie-break regresses 8 cross / candle / stem blocks out of
     * the {@code <0.25} block-parity bucket - measured, not assumed.) A zero normal (cross of
     * collinear edges) has no winning axis and falls back to {@link Face#UP}.
     * <p>
     * Two consumers share this single resolver: {@link Lighting#inventory} bakes the per-face
     * {@code Lighting.ITEMS_3D}-style cardinal shade for block + fluid kits, and the block-icon
     * relight pass ({@link Shading#relightForItems3d}) snaps a plain-block quad's authored normal to
     * its nearest cardinal before lighting - matching vanilla's
     * {@code BlockFeatureRenderer.putBakedQuad}, which lights every quad by its single
     * {@code BakedQuad.direction} rather than the continuous tilted normal.
     *
     * @param normal the surface normal (should be normalized, but only magnitude ordering matters)
     * @return the closest cardinal face to the normal direction, or {@link Face#UP} for a zero normal
     */
    public static @NotNull Face fromNormal(@NotNull Vector3f normal) {
        float absX = Math.abs(normal.x());
        float absY = Math.abs(normal.y());
        float absZ = Math.abs(normal.z());

        // Degenerate (zero) normal: vanilla's FaceBakery.calculateFacing returns null, mapped to UP
        // by the BakedQuad constructor's requireNonNullElse(direction, UP). Match that fallback.
        if (absX == 0f && absY == 0f && absZ == 0f) return Face.UP;

        if (absY >= absX && absY >= absZ)
            return normal.y() > 0f ? Face.UP : Face.DOWN;

        if (absZ >= absX)
            return normal.z() > 0f ? Face.SOUTH : Face.NORTH;

        return normal.x() > 0f ? Face.EAST : Face.WEST;
    }

}
