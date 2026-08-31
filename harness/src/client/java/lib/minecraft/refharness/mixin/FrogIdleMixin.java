package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.FrogRenderer;
import net.minecraft.client.renderer.entity.state.FrogRenderState;
import net.minecraft.world.entity.animal.frog.Frog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the action clip a frog's selection names, and stops the two beside it.
 *
 * <p>A frog nothing has ticked holds all three stopped, which is the group's resting arm and the
 * default here. Its walk is a separate walk-gated clip that plays under a stride already.
 *
 * <p><b>The croak is deliberately absent, and it is a generator constraint rather than an
 * oversight.</b> {@code FrogModel.setupAnim} reads {@code croakAnimationState.isStarted()} to decide
 * whether the croaking body is DRAWN at all - a bone's visibility, which folds to a literal where
 * the shipped table is written. Driving the state leaves that flag unsettleable and the pose flow
 * refuses the subject, so the croak waits on a symbolic flag channel that does not exist.
 */
@Mixin(FrogRenderer.class)
public abstract class FrogIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/frog/Frog;Lnet/minecraft/client/renderer/entity/state/FrogRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$selectAction(
        Frog entity, FrogRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.FROG_ACTION);
        IdleFigures.play(selected, IdleFigures.State.JUMPING, state.jumpAnimationState);
        IdleFigures.play(selected, IdleFigures.State.TONGUING, state.tongueAnimationState);
        IdleFigures.play(selected, IdleFigures.State.SWIM_IDLING, state.swimIdleAnimationState);
    }
}
