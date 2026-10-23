package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.AnimationClock;
import net.minecraft.client.renderer.entity.ShulkerRenderer;
import net.minecraft.client.renderer.entity.state.ShulkerRenderState;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lifts a shulker's lid through vanilla's idle peek.
 *
 * <p>Vanilla ramps the figure by {@code 0.05} a tick toward a target its peek goal picks, and that
 * goal runs behind {@code isEffectiveAi() && !isClientSide}, so an offline shulker never opens at
 * all. The idle peek raises the lid to {@code 0.29999998} over 6 ticks, holds it 14 and lowers it
 * over 6, looped with no wait between peeks. Vanilla holds a peek for 20, 40 or 60 ticks from the
 * raise and waits a random while before the next; the shortest hold and the zero wait are the chosen
 * values.
 *
 * <p>The roster carries the lid's height at every frame; a posed frame writes that value into the
 * shulker it draws, and every other render - a frozen frame, or a shulker no posed frame draws - keeps
 * the height vanilla's own extract gave it.
 */
@Mixin(ShulkerRenderer.class)
public abstract class ShulkerIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Shulker;Lnet/minecraft/client/renderer/entity/state/ShulkerRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$peek(Shulker entity, ShulkerRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless") || !AnimationClock.isSubject(entity)) return;
        state.peekAmount = AnimationClock.clock("peekAmount");
    }
}
