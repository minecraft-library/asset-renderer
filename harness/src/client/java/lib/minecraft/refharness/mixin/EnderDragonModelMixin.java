package lib.minecraft.refharness.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.dragon.EnderDragonModel;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@link EnderDragonModel#setupAnim setupAnim} so the harness renders the
 * authored rest pose instead of the in-flight glide pose.
 *
 * <h2>Why this exists</h2>
 * Even at {@code state.flapTime = 0} (which is what a never-ticked transient
 * {@code EnderDragon} produces - both {@code entity.oFlapTime} and {@code entity.flapTime}
 * are zero, so the lerp lands on zero), {@code EnderDragonModel.setupAnim} still computes:
 * <ul>
 *   <li>{@code jaw.xRot = (sin(0) + 1) * 0.2 = 0.2 rad} - jaw cracked ~11° open;</li>
 *   <li>{@code f3 = sin(-1) + 1 ≈ 0.159; f3 = (f3² + 2f3) * 0.05 ≈ 0.0172} - then
 *       {@code root.y = (f3 - 2) * 16 ≈ -31.7}, {@code root.z = -48}, and a small
 *       {@code root.xRot}, plus historical-position bends on the neck/tail parts.</li>
 * </ul>
 * Net effect: the dragon ships in a mid-glide pose with bent wings, descended neck,
 * downward-tilted head. That's a valid pose - just not the simplest ground-truth shape
 * for asset-renderer to reproduce, since asset-renderer doesn't yet do animation.
 *
 * <h2>What this skips</h2>
 * Cancelling {@code setupAnim} entirely leaves every {@link ModelPart
 * ModelPart} at its authored {@code PartPose} (the values fixed in
 * {@link EnderDragonModel#createBodyLayer createBodyLayer}). That gives the dragon's
 * rest pose - flat wings, level body, neck/tail straight back from the body.
 *
 * <h2>When to remove this mixin</h2>
 * <b>If asset-renderer ever gains animation support, delete this mixin so the harness
 * goes back to producing the actual flight-cycle pose vanilla shows in-game.</b>
 * Asset-renderer would then need to reproduce {@code setupAnim}'s formulas (and
 * {@code DragonFlightHistory} for neck/tail bone positions) to match. Until then,
 * keeping the harness on the rest pose means the byte-stable PNG can be reproduced by
 * static-mesh rendering on the asset-renderer side.
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins so non-harness
 * consumers of this jar (if any ever exist) keep vanilla animation behaviour.
 */
@Mixin(EnderDragonModel.class)
public abstract class EnderDragonModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void refharness$skipAnimation(EnderDragonRenderState state, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        ci.cancel();
    }
}
