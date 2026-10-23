package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.AnimationClock;
import lib.minecraft.refharness.PoseState;
import net.minecraft.client.renderer.entity.IronGolemRenderer;
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts an iron golem's flower offer down per posed frame, from the golem's own counter.
 *
 * <p>Offering a flower sets {@code offerFlowerTick} to 400 ({@code IronGolem.handleEntityEvent} on
 * event 11, which the carried trait sends), and {@code IronGolem.aiStep} lowers it by one a tick while
 * it is positive. {@code IronGolemModel.setupAnim} raises the right arm to
 * {@code -0.8 + 0.025 * triangleWave(offerFlowerTick, 70)} while the field is positive, so the arm
 * sways as the counter runs down. A golem nothing ticks holds 400 on every frame and its arm would
 * hold one instant of that sway.
 *
 * <p>So a posed frame writes what {@code aiStep} would have left after the frame's tick - the counter
 * less the tick, floored at vanilla's zero - and the reference shows the offer's sway. The renderer
 * resolves the poppy from the counter before this fires, so the flower stays drawn as it is; only the
 * arm moves. A golem offering nothing holds a counter of zero and is left as vanilla built it, and a
 * frozen frame is untouched.
 */
@Mixin(IronGolemRenderer.class)
public abstract class IronGolemOfferMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/golem/IronGolem;Lnet/minecraft/client/renderer/entity/state/IronGolemRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$countOfferDown(IronGolem entity, IronGolemRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless") || !PoseState.posed()) return;
        int offered = entity.getOfferFlowerTick();
        if (offered > 0) state.offerFlowerTick = Math.max(0, offered - AnimationClock.tick);
    }
}
