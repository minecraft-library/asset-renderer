package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.AxolotlRenderer;
import net.minecraft.client.renderer.entity.state.AxolotlRenderState;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the four factors an axolotl's animation mixes its poses by.
 *
 * <p>All four are the same mechanism: a binary animator stepping a tick at a time toward a boolean
 * over a length of ten, read as {@code inOutSine(ticks / 10)}, so each is a monotone zero to one. No
 * draw reaches any of them - the only random in the chain gates a hurt reaction and is server-side.
 * The state's constructor seeds {@code inWaterFactor} at ONE and every render overwrites it with an
 * animator nothing has started, which answers zero.
 *
 * <p><b>Two of the four are driven, and which two is the whole of the design here.</b> The model
 * runs all four animations at once and weights each by a {@code Math.min} of two factors - swimming
 * at {@code min(moving, inWater)}, hovering at {@code min(1 - moving, inWater)}, crawling at
 * {@code min(moving, onGround)}, lying still at {@code min(1 - moving, onGround)}. So in-water and
 * on-ground are the two halves of WHERE the subject is and vanilla holds one near one while the
 * other is near zero; driving both would give all four blends full weight and produce a pose that
 * weighting can never reach.
 *
 * <p>So the water gate is held open and the moving factor swept, which blends hovering into swimming
 * and back - the pair vanilla runs for a subject in water. {@code onGroundFactor} rests, and
 * {@code playingDeadFactor} is off the roster entirely: it is layered on top of the blend rather
 * than being part of it, and playing dead is a behaviour rather than an idle.
 */
@Mixin(AxolotlRenderer.class)
public abstract class AxolotlIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/axolotl/Axolotl;Lnet/minecraft/client/renderer/entity/state/AxolotlRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$mixFactors(Axolotl entity, AxolotlRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.Group.AXOLOTL.selected();
        state.inWaterFactor = IdleFigures.select(selected, IdleFigures.State.IN_WATER);
        state.onGroundFactor = IdleFigures.select(selected, IdleFigures.State.ON_GROUND);
        state.playingDeadFactor = IdleFigures.select(selected, IdleFigures.State.PLAYING_DEAD);
        state.movingFactor = IdleFigures.at(IdleFigures.Continuous.MOVING_FACTOR);
    }
}
