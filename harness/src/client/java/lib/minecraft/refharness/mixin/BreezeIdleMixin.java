package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.BreezeRenderer;
import net.minecraft.client.renderer.entity.state.BreezeRenderState;
import net.minecraft.world.entity.monster.breeze.Breeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whirls a breeze, and runs the pose clip layered over that whirl.
 *
 * <p><b>The whirl is not one arm of the pose selector - it runs under all of them.</b>
 * {@code Breeze.tick} calls {@code idle.startIfStopped(tickCount)} unconditionally, before the pose
 * switch and regardless of which arm that switch takes, so every ticked breeze is whirling. It is
 * the bat's situation: there is no arm for a breeze that is not, and the frame an offline one used
 * to draw is one vanilla shows only before the subject's first tick.
 *
 * <p>The five pose clips beside it are the one-hot {@code onSyncedDataUpdated} drives off
 * {@code Pose}, with {@code STANDING} starting none of them - so a default run leaves the subject
 * whirling on the spot, which is what a breeze that has nothing to do is drawn as.
 *
 * <p><b>The slide is the one a stride reaches.</b> A breeze carries no walk-gated clip at all and
 * travels by skimming along the ground, so a walking reference without it is a subject sliding
 * nowhere.
 */
@Mixin(BreezeRenderer.class)
public abstract class BreezeIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/breeze/Breeze;Lnet/minecraft/client/renderer/entity/state/BreezeRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startWhirl(
        Breeze entity, BreezeRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.play(IdleFigures.selected(IdleFigures.Group.BREEZE_WHIRL),
            IdleFigures.State.WHIRLING, state.idle);

        IdleFigures.State pose = IdleFigures.selected(IdleFigures.Group.BREEZE_POSE);
        IdleFigures.play(pose, IdleFigures.State.SLIDING, state.slide);
        IdleFigures.play(pose, IdleFigures.State.SLIDING_BACK, state.slideBack);
        IdleFigures.play(pose, IdleFigures.State.INHALING, state.inhale);
        IdleFigures.play(pose, IdleFigures.State.SHOOTING, state.shoot);
        IdleFigures.play(pose, IdleFigures.State.LONG_JUMPING, state.longJump);
    }
}
