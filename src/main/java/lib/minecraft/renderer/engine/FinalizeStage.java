package lib.minecraft.renderer.engine;

import lib.minecraft.renderer.kit.GlintKit;
import org.jetbrains.annotations.NotNull;

/**
 * Shared terminal stage for 3D renders: supersample downscale, FXAA, and glint finalisation.
 * <p>
 * The entity, player, block, and fluid renderers each duplicate a near-identical tail that
 * supersamples into a pooled hi-res buffer, optionally applies FXAA, downscales, and finalises with
 * (optionally masked) glint. This stage will hold that logic in one place, parameterized by
 * {@link Spec} and a rasterize callback so the per-renderer differences (rasterize vs fitted, mask vs
 * none, glint vs static frame) are explicit rather than copied.
 * <p>
 * Skeleton introduced in Phase 0; the rasterize/finalise method bodies are migrated here in Phase 1.
 */
public final class FinalizeStage {

    /**
     * Parameters describing how to finalise a rendered buffer.
     *
     * @param antiAlias whether to apply FXAA before downscaling
     * @param ssaa supersampling factor; {@code 1} means no supersampling
     * @param enchanted whether the subject carries an enchantment glint
     * @param animateGlint whether to emit animated glint frames rather than a single frame
     * @param glint glint configuration (texture id, scroll periods, scale)
     * @param useGlintMask whether the glint is restricted to a recorded per-pixel mask (3D armor) or
     *     applied to every opaque pixel (2D items)
     */
    public record Spec(
        boolean antiAlias,
        int ssaa,
        boolean enchanted,
        boolean animateGlint,
        @NotNull GlintKit.GlintOptions glint,
        boolean useGlintMask
    ) {}

    private FinalizeStage() {
    }
}
