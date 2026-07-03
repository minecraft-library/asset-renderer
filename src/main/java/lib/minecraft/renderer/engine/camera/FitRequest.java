package lib.minecraft.renderer.engine.camera;

import lib.minecraft.renderer.engine.ModelEngine;
import lib.minecraft.renderer.tensor.Box;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How {@link ModelEngine#rasterizeFitted} should scale and centre a triangle list into its buffer.
 * The one caller-facing knob that unifies the player's auto-fit and the entity's native-resolution fit
 * onto the same {@code prepareFit} authority - the engine forks on {@link #mode()} and, within a mode,
 * on the camera {@link Lens#kind() lens kind}, but callers never thread lens math through a draw.
 *
 * <p>Two modes:
 * <ul>
 * <li><b>{@link Mode#AUTO_FILL}</b> - the engine measures the triangle list's own projected silhouette
 *     and scales it to fill {@link #fill()} of the buffer's smaller dimension. An orthographic lens bakes
 *     a 3D {@code scale(fit)} (preserving the depth frame); a perspective / oblique lens carries the fit
 *     as a post-projection 2D auto-fit. Used by the player and by the entity's perspective / oblique
 *     path (fed unit-normalized geometry so the foreshortening stays well-behaved).</li>
 * <li><b>{@link Mode#NATIVE_SCALE}</b> - the caller supplies an explicit model-units-to-NDC
 *     {@link #explicitScale() scale} (from its own native pixels-per-block canvas sizing) and a
 *     pre-measured projected {@link #explicitBounds() bounds} box whose midpoint the engine centres on
 *     the canvas. The engine bakes the scale in 3D and centres in screen space. Used by the entity's
 *     orthographic (VANILLA_ISO + axonometric) path, whose silhouette bounds are alpha-tight and, for
 *     family renders, unioned across variant siblings - measured by the caller, not the engine.</li>
 * </ul>
 *
 * @param mode the fit strategy
 * @param fill the fraction in {@code (0, 1]} of the smaller buffer dimension the silhouette spans, for
 *     {@link Mode#AUTO_FILL}; unused by {@link Mode#NATIVE_SCALE}
 * @param explicitScale the model-units-to-NDC scale for {@link Mode#NATIVE_SCALE}; unused by
 *     {@link Mode#AUTO_FILL}
 * @param explicitBounds the caller-measured projected silhouette bounds (in the engine's model-rotated
 *     screen space) whose midpoint is centred on the canvas, for {@link Mode#NATIVE_SCALE}; {@code null}
 *     for {@link Mode#AUTO_FILL}
 */
public record FitRequest(
    @NotNull Mode mode,
    float fill,
    float explicitScale,
    @Nullable Box explicitBounds
) {

    /**
     * The fit strategy - whether the engine measures and fills, or the caller supplies the scale and
     * silhouette bounds.
     */
    public enum Mode {

        /** Engine measures the triangle silhouette and scales it to fill {@link FitRequest#fill()}. */
        AUTO_FILL,

        /** Caller supplies an explicit scale and pre-measured bounds; the engine bakes and centres them. */
        NATIVE_SCALE

    }

    /**
     * An auto-fill request scaling the measured silhouette to fill {@code fill} of the buffer's smaller
     * dimension.
     *
     * @param fill the fraction in {@code (0, 1]} of the smaller buffer dimension the silhouette spans
     * @return the auto-fill request
     */
    public static @NotNull FitRequest autoFill(float fill) {
        return new FitRequest(Mode.AUTO_FILL, fill, 0f, null);
    }

    /**
     * A native-scale request applying the caller's explicit model-units-to-NDC scale and centring on the
     * midpoint of its pre-measured projected bounds.
     *
     * @param ndcScale the model-units-to-NDC scale to bake
     * @param projectedBounds the caller-measured projected silhouette bounds whose midpoint is centred
     * @return the native-scale request
     */
    public static @NotNull FitRequest nativeScale(float ndcScale, @NotNull Box projectedBounds) {
        return new FitRequest(Mode.NATIVE_SCALE, 0f, ndcScale, projectedBounds);
    }

}
