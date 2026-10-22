package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.AnimationClock;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beats an ender dragon's wings.
 *
 * <p>The one driven figure here no random reaches at all: a dragon rests in the hovering phase, where
 * {@code isSitting()} is unconditionally true, and {@code aiStep} then steps {@code flapTime} by a
 * literal {@code 0.1} a tick. Every reader multiplies by {@code 2 * PI}, so the figure is a phase of
 * period one and a whole beat is ten ticks. The roster carries that hover beat at every frame; a posed
 * frame writes its value into the dragon it draws, and every other render - a frozen frame, or a dragon
 * no posed frame draws - keeps the beat vanilla's own extract gave it.
 *
 * <p>Its neck and tail read differences across a flight history, and a subject nothing has moved has
 * sixty-four identical samples in that ring - so those differences are zero and the beat is the whole
 * of what an offline dragon animates. That is faithful rather than a gap.
 */
@Mixin(EnderDragonRenderer.class)
public abstract class EnderDragonIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;Lnet/minecraft/client/renderer/entity/state/EnderDragonRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$beatWings(EnderDragon entity, EnderDragonRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless") || !AnimationClock.isSubject(entity)) return;
        state.flapTime = AnimationClock.clock("flapTime");
    }
}
