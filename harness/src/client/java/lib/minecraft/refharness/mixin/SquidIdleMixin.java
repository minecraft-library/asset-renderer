package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.AnimationClock;
import net.minecraft.client.renderer.entity.SquidRenderer;
import net.minecraft.client.renderer.entity.state.SquidRenderState;
import net.minecraft.world.entity.animal.squid.Squid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves a squid's tentacles through vanilla's stroke.
 *
 * <p>{@code Squid.aiStep} steps a tentacle phase by a rate and, while that phase is under {@code PI},
 * writes {@code tentacleAngle = sin((m / PI) ^ 2 * PI) * PI * 0.25}; past it the angle holds at zero
 * until the phase wraps. So each stroke flares the tentacles out and back, then hangs them still for as
 * long again. A subject nothing ticks holds the angle at zero and its tentacles hang.
 *
 * <p>The stroke runs at the speed vanilla gives entity id 9, the network id every subject renders at,
 * and the roster carries the angle at every frame; a posed frame writes that value into the squid it
 * draws, and every other render - a frozen frame, or a squid no posed frame draws - keeps the angle
 * vanilla's own extract gave it.
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
        if (!Boolean.getBoolean("refharness.headless") || !AnimationClock.isSubject(entity)) return;
        state.tentacleAngle = AnimationClock.clock("tentacleAngle");
    }
}
