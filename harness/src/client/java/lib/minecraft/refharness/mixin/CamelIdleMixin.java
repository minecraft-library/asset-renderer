package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.IdleFigures;
import net.minecraft.client.renderer.entity.CamelHuskRenderer;
import net.minecraft.client.renderer.entity.CamelRenderer;
import net.minecraft.client.renderer.entity.state.CamelRenderState;
import net.minecraft.world.entity.animal.camel.Camel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plays the idle clip a camel keeps on a timer of its own.
 *
 * <p>{@code Camel.setupAnimationStates} starts it whenever {@code idleAnimationTimeout} has run out,
 * and a fresh camel holds that at zero - so the first tick it ever takes starts the clip. What its
 * random decides is how long until the NEXT one, an interval in {@code [80, 120)} ticks, and an
 * interval is not a shape.
 *
 * <p>The four states beside it stay stopped, which is what a camel that is neither sitting, standing
 * up nor dashing holds them at. One state serves both meshes the subject draws: the saddle poses off
 * this same render state, so its own copy of the clip runs with the body's.
 *
 * <p><b>Two renderers rather than one, because a husk camel is a sibling and not a subclass.</b>
 * {@code CamelHuskRenderer} extends {@code MobRenderer} directly and declares its own
 * {@code extractRenderState} at the same descriptor, so an injection into the plain camel's alone
 * would leave a husk standing still against an asset side that plays the clip for both.
 */
@Mixin({CamelRenderer.class, CamelHuskRenderer.class})
public abstract class CamelIdleMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/camel/Camel;Lnet/minecraft/client/renderer/entity/state/CamelRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$startIdle(Camel entity, CamelRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        IdleFigures.play(IdleFigures.Group.IDLE_CLIP.selected(), IdleFigures.State.IDLING,
            state.idleAnimationState);
    }
}
