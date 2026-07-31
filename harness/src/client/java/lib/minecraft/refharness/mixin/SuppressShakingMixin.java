package lib.minecraft.refharness.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses the {@code isShaking} bodyRot wobble in {@link LivingEntityRenderer#setupRotations}
 * so headless reference renders land on the entity's actual bind pose instead of an
 * animation-driven yaw offset.
 *
 * <h2>Why this exists</h2>
 * {@code LivingEntityRenderer.setupRotations} adds an animation wobble to {@code bodyRot}
 * when {@code isShaking(state)} returns true:
 * <pre>
 *     bodyRot += cos(floor(ageInTicks) * 3.25) * PI * 0.4   // degrees
 * </pre>
 * At {@code ageInTicks = 0} (frozen by {@link FreezeAnimationStateMixin}), {@code cos(0) = 1}
 * so the term collapses to a constant {@code PI * 0.4 ≈ 1.2566°} offset. That's exactly
 * the empirically-measured yaw delta that fixes piglin / piglin_brute / hoglin when applied
 * to the asset-renderer side (see {@code project_task_18_isshaking_wobble} memory).
 *
 * <h2>Which renderers trip the wobble at zero state</h2>
 * Base {@code LivingEntityRenderer.isShaking} returns {@code state.isFullyFrozen} - false
 * for a fresh harness entity. But overrides on subclasses can fire on other state fields:
 * <ul>
 *   <li>{@link net.minecraft.client.renderer.entity.PiglinRenderer PiglinRenderer}:
 *       {@code super.isShaking(state) || state.isConverting}. Piglins zombify in the
 *       overworld, so {@code Piglin.isConverting()} returns true for any piglin spawned
 *       in the harness's overworld flat world. Triggers the wobble for piglin /
 *       piglin_brute.</li>
 *   <li>{@link net.minecraft.client.renderer.entity.HoglinRenderer HoglinRenderer} family:
 *       does not directly override but {@code HoglinAi} / equivalent gates also flip the
 *       isShaking response on overworld conversion, producing the same 1.2566° wobble.</li>
 *   <li>Other future renderers that override {@code isShaking} on a state field that's
 *       true-at-spawn will similarly trip.</li>
 * </ul>
 *
 * <h2>Why we suppress rather than match</h2>
 * The wobble is animation, not bind pose. Asset-renderer's static iso path doesn't
 * animate, so the wobble would have to be reproduced as a per-entity yaw addend - which
 * means hand-listing every renderer-override (PiglinRenderer, etc.) AND per-entity
 * conditions ({@code isConverting} depends on biome / overworld vs nether).
 * Suppressing on the harness side keeps the reference output stable and bind-pose-shaped,
 * which is what we want to compare against.
 *
 * <p>Same {@code refharness.headless} gate as the other harness mixins.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SuppressShakingMixin {

    /**
     * Bridge to vanilla's protected {@code isShaking} so the @Redirect handler can fall
     * through to vanilla behaviour when not in headless mode.
     */
    @Mixin(LivingEntityRenderer.class)
    public interface IsShakingAccessor {
        @Invoker("isShaking")
        boolean refharness$invokeIsShaking(LivingEntityRenderState state);
    }

    @Redirect(
        method = "setupRotations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;isShaking(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z"),
        require = 0
    )
    private boolean refharness$forceNotShakingDuringSetupRotations(LivingEntityRenderer<?, ?, ?> instance, LivingEntityRenderState state) {
        if (Boolean.getBoolean("refharness.headless")) return false;
        return ((IsShakingAccessor) instance).refharness$invokeIsShaking(state);
    }
}
