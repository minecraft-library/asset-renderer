package lib.minecraft.renderer.tooling.atlas;

import org.jetbrains.annotations.NotNull;

/**
 * The atlas diagnose flow's complete policy roster, minus the mis-homed
 * {@code 0.999f} alpha epsilon, which is entity-overlay knowledge. One
 * empirically-tuned threshold, its tuning history carried as provenance. Not a
 * {@code NavigationPolicy}: a render/diagnose job consults a threshold, it does not navigate
 * bytecode.
 */
enum AtlasPolicies {

    /**
     * The opaque-pixel fraction below which a tile is flagged {@code sparseContent}. Tuned at
     * {@code 2%} after inspecting the flag distribution.
     */
    SPARSE_CONTENT_THRESHOLD(
        0.02,
        "empirically tuned: at 5% normal thin blocks (torches, candles, buttons) false-flag; at 2%"
            + " the bucket is template submodels that should be filtered at registration (tripwire"
            + " sub-models, glass_pane sub-posts, stem_stage growth) - ToolingAtlasDiagnose.java:71-77");

    private final double value;
    private final @NotNull String provenance;

    AtlasPolicies(double value, @NotNull String provenance) {
        this.value = value;
        this.provenance = provenance;
    }

    /** The opaque-pixel fraction below which a tile is flagged {@code sparseContent}. */
    static double sparseContentThreshold() {
        return SPARSE_CONTENT_THRESHOLD.value;
    }

}
