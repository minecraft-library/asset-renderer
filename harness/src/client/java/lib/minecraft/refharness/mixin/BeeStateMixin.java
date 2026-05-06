package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.BeeRenderer;
import net.minecraft.client.renderer.entity.state.BeeRenderState;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces every refharness bee render to the at-rest, on-ground pose.
 *
 * <p>{@code BeeModel.setupAnim} routes between two animation modes by
 * {@link BeeRenderState#isOnGround isOnGround}:
 * <ul>
 *   <li>{@code false} (flying, vanilla default for a transient bee): wing flap math runs
 *       (wings rotate by {@code cos(ageInTicks * 120.32) * π * 0.15}) and the body bobs
 *       via {@code bobUpAndDown} which applies {@code bone.xRot = 0.1 + cos(0)*π*0.025 ≈
 *       0.179 rad} (~10° forward head-down tilt) - the body looks like it's in mid-flight
 *       even with {@code ageInTicks=0} frozen, because {@code cos(0)=1} hits the peak of
 *       the bob.</li>
 *   <li>{@code true} (landed): both the wing-flap block and the body-bob block are
 *       skipped via {@code if (!isOnGround)} guards. Wings and body stay at the model's
 *       authored rest pose - flat wings, level body, no tilt - which is the inventory-
 *       portrait look.</li>
 * </ul>
 * Asset-renderer doesn't currently support animation, so the most useful ground-truth
 * reference is the rest pose. Pin {@code state.isOnGround = true}.
 *
 * <p>Same injection-point reasoning as {@code PufferfishStateMixin}: the field is set by
 * {@link BeeRenderer}'s subclass {@code extractRenderState} <em>after</em> the call to
 * {@code super.extractRenderState(...)}, so {@code FreezeAnimationStateMixin} on the base
 * renderer would fire before the subclass writes the field and have its value
 * overwritten. Injecting at the subclass return point captures the post-vanilla-write
 * state and overrides it.
 */
@Mixin(BeeRenderer.class)
public abstract class BeeStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/bee/Bee;Lnet/minecraft/client/renderer/entity/state/BeeRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$forceAtRestPose(Bee entity, BeeRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.isOnGround = true;
    }
}
