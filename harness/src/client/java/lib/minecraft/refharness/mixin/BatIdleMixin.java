package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.BatRenderer;
import net.minecraft.client.renderer.entity.state.BatRenderState;
import net.minecraft.world.entity.ambient.Bat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a bat on the wing, which is the animation a ticked one runs.
 *
 * <p>A bat's whole model is two keyframe clips and it writes no bone outside them, so an offline one
 * hangs in the mesh as authored - the single row of the animated corpus that measured zero. Vanilla
 * picks between them in {@code setupAnimationStates}: {@code isResting()} is one bit of a synched
 * byte its own {@code defineSynchedData} declares at zero, so a bat the client has built is flying,
 * and the resting state is stopped as the flying one starts.
 *
 * <p>The exclusion is what makes this a selection rather than two switches. Both clips write the
 * wings and the body, so a bat with both started is a pose vanilla never draws - the axolotl's
 * lesson, and the reason {@link IdleFigures#play} stops the member that was not chosen.
 */
@Mixin(BatRenderer.class)
public abstract class BatIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/ambient/Bat;Lnet/minecraft/client/renderer/entity/state/BatRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startFlight(Bat entity, BatRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.Group.BAT.selected();
        IdleFigures.play(selected, IdleFigures.State.FLYING, state.flyAnimationState);
        IdleFigures.play(selected, IdleFigures.State.RESTING, state.restAnimationState);
    }
}
