package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.PhantomRenderer;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins a phantom's flap-cycle offset to zero, leaving its wings beating off the schedule alone.
 *
 * <p>{@link PhantomRenderer#extractRenderState extractRenderState} sets
 * {@code state.flapTime = entity.getUniqueFlapTickOffset() + state.ageInTicks}, and the offset is
 * {@code getId() * 3}. An id is a counter over every entity the client has built, so which phase a
 * subject's wings start at is a function of how many were built before it - a randomization with no
 * {@code random} call in sight, of the same shape as {@code WitchNoseMixin}'s. {@code
 * PhantomModel.setupAnim} turns each wing segment by {@code cos(flapTime * 7.448451 deg)}, so an
 * unpinned offset snaps the wings to a different rotation on every run.
 *
 * <p><b>The offset is what is pinned, not the sum.</b> Assigning the elapsed age back is the phantom
 * whose id is zero, which keeps the whole of vanilla's own arithmetic and drops only the term that
 * varies with the client's history. Pinning the sum to zero instead would take the schedule with it
 * and hold a beating wing flat - free on a frozen run, where the age is zero either way, and a
 * fiction on the animated one, where every other phantom in the game is beating.
 *
 * <p>So this is inert on the seven frozen sub-trees: {@code AnimationClock.ageInTicks()} answers
 * zero there, which is the flat-wing pose those references already hold.
 *
 * <p>Same injection-point reasoning as {@code GuardianStateMixin}:
 * {@code flapTime} is populated in the subclass override <em>after</em>
 * {@code super.extractRenderState(...)} runs, so {@code FreezeAnimationStateMixin} on the
 * base renderer fires before the subclass writes it. Inject at the subclass return point.
 */
@Mixin(PhantomRenderer.class)
public abstract class PhantomStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/client/renderer/entity/state/PhantomRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$pinFlapTime(Phantom entity, PhantomRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.flapTime = state.ageInTicks;
    }
}
