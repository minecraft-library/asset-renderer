package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.CreakingRenderer;
import net.minecraft.client.renderer.entity.state.CreakingRenderState;
import net.minecraft.world.entity.monster.creaking.Creaking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the action clip a creaking's selection names, and stops the two beside it.
 *
 * <p>All three are started by an event rather than by a tick counter, so a creaking nothing has sent
 * anything to holds them stopped - which is the group's resting arm and the default here. Its walk
 * is a separate walk-gated clip that plays under a stride already.
 *
 * <p>The attack is shared with a warden, both spelling it {@code attackAnimationState}, which is why
 * one group carries both subjects.
 *
 * <p><b>The renderer is generic</b> - {@code CreakingRenderer<T extends Creaking>} - so the
 * descriptor names the erased bound rather than the type parameter.
 */
@Mixin(CreakingRenderer.class)
public abstract class CreakingIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/creaking/Creaking;Lnet/minecraft/client/renderer/entity/state/CreakingRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$selectAction(
        Creaking entity, CreakingRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.ACTION_CLIP);
        IdleFigures.play(selected, IdleFigures.State.ATTACKING, state.attackAnimationState);
        IdleFigures.play(selected, IdleFigures.State.INVULNERABLE, state.invulnerabilityAnimationState);
        IdleFigures.play(selected, IdleFigures.State.DYING, state.deathAnimationState);
    }
}
