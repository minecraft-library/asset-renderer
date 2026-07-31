package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.GlintClock;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the enchantment-glint scroll matrix with a harness-controlled glint time so the glint
 * reference renders are deterministic.
 *
 * <h2>Why this exists</h2>
 * Vanilla {@code TextureTransform.setupGlintTexturing(float)} builds the glint UV matrix from
 * {@code t = (long)(Util.getMillis() * glintSpeed * 8.0)} - real wall-clock time. A single PIP
 * capture therefore lands at an arbitrary glint phase, so it can never byte-align with
 * asset-renderer's pre-rendered glint frames (which scroll through a fixed schedule). This mixin
 * substitutes {@link GlintClock#overrideT} for {@code t} when the harness has set it, rebuilding the
 * <em>exact</em> vanilla matrix ({@code translation(-u, v, 0).rotateZ(10 degrees).scale(scale)}) so
 * the only thing that changes is which deterministic glint time is sampled.
 *
 * <p>Gated on {@code refharness.headless} and {@code overrideT >= 0}; outside the glint sweep the
 * override is {@code -1} and vanilla's wall-clock behaviour is untouched.
 */
@Mixin(TextureTransform.class)
public abstract class GlintTexturingMixin {

    /** U-axis loop period - {@code 110_000L} modulus in {@code setupGlintTexturing}. */
    private static final long U_LOOP_MILLIS = 110_000L;

    /** V-axis loop period - {@code 30_000L} modulus in {@code setupGlintTexturing}. */
    private static final long V_LOOP_MILLIS = 30_000L;

    /** Glint UV rotation - {@code 0.17453292f} radians, ~10 degrees. */
    private static final float ROTATION_RADIANS = 0.17453292f;

    @Inject(method = "setupGlintTexturing(F)Lorg/joml/Matrix4f;", at = @At("HEAD"), cancellable = true)
    private static void refharness$forceGlintTime(float scale, CallbackInfoReturnable<Matrix4f> cir) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        long t = GlintClock.overrideT;
        if (t < 0) return;

        float u = (float) Math.floorMod(t, U_LOOP_MILLIS) / U_LOOP_MILLIS;
        float v = (float) Math.floorMod(t, V_LOOP_MILLIS) / V_LOOP_MILLIS;
        Matrix4f matrix = new Matrix4f().translation(-u, v, 0.0f).rotateZ(ROTATION_RADIANS).scale(scale);
        cir.setReturnValue(matrix);
    }
}
