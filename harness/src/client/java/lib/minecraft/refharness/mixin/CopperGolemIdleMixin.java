package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.CopperGolemRenderer;
import net.minecraft.client.renderer.entity.state.CopperGolemRenderState;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plays the idle clip a copper golem keeps on a timer of its own.
 *
 * <p>{@code CopperGolem.setupAnimationStates} runs the idle arm while the subject is in its idle
 * state, and starts the clip on the tick {@code idleAnimationStartTick} names. A fresh golem holds
 * that field at zero and takes its first tick at zero, so the two meet and the clip starts there.
 * The random sets only when the next one comes round, an interval in {@code [200, 240)} ticks.
 *
 * <p>The four chest-interaction states beside it stay stopped, which is what a golem carrying
 * nothing and reaching for nothing holds them at.
 */
@Mixin(CopperGolemRenderer.class)
public abstract class CopperGolemIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/golem/CopperGolem;Lnet/minecraft/client/renderer/entity/state/CopperGolemRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startIdle(
        CopperGolem entity, CopperGolemRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.play(IdleFigures.Group.IDLE_CLIP.selected(), IdleFigures.State.IDLING,
            state.idleAnimationState);
    }
}
