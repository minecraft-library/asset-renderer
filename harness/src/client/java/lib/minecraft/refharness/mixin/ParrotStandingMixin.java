package lib.minecraft.refharness.mixin;

import lib.minecraft.refharness.PoseState;
import net.minecraft.client.model.animal.parrot.ParrotModel;
import net.minecraft.client.renderer.entity.ParrotRenderer;
import net.minecraft.client.renderer.entity.state.ParrotRenderState;
import net.minecraft.world.entity.animal.parrot.Parrot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stands a parrot on the ground on every posed frame.
 *
 * <p>{@code ParrotRenderer.extractRenderState} writes {@code state.pose = ParrotModel.getPose(parrot)}
 * after {@code super} returns, and {@code getPose} answers {@code PARTY}, then {@code SITTING}, then
 * {@code FLYING} when {@code Parrot.isFlying()} - which is {@code !onGround()} - and
 * {@code STANDING} only otherwise. A subject the harness builds is never added to a world, so it is
 * never on the ground and vanilla draws it flying. The asset side rests a parrot standing in every
 * render, walking it on the ground at its wander pace, so both posed gaits pin {@code STANDING} here
 * and the two sides draw one parrot.
 *
 * <p>Inert on the frozen sub-trees, and harmless there either way: {@code ParrotModel.setupAnim} is
 * the pose's one reader and the freeze skips it.
 *
 * <p>Injected at the subclass return because the field is written after
 * {@code super.extractRenderState} has returned.
 */
@Mixin(ParrotRenderer.class)
public abstract class ParrotStandingMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/parrot/Parrot;Lnet/minecraft/client/renderer/entity/state/ParrotRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$stand(Parrot entity, ParrotRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless") || !PoseState.posed()) return;
        state.pose = ParrotModel.Pose.STANDING;
    }
}
