package lib.minecraft.refharness;

/**
 * Holds the harness-controlled enchantment-glint time so the glint reference renders are
 * deterministic and phase-aligned with asset-renderer's pre-rendered glint frames.
 *
 * <p>Vanilla's {@code TextureTransform.setupGlintTexturing(float)} derives the scroll offset from
 * {@code (long)(Util.getMillis() * glintSpeed * 8.0)} - real wall-clock time, so a single captured
 * frame lands at an arbitrary glint phase. {@link lib.minecraft.refharness.mixin.GlintTexturingMixin}
 * reads {@link #overrideT} instead whenever it is non-negative, letting {@link GlintSweeper} step the
 * glint through a fixed schedule of {@code t} values (the same schedule the asset-renderer side feeds
 * {@code GlintKit.applyGlintAtTimes}) and capture one PNG per value.
 *
 * <p>{@code volatile} because the value is set on the client tick thread before each render and read
 * inside the (same-thread, but separately compiled) mixin handler; {@code -1} disables the override
 * and restores vanilla wall-clock behaviour.
 */
public final class GlintClock {

    /**
     * The forced glint time in vanilla post-{@code glintSpeed} millis, or {@code -1} to use vanilla's
     * wall-clock derivation.
     */
    public static volatile long overrideT = -1;

    private GlintClock() {}
}
