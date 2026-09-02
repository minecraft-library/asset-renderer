package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.SquidRenderer;
import net.minecraft.client.renderer.entity.state.SquidRenderState;
import net.minecraft.world.entity.animal.squid.Squid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves a squid's tentacles through the arc its own arithmetic bounds them to.
 *
 * <p>{@code Squid.aiStep} steps a tentacle phase by a rate it redraws in {@code (0.1, 0.2]} and,
 * while that phase is under {@code PI}, writes
 * {@code tentacleAngle = sin((m / PI) ^ 2 * PI) * PI * 0.25} - so the angle rises to a quarter turn
 * and returns, and the redrawn rate decides only how quickly. A subject nothing ticks holds it at
 * zero and its tentacles hang.
 *
 * <p>Covers the glow squid too, whose renderer extends this one.
 *
 * <p>Injected at the subclass return because a subclass fills its own fields after
 * {@code super.extractRenderState} has returned, which is after
 * {@link FreezeAnimationStateMixin} fires.
 */
@Mixin(SquidRenderer.class)
public abstract class SquidIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/squid/Squid;Lnet/minecraft/client/renderer/entity/state/SquidRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$driveTentacles(Squid entity, SquidRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.tentacleAngle = IdleFigures.at(IdleFigures.Continuous.TENTACLE_ANGLE);
    }
}
