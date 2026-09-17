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
 * <p><b>Three of the factors are one selection, and that is the whole of the design here.</b> The
 * model runs four blends at once and weights each by a {@code Math.min} of two factors - swimming
 * at {@code min(moving, inWater)}, hovering at {@code min(1 - moving, inWater)}, crawling at
 * {@code min(moving, onGround)}, lying still at {@code min(1 - moving, onGround)}. In-water and
 * on-ground are two halves of WHERE the subject is and vanilla holds one near one while the other
 * is near zero; driving both would give all four blends full weight and produce a pose that
 * weighting can never reach. So {@link IdleFigures.Group#AXOLOTL} carries them as a one-hot over
 * the four members of vanilla's own {@code AxolotlAnimationState}, exactly one answering one, and
 * {@code IN_AIR} names no field so selecting it rests the group.
 *
 * <p>{@code movingFactor} is swept on top of whichever member is selected rather than joining the
 * one-hot, being the other axis each blend is weighted by. {@code playingDeadFactor} is a member
 * like the rest, and the arm it opens is layered over the blend rather than part of it -
 * {@code setupPlayDeadAnimation} takes the factor alone where the other four arms each take
 * {@code ageInTicks} too, so it is the one pose of the five that reads no clock.
 */
@Mixin(AxolotlRenderer.class)
public abstract class AxolotlIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/axolotl/Axolotl;Lnet/minecraft/client/renderer/entity/state/AxolotlRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$mixFactors(Axolotl entity, AxolotlRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.AXOLOTL);
        state.inWaterFactor = IdleFigures.select(selected, IdleFigures.State.IN_WATER);
        state.onGroundFactor = IdleFigures.select(selected, IdleFigures.State.ON_GROUND);
        state.playingDeadFactor = IdleFigures.select(selected, IdleFigures.State.PLAYING_DEAD);
        state.movingFactor = IdleFigures.at(IdleFigures.Continuous.MOVING_FACTOR);

        // The BABY mesh of the same animal answers through keyframe clips where the adult weights
        // these factors, so one render state carries both encodings and they are two selectors. The
        // underwater walk is deliberately not among them: its model gates a walk-driven site on
        // isStarted, and driving it puts back a play site the generator's fold settles and drops.
        IdleFigures.State clip = IdleFigures.selected(IdleFigures.Group.AXOLOTL_CLIP);
        IdleFigures.play(clip, IdleFigures.State.BABY_SWIMMING, state.swimAnimation);
        IdleFigures.play(clip, IdleFigures.State.BABY_IDLING_UNDERWATER_ON_GROUND,
            state.idleUnderWaterOnGroundAnimationState);
        IdleFigures.play(clip, IdleFigures.State.BABY_IDLING_UNDERWATER,
            state.idleUnderWaterAnimationState);
        IdleFigures.play(clip, IdleFigures.State.BABY_IDLING_ON_GROUND,
            state.idleOnGroundAnimationState);
        IdleFigures.play(clip, IdleFigures.State.BABY_PLAYING_DEAD, state.playDeadAnimationState);
    }
}
