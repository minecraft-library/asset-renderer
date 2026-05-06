package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.PufferfishRenderer;
import net.minecraft.client.renderer.entity.state.PufferfishRenderState;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces every refharness pufferfish render to the fully-puffed adult silhouette.
 *
 * <p>{@link PufferfishRenderer} dispatches between three sub-models keyed by
 * {@link PufferfishRenderState#puffState puffState}: 0 (small / deflated), 1 (medium),
 * 2 ({@link Pufferfish#STATE_FULL full puff}). Vanilla bumps the field on the entity in
 * response to nearby player proximity inside {@code Pufferfish.aiStep}, but the harness
 * builds a transient pufferfish via {@code EntityType.create} and never ticks it, so the
 * field stays at the {@code DEFAULT_PUFF_STATE = 0} baseline and every reference render
 * lands on the small deflated model. Force {@code STATE_FULL} so the iconic adult form
 * is what ships into the asset cache.
 *
 * <p>Has to inject into {@code PufferfishRenderer.extractRenderState} (not
 * {@code LivingEntityRenderer.extractRenderState}, which fires before the subclass
 * populates {@code puffState}) - vanilla's call order is
 * {@code super.extractRenderState(...)} → write {@code puffState}, so {@code @At("RETURN")}
 * here captures the post-vanilla-write state and overrides it.
 *
 * <p>Gated on {@code refharness.headless} so a non-harness consumer of this jar - if any
 * ever exists - keeps vanilla pufferfish behaviour intact.
 */
@Mixin(PufferfishRenderer.class)
public abstract class PufferfishStateMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/fish/Pufferfish;Lnet/minecraft/client/renderer/entity/state/PufferfishRenderState;F)V",
        at = @At("RETURN"))
    private void refharness$forceFullPuff(Pufferfish entity, PufferfishRenderState state, float partialTick, CallbackInfo ci) {
        if (!Boolean.getBoolean("refharness.headless")) return;
        state.puffState = Pufferfish.STATE_FULL;
    }
}
