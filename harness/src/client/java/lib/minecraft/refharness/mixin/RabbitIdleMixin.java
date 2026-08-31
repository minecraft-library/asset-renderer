package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.RabbitRenderer;
import net.minecraft.client.renderer.entity.state.RabbitRenderState;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tilts a rabbit's head, which is the whole of what an idle one animates.
 *
 * <p>{@code Rabbit.setupAnimationStates} starts it whenever its own timer has run out and the
 * subject is neither leashed nor {@code isNoAi()} - a fresh rabbit answers all three, so the first
 * tick it takes starts the clip. Its random sets the {@code [180, 220)} ticks until the next one and
 * reaches nothing else.
 *
 * <p><b>The hop is the same selector's second arm, and a walking run is what reaches it.</b>
 * {@code setupAnimationStates} is a three-way: an idle due starts the tilt, otherwise
 * {@code jumpTicks > 0} starts the hop and stops the tilt, otherwise both are stopped. A rabbit
 * travels by hopping and carries no walk-gated clip at all, so a walking reference without this
 * swings the legs of an animal vanilla draws mid-hop.
 */
@Mixin(RabbitRenderer.class)
public abstract class RabbitIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/rabbit/Rabbit;Lnet/minecraft/client/renderer/entity/state/RabbitRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startHeadTilt(
        Rabbit entity, RabbitRenderState state, float partialTick, CallbackInfo ci) {

        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.State selected = IdleFigures.selected(IdleFigures.Group.RABBIT);
        IdleFigures.play(selected, IdleFigures.State.TILTING, state.idleHeadTiltAnimationState);
        IdleFigures.play(selected, IdleFigures.State.HOPPING, state.hopAnimationState);
    }
}
