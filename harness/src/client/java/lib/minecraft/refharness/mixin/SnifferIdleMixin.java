package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.SnifferRenderer;
import net.minecraft.client.renderer.entity.state.SnifferRenderState;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the action clip a sniffer's selection names, and stops the four beside it.
 *
 * <p>Vanilla's own selector is {@code Sniffer.State}, whose {@code IDLING} arm starts none of these
 * and is where an animal nothing has ticked sits. The dig is shared with a warden - both spell it
 * {@code diggingAnimationState} - which is why one group carries both subjects.
 *
 * <p>{@code SEARCHING} is an arm of that enum with no clip of its own on this model, so it is a
 * member of vanilla's selector that this roster has nothing to answer for and correctly omits.
 */
@Mixin(SnifferRenderer.class)
public abstract class SnifferIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/sniffer/Sniffer;Lnet/minecraft/client/renderer/entity/state/SnifferRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$selectAction(
        Sniffer entity, SnifferRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.ACTION_CLIP);
        IdleFigures.play(selected, IdleFigures.State.DIGGING, state.diggingAnimationState);
        IdleFigures.play(selected, IdleFigures.State.SNIFFER_SNIFFING, state.sniffingAnimationState);
        IdleFigures.play(selected, IdleFigures.State.RISING, state.risingAnimationState);
        IdleFigures.play(selected, IdleFigures.State.FEELING_HAPPY, state.feelingHappyAnimationState);
        IdleFigures.play(selected, IdleFigures.State.SCENTING, state.scentingAnimationState);
    }
}
