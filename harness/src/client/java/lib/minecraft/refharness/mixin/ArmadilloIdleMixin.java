package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.ArmadilloRenderer;
import net.minecraft.client.renderer.entity.state.ArmadilloRenderState;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the shell clip an armadillo's own state selects, and stops the two beside it.
 *
 * <p>{@code Armadillo.setupAnimationStates} is a switch over {@code ArmadilloState} and nothing
 * else - {@code ROLLING} starts the roll up, {@code UNROLLING} the roll out, {@code SCARED} the
 * peek, and {@code IDLE} stops all three. So it is a selector on the same terms an axolotl's is,
 * and the resting arm is the one a never-ticked animal already holds.
 *
 * <p><b>Not locomotion, so a stride does not reach it.</b> The animal's walk is a separate
 * walk-gated clip that plays under a stride already; balling up is fear, and selecting it for a
 * walking reference would animate something vanilla never draws while the animal is merely walking.
 */
@Mixin(ArmadilloRenderer.class)
public abstract class ArmadilloIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/armadillo/Armadillo;Lnet/minecraft/client/renderer/entity/state/ArmadilloRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$selectShell(
        Armadillo entity, ArmadilloRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.ARMADILLO_SHELL);
        IdleFigures.play(selected, IdleFigures.State.ROLLING_UP, state.rollUpAnimationState);
        IdleFigures.play(selected, IdleFigures.State.ROLLING_OUT, state.rollOutAnimationState);
        IdleFigures.play(selected, IdleFigures.State.PEEKING, state.peekAnimationState);
    }
}
