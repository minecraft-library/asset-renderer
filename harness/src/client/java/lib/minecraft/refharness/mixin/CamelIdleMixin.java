package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.CamelHuskRenderer;
import net.minecraft.client.renderer.entity.CamelRenderer;
import net.minecraft.client.renderer.entity.state.CamelRenderState;
import net.minecraft.world.entity.animal.camel.Camel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plays the idle clip a camel keeps on a timer of its own.
 *
 * <p>{@code Camel.setupAnimationStates} starts it whenever {@code idleAnimationTimeout} has run out,
 * and a fresh camel holds that at zero - so the first tick it ever takes starts the clip. What its
 * random decides is how long until the NEXT one, an interval in {@code [80, 120)} ticks, and an
 * interval is not a shape.
 *
 * <p>The four states beside it are a selector of their own, resting where a camel is neither sitting,
 * standing up nor dashing - so a default run drives them all stopped. <b>The dash is not what a
 * stride reaches</b>: it is a synched flag a rider sets, and the animal's ordinary walk is a
 * walk-gated clip that already plays under a stride, so selecting it under {@code WALK} would draw a
 * sprint vanilla does not run for a camel merely walking. One state serves both meshes the subject
 * draws: the saddle poses off this same render state, so its own copy of the clip runs with the
 * body's.
 *
 * <p><b>Two renderers rather than one, because a husk camel is a sibling and not a subclass.</b>
 * {@code CamelHuskRenderer} extends {@code MobRenderer} directly and declares its own
 * {@code extractRenderState} at the same descriptor, so an injection into the plain camel's alone
 * would leave a husk standing still against an asset side that plays the clip for both.
 */
@Mixin({CamelRenderer.class, CamelHuskRenderer.class})
public abstract class CamelIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/camel/Camel;Lnet/minecraft/client/renderer/entity/state/CamelRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startIdle(Camel entity, CamelRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.play(IdleFigures.selected(IdleFigures.Group.IDLE_CLIP), IdleFigures.State.IDLING,
            state.idleAnimationState);

        IdleFigures.State stance = IdleFigures.selected(IdleFigures.Group.CAMEL_STANCE);
        IdleFigures.play(stance, IdleFigures.State.SITTING_DOWN, state.sitAnimationState);
        IdleFigures.play(stance, IdleFigures.State.SITTING, state.sitPoseAnimationState);
        IdleFigures.play(stance, IdleFigures.State.STANDING_UP, state.sitUpAnimationState);
        IdleFigures.play(stance, IdleFigures.State.DASHING, state.dashAnimationState);
    }
}
