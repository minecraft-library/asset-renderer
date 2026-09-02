package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.DolphinRenderer;
import net.minecraft.client.renderer.entity.state.DolphinRenderState;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a dolphin under way, which is the only state its model animates in.
 *
 * <p>{@code DolphinRenderer.extractRenderState} fills the field as
 * {@code getDeltaMovement().horizontalDistanceSqr() > 1.0E-7}, and a subject nothing has added to a
 * world has no delta at all - so an offline dolphin reads false and its whole swim is folded away.
 * No draw is on that path: the field is read off a vector the tick fills and never off the random.
 *
 * <p><b>A two-member selection rather than a swept figure</b>, because the model reads it as a gate:
 * under it the body pitches by {@code -0.05 - 0.05 * cos(age * 0.3)} and the tail and its fin turn
 * by {@code -0.1} and {@code -0.2} of the same cosine, and above it the body takes its orientation
 * alone. There is nothing between the two to sweep, so the caller chooses which arm rather than how
 * far along it.
 */
@Mixin(DolphinRenderer.class)
public abstract class DolphinIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/dolphin/Dolphin;Lnet/minecraft/client/renderer/entity/state/DolphinRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$driveSwim(Dolphin entity, DolphinRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.isMoving =
            IdleFigures.selects(IdleFigures.selected(IdleFigures.Group.DOLPHIN),
                IdleFigures.State.MOVING);
    }
}
