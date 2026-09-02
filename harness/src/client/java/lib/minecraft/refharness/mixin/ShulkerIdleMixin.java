package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.ShulkerRenderer;
import net.minecraft.client.renderer.entity.state.ShulkerRenderState;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens and closes a shulker's lid.
 *
 * <p>Vanilla ramps the figure by {@code 0.05} a tick toward a target its peek goal picks, and that
 * goal runs behind {@code isEffectiveAi() && !isClientSide}, so an offline shulker never opens at
 * all. Both draws in it choose an interval and a duration rather than how far the lid travels, which
 * is bounded at one by the field that bounds a lid.
 */
@Mixin(ShulkerRenderer.class)
public abstract class ShulkerIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Shulker;Lnet/minecraft/client/renderer/entity/state/ShulkerRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$peek(Shulker entity, ShulkerRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.peekAmount = IdleFigures.at(IdleFigures.Continuous.PEEK_AMOUNT);
    }
}
