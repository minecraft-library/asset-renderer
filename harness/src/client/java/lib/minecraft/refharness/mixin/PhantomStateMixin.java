package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.PhantomRenderer;
import net.minecraft.client.renderer.entity.state.PhantomRenderState;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pins {@link PhantomRenderState#flapTime flapTime} to {@code 0} so phantom wings render
 * at a stable, deterministic cycle position across runs.
 *
 * <p>{@link PhantomRenderer#extractRenderState extractRenderState} sets
 * {@code state.flapTime = entity.getUniqueFlapTickOffset() + state.ageInTicks}. The
 * "unique" offset is a per-instance pseudo-random value seeded off the phantom's id when
 * it spawns, so every fresh {@code EntityType.create} call hands back a phantom whose
 * wings start at a different flap-cycle phase. {@code PhantomModel.setupAnim} then feeds
 * {@code flapTime} into {@code sin(flapTime * 7.448) * 0.05} (and similar) for each wing
 * segment - net effect: the wings visibly snap to a different rotation on every render,
 * breaking byte-stability of {@code phantom.png} across runs.
 *
 * <p>Pinning {@code flapTime = 0} gives {@code sin(0) = 0}, i.e. zero rotation per wing
 * segment - the canonical "phantom mid-glide" rest pose used by the wiki / inventory
 * portrait, and the same flat-wing pose vanilla actually shows when {@code ageInTicks}
 * happens to align with a flap-cycle zero crossing.
 *
 * <p>Same injection-point reasoning as {@code GuardianStateMixin} / {@code BeeStateMixin}:
 * {@code flapTime} is populated in the subclass override <em>after</em>
 * {@code super.extractRenderState(...)} runs, so {@code FreezeAnimationStateMixin} on the
 * base renderer fires before the subclass writes it. Inject at the subclass return point.
 */
@Mixin(PhantomRenderer.class)
public abstract class PhantomStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/client/renderer/entity/state/PhantomRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$pinFlapTime(Phantom entity, PhantomRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.flapTime = 0.0f;
    }
}
