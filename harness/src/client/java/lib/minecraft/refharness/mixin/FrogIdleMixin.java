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
 * <p>A frog nothing has ticked holds all four stopped, which is the group's resting arm and the
 * default here. Its walk is a separate walk-gated clip that plays under a stride already.
 *
 * <p><b>The croak DRAWS its bone as well as moving it, so it is half of a pair.</b>
 * {@code FrogModel.setupAnim} writes {@code croakingBody.visible} from this same state, and the clip
 * writes that bone and nothing else - so the sac exists only while the croak runs. The mesh rests it
 * undrawn carrying a {@code croak} toggle, and a reference that wants the croak selects that
 * appearance as well as this state, which is why {@code EntityRoster} names the toggle beside the
 * stand's arms and the goat's horns.
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
        IdleFigures.play(selected, IdleFigures.State.CROAKING, state.croakAnimationState);
    }
}
