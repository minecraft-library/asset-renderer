package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.monster.wither.WitherBossModel;
import net.minecraft.client.renderer.entity.state.WitherRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>Now redundant.</b> {@link SkipSetupAnimMixin} redirects every {@code setupAnim} call from
 * {@link net.minecraft.client.renderer.entity.LivingEntityRenderer LivingEntityRenderer.submit}
 * generically; this per-model HEAD cancel still works (idempotent with the redirect) but is
 * no longer load-bearing. Kept as documentation of the wither-specific reasoning until the
 * generic mixin matures.
 *
 * <p>Original purpose: cancels {@link WitherBossModel#setupAnim setupAnim} so the harness
 * renders the authored rest pose instead of the at-rest-but-still-animated chest-bob pose.
 *
 * <h2>Why this exists</h2>
 * Even at {@code state.ageInTicks = 0} (a never-ticked transient {@code WitherBoss}),
 * {@code WitherBossModel.setupAnim} still computes:
 * <ul>
 *   <li>{@code f = cos(0 * 0.1) = 1} so {@code ribcage.xRot = (0.065 + 0.05 * 1) * π ≈ 0.3613 rad} -
 *       chest tilted forward {@code ~20.7°} (this {@code putfield} replaces the bind-pose
 *       {@code RIBCAGE_X_ROT_OFFSET = 0.20420352 rad ≈ 11.7°}, not adds);</li>
 *   <li>{@code tail.setPos(-2 + cos(ribcage.xRot)*10, 6.9, -0.5 + sin(ribcage.xRot)*10)} ≈
 *       {@code (~7.36, 6.9, ~3.03)} - the tail repositions away from the body root following the
 *       new ribcage angle;</li>
 *   <li>{@code tail.xRot = (0.265 + 0.1 * 1) * π ≈ 1.1467 rad ≈ 65.7°} - the tail tilts forward.</li>
 * </ul>
 * Net effect: the wither ships with a noticeably bent body that diverges from the static
 * {@code createBodyLayer} authored pose. Cancelling {@code setupAnim} leaves every
 * {@link net.minecraft.client.model.geom.ModelPart ModelPart} at its authored {@code PartPose}.
 *
 * <h2>When to remove this mixin</h2>
 * <b>If asset-renderer ever gains animation support, delete this mixin so the harness goes back
 * to producing the actual idle-pose vanilla shows in-game.</b> Asset-renderer would then need to
 * reproduce the {@code setupAnim} formulas (cosine-based ribcage tilt, derived tail offset and
 * rotation, head tracking) to match. Until then, keeping the harness on the rest pose means the
 * byte-stable PNG can be reproduced by static-mesh rendering on the asset-renderer side.
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins so non-harness consumers
 * of this jar keep vanilla animation behaviour.
 */
@Mixin(WitherBossModel.class)
public abstract class WitherBossModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/WitherRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void refharness$skipAnimation(WitherRenderState state, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        ci.cancel();
    }
}
